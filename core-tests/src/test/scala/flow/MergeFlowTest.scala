package datto.flow

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.testkit.TestKit
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures

class MergeFlowTest extends TestKit(ActorSystem("MergeFlowTest")) with FunSpecLike with ScalaFutures {

  implicit val ec = system.dispatcher

  implicit val patience = PatienceConfig(5.seconds, 10.milliseconds)

  case object MockError extends Exception("error")

  val sumFlow      = FlowBuilder[Int, String](2).map(x => x + 1).flow
  val subtractFlow = FlowBuilder[Int, String](2).map(x => x - 1).flow
  val failureFlow  = FlowBuilder[Int, String](2).flatMap(x => Failure(MockError)).flow

  def runFlow(initialValues: Seq[Try[Int]])(flow: ContextFlow[Int, Int, String]): Future[Seq[FlowResult[Int, String]]] =
    Source(Stream(initialValues: _*))
      .map(i => FlowResult(i, "context"))
      .viaMat(flow)(Keep.right)
      .toMat(Sink.seq)(Keep.right)
      .run

  describe("Merging multiple flows") {
    it("should send items down the relevant flows according to the predicate") {
      val items      = Seq(1, -1, 2).map(Success(_))
      val mergedFlow = MergeFlow[Int, Int, String]((sumFlow, _ > 0), (subtractFlow, _ < 0))

      whenReady(runFlow(items)(mergedFlow)) { itemResults =>
        assert(Seq(Success(2), Success(-2), Success(3)) == itemResults.map(_.value))
      }
    }

    it("should propogate errors created in the child flows to the produced output") {
      val items      = Seq(1, -1, 2).map(Success(_))
      val mergedFlow = MergeFlow[Int, Int, String]((sumFlow, _ > 0), (failureFlow, _ < 0))

      whenReady(runFlow(items)(mergedFlow)) { itemResults =>
        assert(Seq(Success(2), Failure(MockError), Success(3)) == itemResults.map(_.value))
      }
    }

    it("should propogate errors that enter the flow input as output") {
      val items      = Seq(Failure(MockError), Failure(MockError), Success(2))
      val mergedFlow = MergeFlow[Int, Int, String]((sumFlow, _ > 0), (subtractFlow, _ < 0))

      whenReady(runFlow(items)(mergedFlow)) { itemResults =>
        assert(Seq(Failure(MockError), Failure(MockError), Success(3)) == itemResults.map(_.value))
      }
    }
  }
}
