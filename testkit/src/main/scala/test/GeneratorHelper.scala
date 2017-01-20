package datto.flow.test

import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Keep, Sink }
import datto.flow._
import scala.concurrent._
import org.scalatest.concurrent.PatienceConfiguration

trait GeneratorHelper extends PatienceConfiguration {
  def runGenerator[T, Out](gen: Generator[T, Out])(implicit mat: ActorMaterializer, patience: PatienceConfig) = {
    val source = Await.result(gen.source(), patience.timeout)
    val (matFuture, itemsFuture) = source.toMat(Sink.seq)(Keep.both).run
    (Await.result(itemsFuture, patience.timeout).toList, Await.result(matFuture, patience.timeout))
  }
}
