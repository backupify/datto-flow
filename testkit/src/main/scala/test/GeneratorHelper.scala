package datto.flow.test

import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Keep, Sink }
import datto.flow._
import scala.concurrent._
import org.scalatest.concurrent.PatienceConfiguration

trait GeneratorHelper extends PatienceConfiguration {
  def runGenerator[T, Out](gen: Generator[T, Out])(implicit
    mat: ActorMaterializer,
    patience: PatienceConfig,
    ec: ExecutionContext) = {
    val eventuallyItemsAndMat = gen.runWithMat(Sink.seq) {
      case (eventuallyMat, eventuallyItems) ⇒
        eventuallyMat.flatMap(mat ⇒
          eventuallyItems.map(items ⇒ (items.toList, mat)))
    }
    Await.result(eventuallyItemsAndMat, patience.timeout)
  }
}
