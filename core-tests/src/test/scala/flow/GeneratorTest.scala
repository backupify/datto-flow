package datto.flow

import scala.concurrent._
import scala.util.Failure

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Source, Keep, Sink }
import akka.testkit.TestKit
import datto.flow.test.GeneratorHelper
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import scala.concurrent._
import scala.concurrent.duration._
import GeneratorImplicits._

class GeneratorTest extends TestKit(ActorSystem("GeneratorTest")) with FunSpecLike with ScalaFutures with GeneratorHelper {

  implicit val materializer = ActorMaterializer()
  implicit val ec = system.dispatcher
  implicit val patience = PatienceConfig(5.seconds, 25.milliseconds)

  val rawSource = Source.single(1).mapMaterializedValue(_ ⇒ Future.successful(-1))
  val rawGenerator = Generator.Mat(rawSource)

  def wait[T](f: ⇒ Future[T]): T = Await.result(f, patience.timeout)

  describe("creating generators without materialized values") {
    it("should be creatable from a NotUsed Source") {
      val (items, mat) = runGenerator(Generator(Source.single(1)))
      assert(items === List(1))
      assert(mat === {})
    }

    it("should be creatable from future source") {
      val (items, mat) = runGenerator(Generator.future(() => Future(Source.single(1))))
      assert(items === List(1))
      assert(mat === {})
    }
  }

  describe("creating generators") {
    it("should be createable from a source") {
      val (items, mat) = runGenerator(rawGenerator)
      assert(items === List(1))
      assert(mat === -1)
    }

    it("should lazily evaluate anything within the generator setup") {
      var x = 0
      val gen = Generator.Mat {
        x = 1
        rawSource
      }
      assert(x === 0)
      runGenerator(gen)
      assert(x === 1)
    }

    it("should evaluate the generator setup on each run of the generator") {
      var x = 0
      val gen = Generator.Mat {
        x += 1
        rawSource
      }
      assert(x === 0)
      runGenerator(gen)
      runGenerator(gen)
      assert(x === 2)
    }

    it("should be creatable from a Future") {
      val gen = Generator.Mat.future(() => Future(rawSource))

      val (items, mat) = runGenerator(gen)
      assert(items === List(1))
      assert(mat === -1)
    }

    it("should lazily evaluate the Future within the generator setup") {
      var x = 0
      val gen = Generator.Mat.future { () =>
        Future {
          x = 1
          rawSource
        }
      }
      Thread.sleep(20)
      assert(x === 0)
      runGenerator(gen)
      assert(x === 1)
    }
  }

  describe("modifying generators") {
    it("should support map") {
      val generator = rawGenerator.map(_ + 1)
      val (items, mat) = runGenerator(generator)
      assert(items === List(2))
      assert(mat === -1)
    }

    it("should support mapAsync") {
      val generator = rawGenerator.mapAsync(1)(x ⇒ Future.successful(x + 1))
      val (items, mat) = runGenerator(generator)
      assert(items === List(2))
      assert(mat === -1)
    }

    it("should support mapMaterializedValue") {
      val generator = rawGenerator.mapMaterializedValue(_ - 1)
      val (items, mat) = runGenerator(generator)
      assert(items === List(1))
      assert(mat === -2)
    }

    it("should support flatMapMaterializedValue") {
      val generator = rawGenerator.flatMapMaterializedValue(v ⇒ Future.successful(v - 1))
      val (items, mat) = runGenerator(generator)
      assert(items === List(1))
      assert(mat === -2)
    }

    it("should support flatMapConcat") {
      val generator = rawGenerator.flatMapConcat(4)(i ⇒ Generator.iterator(() ⇒ Seq(i + 1, i + 2).iterator))
      val (items, mat) = runGenerator(generator)
      assert(items === List(2, 3))
    }
  }

  describe("combining generators") {
    describe("orElse") {
      it("should replace a failing generator with a successful one") {
        val generator = Generator.Mat.future[Int, Int](() => Future.failed(new Exception(""))).orElse(rawGenerator)
        val (items, mat) = runGenerator(generator)
        assert(items === List(1))
        assert(mat === -1)
      }

      it("should not use the provided generator if the first executes successfully") {
        val generator = rawGenerator.map(_ + 1).orElse(rawGenerator)
        val (items, mat) = runGenerator(generator)
        assert(items === List(2))
        assert(mat === -1)
      }
    }

    describe("concatMat") {
      val otherSource = rawGenerator.map(_ + 1).mapMaterializedValue(_ - 1)

      it("should concat the results of the two generators in the correct order") {
        val generator = otherSource.concatMat(rawGenerator)(Keep.left)
        val (items, mat) = runGenerator(generator)
        assert(items === List(2, 1))
        assert(mat === -2)
      }

      it("should materialize the specified value according to the provided combiner") {
        val generator = otherSource.concatMat(rawGenerator)(Keep.right)
        val (items, mat) = runGenerator(generator)
        assert(items === List(2, 1))
        assert(mat === -1)
      }
    }
  }

  describe("running Generators") {
    it("should be runnable when a sink is provided") {
      val res = wait(rawGenerator.runWith(Sink.seq))
      assert(List(1) === res.toList)
    }

    it("should allow a sink to not materialize a value") {
      val res = wait(rawGenerator.runWith(Sink.ignore))
      assert(res === akka.Done)
    }

    it("should allow for custom materialization combination functions") {
      val sumBoth = (a: Future[Int], b: Future[Int]) ⇒ a.flatMap(aVal ⇒ b.map(bVal ⇒ aVal + bVal))
      val f: Future[Int] = rawGenerator.runWithMat(Sink.head)(sumBoth)
      val res = wait(f)
      assert(0 === res)
    }
  }

  describe("classifying errors") {
    it("should modify the error of a failed future prior to the generator") {
      val gen = Generator.future(() => Future.failed(new Exception)).classifyErrors {
        case e ⇒ TestError()
      }
      val out = Await.ready(gen.runWith(Sink.seq), 5.seconds).value.get
      assert(out == Failure(TestError()))
    }

    it("should modify the error of a failed materialized value") {
      val gen = rawGenerator.flatMapMaterializedValue(_ ⇒ Future.failed(new Exception)).classifyErrors {
        case e ⇒ TestError()
      }
      val out = Await.ready(gen.runWithMat(Sink.seq)(Keep.left), 5.seconds).value.get
      assert(out == Failure(TestError()))
    }

    it("should modify the error of a generator that fails during the execution of the generator") {
      val gen = rawGenerator.map(_ ⇒ throw new Exception).classifyErrors {
        case e ⇒ TestError()
      }
      val out = Await.ready(gen.runWith(Sink.seq), 5.seconds).value.get
      assert(out == Failure(TestError()))
    }
  }

  describe("grouped") {
    it("should return groups of the specified size") {
      val groups = wait(rawGenerator.concat(rawGenerator).concat(rawGenerator).grouped(2).runWith(Sink.seq))
      assert(groups === Seq(Seq(1, 1), Seq(1)))
    }
  }

  describe("generator implicits") {
    it("should be able to flatten a future generator") {
      val futureGen: Future[Generator[Int, Unit]] = Future.successful(Generator(Source.single(1)))
      val flattened: Generator[Int, Unit] = futureGen.generator
      val res = wait(flattened.runWith(Sink.ignore))
      assert(res === akka.Done)
    }

    it("should be able to flatten a future source") {
      val futureGen = Future.successful(Source.single(1)).generator
      val res = wait(futureGen.runWith(Sink.ignore))
      assert(res === akka.Done)
    }
  }
}

case class TestError() extends Exception
