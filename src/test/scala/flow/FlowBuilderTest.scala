package datto.flow

import scala.collection.immutable.Seq
import scala.concurrent._
import scala.util._

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Keep, Sink, Source }
import akka.testkit.TestKit
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures

class FlowBuilderTest extends TestKit(ActorSystem("FlowBuilder")) with FunSpecLike with ScalaFutures {

  def initialBuilder = FlowBuilder.simple[Int](1)

  implicit val materializer = ActorMaterializer()
  implicit val ec = system.dispatcher

  def runFlow(initialValues: Int*)(flowBuilder: FlowBuilder[Int, Int, Int]): Future[Seq[FlowResult[Int, Int]]] = {
    Source(Stream(initialValues: _*))
      .map(i ⇒ FlowSuccess(i, i))
      .viaMat(flowBuilder.flow)(Keep.right)
      .toMat(Sink.seq)(Keep.right)
      .run
  }

  describe("the flow constructed by a FlowBuilder") {
    describe("synchronous transformations") {
      it("should allow the value to be mapped") {
        val outFuture = initialBuilder.map(x ⇒ x + 1)
        whenReady(runFlow(1)(outFuture)) { res ⇒
          assertResult(Success(2))(res.head.value)
        }
      }

      it("should allow the value to be flat mapped") {
        val outFuture = initialBuilder.flatMap(x ⇒ Success(x + 1))
        whenReady(runFlow(1)(outFuture)) { res ⇒
          assertResult(Success(2))(res.head.value)
        }
      }

      it("should allow the result to be mapped with its context") {
        val outFuture = initialBuilder.mapWithContext((i, ctx, _) ⇒ ctx * 3)
        whenReady(runFlow(1)(outFuture)) { res ⇒
          assertResult(Success(3))(res.head.value)
        }
      }

      it("should allow groups of results to be transformed") {
        val outFuture = initialBuilder.flatMapGrouped(2)(triples ⇒ triples.map(x ⇒ Success(x._1 + 1)))
        whenReady(runFlow(1, 2)(outFuture)) { res ⇒
          assertResult(Seq(Success(2), Success(3)))(res.map(_.value))
        }
      }

      it("should not callback when grouped if there are no successes") {
        val outFuture = initialBuilder.flatMap(x ⇒ Failure[Int](new Exception())).flatMapGrouped(1)(group ⇒ {
          group.map(x ⇒ Success(x._1))
        })
        whenReady(runFlow(1)(outFuture)) { res ⇒
          assert(res.map(_.value.isSuccess).head === false)
        }
      }

      it("should not fail the stream when the callback fails") {
        val outFuture = initialBuilder.flatMapGrouped(1)(group ⇒ {
          Seq(Success(Seq[Int]().head))
        })
        whenReady(runFlow(1)(outFuture)) { res ⇒
          assert(res.map(_.value.isSuccess).head === false)
        }
      }
    }

    describe("asynchronous transformations") {
      it("should allow the value to be mapped") {
        val outFuture = initialBuilder.mapAsync(x ⇒ Future.successful(x + 1))
        whenReady(runFlow(1)(outFuture)) { res ⇒
          assertResult(Success(2))(res.head.value)
        }
      }

      it("should allow the value to be flat mapped") {
        val outFuture = initialBuilder.flatMapAsync(x ⇒ Future.successful(Success(x + 1)))
        whenReady(runFlow(1)(outFuture)) { res ⇒
          assertResult(Success(2))(res.head.value)
        }
      }

      it("should allow the entire result to be mapped") {
        val outFuture = initialBuilder.mapWithContextAsync((i, ctx, _) ⇒ Future.successful(ctx * 3))
        whenReady(runFlow(1)(outFuture)) { res ⇒
          assertResult(Success(3))(res.head.value)
        }
      }

      it("should allow groups of results to be transformed") {
        val e = new Exception
        val outFuture = initialBuilder.flatMapAsyncGrouped(2)(triples ⇒ Future.successful(Seq(Success(1), Failure(e))))
        whenReady(runFlow(1, 2)(outFuture)) { res ⇒
          assertResult(Success(1))(res.head.value)
          assertResult(Failure(e))(res(1).value)
        }
      }

      it("should apply the transformation to any leftover elements") {
        val outFuture = initialBuilder.flatMapAsyncGrouped(2)(triples ⇒ Future.successful(triples.map(x ⇒ Success(x._1))))
        whenReady(runFlow(1, 2, 3)(outFuture)) { res ⇒
          assertResult(Success(1))(res.head.value)
          assertResult(Success(2))(res(1).value)
          assertResult(Success(3))(res(2).value)
        }
      }

      it("should not callback when grouped if there are no successes") {
        val outFuture = initialBuilder.flatMap(x ⇒ Failure[Int](new Exception())).flatMapAsyncGrouped(1)(group ⇒ {
          Future.successful(group.map(x ⇒ Success(x._1)))
        })
        whenReady(runFlow(1)(outFuture)) { res ⇒
          assert(res.map(_.value.isSuccess).head === false)
        }
      }

      it("should not fail the stream when the callback fails") {
        val outFuture = initialBuilder.flatMapAsyncGrouped(1)(group ⇒ {
          Future.successful(Seq(Success(Seq[Int]().head)))
        })
        whenReady(runFlow(1)(outFuture)) { res ⇒
          assert(res.map(_.value.isSuccess).head === false)
        }
      }

      it("should not fail the stream when the future fails") {
        val outFuture = initialBuilder.flatMapAsyncGrouped(1)(group ⇒ {
          Future.failed[Seq[Try[Int]]](new Exception())
        })
        whenReady(runFlow(1)(outFuture)) { res ⇒
          assert(res.map(_.value.isSuccess).head === false)
        }
      }
    }
  }
}
