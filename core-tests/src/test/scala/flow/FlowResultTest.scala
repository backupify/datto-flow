package datto.flow

import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util._

import org.scalatest._
import org.scalatest.concurrent.ScalaFutures

case object MockMetadata extends MetadataEntry

case class MockException() extends Exception("mock")

class FlowResultTest extends FunSpec with ScalaFutures {
  implicit val defaultPatience = PatienceConfig(5.seconds, 10.milliseconds)

  describe("A FlowResult") {
    describe("map") {
      it("should allow results to be mapped") {
        val res = FlowResult(Success(1), "")
        assert(res.map(_ + 1).value === Success(2))
      }

      it("should not modify failures") {
        val exception = new Exception
        val res = FlowResult[Int, String](Failure(exception), "")
        assert(res.map(_ + 1).value === Failure(exception))
      }

      it("should preserve context") {
        val res = FlowResult(Success(1), "ctx")
        assert(res.map(_ + 1).context === "ctx")
      }

      it("should preserve metadata") {
        val metadata = Metadata(Seq(MockMetadata))
        val res = FlowResult(Success(1), "", metadata)
        assert(res.map(_ + 1).metadata === metadata)
      }
    }

    describe("flatMap") {
      it("should allow results to be mapped") {
        val res = FlowResult(Success(1), "")
        assert(res.flatMap(x ⇒ Success(x + 1)).value === Success(2))
      }

      it("should allow a step to fail") {
        val exception = new Exception
        val res = FlowResult(Success(1), "")
        val out = res.flatMap(x ⇒ Failure(exception)).value
        assert(out === Failure(exception))
      }

      it("should not modify failures") {
        val exception = new Exception
        val res = FlowResult[Int, String](Failure(exception), "")
        assert(res.flatMap(x ⇒ Success(x + 1)).value === Failure(exception))
      }

      it("should preserve context") {
        val res = FlowResult(Success(1), "ctx")
        assert(res.flatMap(_ ⇒ Success(1)).context === "ctx")
      }

      it("should preserve metadata") {
        val metadata = Metadata(Seq(MockMetadata))
        val res = FlowResult(Success(1), "", metadata)
        assert(res.flatMap(_ ⇒ Success(1)).metadata === metadata)
      }
    }

    describe("mapAsync") {
      it("should allow results to be mapped") {
        val res = FlowResult(Success(1), "")
        val outFuture = res.mapAsync(x ⇒ Future.successful(x + 1))

        whenReady(outFuture) { out ⇒
          assert(out.value === Success(2))
        }
      }

      it("should convert failed futures to failed FlowResults, so that the future is always successful") {
        val res = FlowResult(Success(1), "")
        val exception = new Exception
        val outFuture = res.mapAsync(x ⇒ Future.failed(exception))

        whenReady(outFuture) { out ⇒
          assert(out.value === Failure(exception))
        }
      }
    }

    describe("addMetadata") {
      it("should allow metadata to be added") {
        val res = FlowResult(Success(1), "")
        val entry = MockMetadata
        val newRes = res.addMetadata(entry)
        assert(newRes.metadata === Metadata(Seq(entry)))

      }
    }

    describe("recoverAsync") {
      it("should recover on a specific error") {
        val res = FlowResult(Failure(MockException()), "")
        val outFuture = res.recoverAsync {
          case MockException() ⇒ Future.successful(2)
        }

        whenReady(outFuture) { out ⇒
          assert(out.value === Success(2))
        }
      }

      it("should transform errors provided in the recovery block") {
        val res = FlowResult(Failure(MockException()), "")
        val e = new Exception
        val outFuture = res.recoverAsync {
          case MockException() ⇒ Future.failed(e)
        }

        whenReady(outFuture) { out ⇒
          assert(out.value === Failure(e))
        }
      }
    }
  }
}
