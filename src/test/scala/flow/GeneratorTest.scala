package datto.flow

import scala.concurrent._

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Source, Keep }
import akka.testkit.TestKit
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures

class GeneratorTest extends TestKit(ActorSystem("GeneratorTest")) with FunSpecLike with ScalaFutures with GeneratorHelper {

  implicit val materializer = ActorMaterializer()
  implicit val ec = system.dispatcher

  val source = Generator(Source.single(1).mapMaterializedValue(_ ⇒ Future.successful(-1)))

  describe("creating generators") {
    it("should be creatable from a source") {
      val (items, mat) = runGenerator(source)
      assert(items === List(1))
      assert(mat === -1)
    }

    it("should be creatable from a NotUsed source") {
      val (items, mat) = runGenerator(Generator.fromNotUsedSource(Source.single(1)))
      assert(items === List(1))
      assert(mat === {})
    }
  }

  describe("modifying generators") {
    it("should support map") {
      val generator = source.map(_ + 1)
      val (items, mat) = runGenerator(generator)
      assert(items === List(2))
      assert(mat === -1)
    }

    it("should support mapAsync") {
      val generator = source.mapAsync(1)(x ⇒ Future.successful(x + 1))
      val (items, mat) = runGenerator(generator)
      assert(items === List(2))
      assert(mat === -1)
    }

    it("should support mapMaterializedValue") {
      val generator = source.mapMaterializedValue(_ - 1)
      val (items, mat) = runGenerator(generator)
      assert(items === List(1))
      assert(mat === -2)
    }

    it("should support flatMapMaterializedValue") {
      val generator = source.flatMapMaterializedValue(v ⇒ Future.successful(v - 1))
      val (items, mat) = runGenerator(generator)
      assert(items === List(1))
      assert(mat === -2)
    }
  }

  describe("combining generators") {
    describe("orElse") {
      it("should replace a failing generator with a successful one") {
        val generator = Generator[Int, Int](Future.failed(new Exception(""))).orElse(source)
        val (items, mat) = runGenerator(generator)
        assert(items === List(1))
        assert(mat === -1)
      }

      it("should not use the provided generator if the first executes successfully") {
        val generator = source.map(_ + 1).orElse(source)
        val (items, mat) = runGenerator(generator)
        assert(items === List(2))
        assert(mat === -1)
      }
    }

    describe("concatMat") {
      val otherSource = source.map(_ + 1).mapMaterializedValue(_ - 1)

      it("should concat the results of the two generators in the correct order") {
        val generator = otherSource.concatMat(source)(Keep.left)
        val (items, mat) = runGenerator(generator)
        assert(items === List(2, 1))
        assert(mat === -2)
      }

      it("should materialize the specified value according to the provided combiner") {
        val generator = otherSource.concatMat(source)(Keep.right)
        val (items, mat) = runGenerator(generator)
        assert(items === List(2, 1))
        assert(mat === -1)
      }
    }
  }
}
