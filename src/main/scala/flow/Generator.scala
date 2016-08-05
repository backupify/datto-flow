package datto.flow

import akka.stream.scaladsl.{ Flow, Keep, RunnableGraph, Sink, Source }
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

object Generator {
  def future[T, Out](sourceBuilder: ⇒ Future[Source[T, Future[Out]]]): Generator[T, Out] =
    new Generator(() ⇒ sourceBuilder)

  def apply[T, Out](source: ⇒ Source[T, Future[Out]]): Generator[T, Out] =
    Generator.future(Future.successful(source))

  def empty[T, Out](implicit ec: ExecutionContext) = Generator.fromNotUsedSource[T](Source.empty[T])

  def iterator[T, Out](it: () ⇒ Iterator[T])(implicit ec: ExecutionContext) =
    Generator.fromNotUsedSource(Source.fromIterator(it))

  def futureGenerator[T, Out](futureGen: ⇒ Future[Generator[T, Out]])(implicit ec: ExecutionContext) =
    Generator.future(futureGen.flatMap(_.source()))

  def failed[T, Out](e: Throwable) = Generator.future[T, Out](Future.failed[Source[T, Future[Out]]](e))

  def fromNotUsedSource[T](source: Source[T, akka.NotUsed])(implicit ec: ExecutionContext): Generator[T, Unit] = {
    Generator(source.alsoToMat(Sink.lastOption[T])(Keep.right).mapMaterializedValue(_.map(_ ⇒ {})))
  }
}

class Generator[+T, +Out](val source: () ⇒ Future[Source[T, Future[Out]]]) {
  def map[U](f: T ⇒ U)(implicit ec: ExecutionContext): Generator[U, Out] =
    use(() ⇒ source().map(_.map(f)))

  def mapAsync[U](parallelism: Int)(f: T ⇒ Future[U])(implicit ec: ExecutionContext): Generator[U, Out] =
    use(() ⇒ source().map(_.mapAsyncUnordered(parallelism)(f)))

  def mapMaterializedValue[Out2](f: Out ⇒ Out2)(implicit ec: ExecutionContext) =
    flatMapMaterializedValue(out ⇒ Future.successful(f(out)))

  def flatMapMaterializedValue[Out2](f: Out ⇒ Future[Out2])(implicit ec: ExecutionContext) =
    use(() ⇒ source().map(_.mapMaterializedValue(outFuture ⇒ outFuture.flatMap(f))))

  def via[U](flow: Flow[T, U, akka.NotUsed])(implicit ec: ExecutionContext): Generator[U, Out] =
    use(() ⇒ source().map(_.via(flow)))

  def toMat[Out2, Out3](sink: Sink[T, Out2])(combine: (Future[Out], Out2) ⇒ Out3)(implicit ec: ExecutionContext): Future[RunnableGraph[Out3]] =
    source().map(_.toMat(sink)(combine))

  def to[Out2](sink: Sink[T, Out2])(implicit ec: ExecutionContext) = toMat(sink)(Keep.left)

  def orElse[U >: T, Out2 >: Out](other: Generator[U, Out2])(implicit ec: ExecutionContext): Generator[U, Out2] =
    use(() ⇒ source().recoverWith { case _ ⇒ other.source() })

  def concatMat[U >: T, Out2, Out3](other: Generator[U, Out2])(combine: (Future[Out], Future[Out2]) ⇒ Future[Out3])(implicit ec: ExecutionContext): Generator[U, Out3] =
    use(() ⇒ for {
      source1 ← source()
      source2 ← other.source()
    } yield source1.concatMat(source2)(combine))

  def filter(predicate: T ⇒ Boolean)(implicit ec: ExecutionContext) = use(() ⇒ source().map(_.filter(predicate)))

  def concat[U >: T](other: Generator[U, _])(implicit ec: ExecutionContext): Generator[U, Out] =
    concatMat(other)(Keep.left)

  def throttle(elements: Int, per: FiniteDuration = 1.second)(implicit ec: ExecutionContext): Generator[T, Out] =
    throttle(elements, per, elements)

  def throttle(elements: Int, per: FiniteDuration, maximumBurst: Int)(implicit ec: ExecutionContext): Generator[T, Out] =
    use(() ⇒ source().map(_.throttle(elements, per, maximumBurst, akka.stream.ThrottleMode.Shaping)))

  private def use[U, Out2](source: () ⇒ Future[Source[U, Future[Out2]]]) = new Generator(source)
}
