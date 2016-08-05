package datto.flow.test

import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Keep, Sink }
import datto.flow._
import scala.concurrent._
import scala.concurrent.duration._

trait GeneratorHelper {
  def runGenerator[T, Out](gen: Generator[T, Out])(implicit mat: ActorMaterializer) = {
    val source = Await.result(gen.source(), 1.second)
    val (matFuture, itemsFuture) = source.toMat(Sink.seq)(Keep.both).run
    (Await.result(itemsFuture, 1.second).toList, Await.result(matFuture, 1.second))
  }
}
