package datto.flow

import akka.stream.{ ActorMaterializer, FlowShape, Graph }
import akka.stream.scaladsl.{ Flow, Keep, RunnableGraph, Sink, Source }
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import scala.collection.immutable

object Generator {

  object Mat {
    def future[T, Out](sourceBuilder: ⇒ Future[Source[T, Future[Out]]]): Generator[T, Out] =
      new Generator(() ⇒ sourceBuilder)

    def apply[T, Out](source: ⇒ Source[T, Future[Out]]): Generator[T, Out] =
      Generator.Mat.future(Future.successful(source))
  }

  def future[T](sourceBuilder: ⇒ Future[Source[T, akka.NotUsed]])(implicit ec: ExecutionContext): Generator[T, Unit] =
    new Generator(() ⇒ sourceBuilder.map(toUnit))

  def apply[T](source: Source[T, akka.NotUsed])(implicit ec: ExecutionContext): Generator[T, Unit] =
    Generator.Mat(toUnit(source))

  def apply[T](stream: Stream[T])(implicit ec: ExecutionContext): Generator[T, Unit] =
    Generator.Mat(toUnit(Source(stream)))

  def empty[T](implicit ec: ExecutionContext) = Generator[T](Source.empty[T])

  def single[T](item: T)(implicit ec: ExecutionContext) = Generator[T](Source.single(item))

  def iterator[T](it: () ⇒ Iterator[T])(implicit ec: ExecutionContext) =
    Generator(Source.fromIterator(it))

  def futureGenerator[T, Out](futureGen: ⇒ Future[Generator[T, Out]])(implicit ec: ExecutionContext) =
    Generator.Mat.future(futureGen.flatMap(_.source()))

  def failed[T, Out](e: Throwable) = Generator.Mat.future[T, Out](Future.failed[Source[T, Future[Out]]](e))

  private def toUnit[T](source: Source[T, akka.NotUsed])(implicit ec: ExecutionContext): Source[T, Future[Unit]] =
    source.mapMaterializedValue(_ ⇒ Future.successful({}))
}

class Generator[+T, +Out](val source: () ⇒ Future[Source[T, Future[Out]]]) {
  def map[U](f: T ⇒ U)(implicit ec: ExecutionContext): Generator[U, Out] =
    use(() ⇒ source().map(_.map(f)))

  def mapAsync[U](parallelism: Int)(f: T ⇒ Future[U])(implicit ec: ExecutionContext): Generator[U, Out] =
    use(() ⇒ source().map(_.mapAsyncUnordered(parallelism)(f)))

  def mapAsyncOrdered[U](parallelism: Int)(f: T ⇒ Future[U])(implicit ec: ExecutionContext): Generator[U, Out] =
    use(() ⇒ source().map(_.mapAsync(parallelism)(f)))

  def mapConcat[U](f: T ⇒ immutable.Iterable[U])(implicit ec: ExecutionContext): Generator[U, Out] =
    use(() ⇒ source().map(_.mapConcat(f)))

  def mapMaterializedValue[Out2](f: Out ⇒ Out2)(implicit ec: ExecutionContext) =
    flatMapMaterializedValue(out ⇒ Future.successful(f(out)))

  def flatMapMaterializedValue[Out2](f: Out ⇒ Future[Out2])(implicit ec: ExecutionContext) =
    use(() ⇒ source().map(_.mapMaterializedValue(outFuture ⇒ outFuture.flatMap(f))))

  def recoverMaterializedValue[Out2 >: Out](p: PartialFunction[Throwable, Out2])(implicit ec: ExecutionContext): Generator[T, Out2] =
    use(() ⇒ source().map(_.mapMaterializedValue(outFuture ⇒ outFuture.recover(p))))

  def recoverWithMaterializedValue[Out2 >: Out](p: PartialFunction[Throwable, Future[Out2]])(implicit ec: ExecutionContext): Generator[T, Out2] =
    use(() ⇒ source().map(_.mapMaterializedValue(outFuture ⇒ outFuture.recoverWith[Out2](p))))

  def via[U](flow: Graph[FlowShape[T, U], akka.NotUsed])(implicit ec: ExecutionContext): Generator[U, Out] =
    use(() ⇒ source().map(_.via(flow)))

  def via[U](flow: Flow[T, U, akka.NotUsed])(implicit ec: ExecutionContext): Generator[U, Out] =
    use(() ⇒ source().map(_.via(flow)))

  def viaMat[U, Mat2, Mat3](flow: Graph[FlowShape[T, U], Mat2])(combine: (Future[Out], Mat2) ⇒ Future[Mat3])(implicit ec: ExecutionContext): Generator[U, Mat3] =
    use(() ⇒ source().map(_.viaMat(flow)(combine)))

  def viaMat[U, Mat2, Mat3](flow: Flow[T, U, Mat2])(combine: (Future[Out], Mat2) ⇒ Future[Mat3])(implicit ec: ExecutionContext): Generator[U, Mat3] =
    use(() ⇒ source().map(_.viaMat(flow)(combine)))

  def toMat[Out2, Out3](sink: Sink[T, Out2])(combine: (Future[Out], Out2) ⇒ Out3)(implicit ec: ExecutionContext): Future[RunnableGraph[Out3]] =
    source().map(_.toMat(sink)(combine))

  def to[Out2](sink: Sink[T, Out2])(implicit ec: ExecutionContext) = toMat(sink)(Keep.left)

  def runWithMat[Out2, Out3](sink: Sink[T, Future[Out2]])(combine: (Future[Out], Future[Out2]) ⇒ Future[Out3])(implicit ec: ExecutionContext, mat: ActorMaterializer): Future[Out3] =
    toMat(sink)(combine).flatMap(_.run())

  def runWith[Out2](sink: Sink[T, Future[Out2]])(implicit ec: ExecutionContext, mat: ActorMaterializer): Future[Out2] = runWithMat(sink)(Keep.right)

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

  def grouped(size: Int)(implicit ec: ExecutionContext): Generator[Seq[T], Out] = use(() ⇒ source().map(_.grouped(size)))

  def throttle(elements: Int, per: FiniteDuration = 1.second)(implicit ec: ExecutionContext): Generator[T, Out] =
    throttle(elements, per, elements)

  def throttle(elements: Int, per: FiniteDuration, maximumBurst: Int)(implicit ec: ExecutionContext): Generator[T, Out] =
    use(() ⇒ source().map(_.throttle(elements, per, maximumBurst, akka.stream.ThrottleMode.Shaping)))

  def throttle(elements: Int, per: FiniteDuration, maximumBurst: Int,
    costCalculation: (T) ⇒ Int)(implicit ec: ExecutionContext): Generator[T, Out] =
    throttle(elements, per, maximumBurst, costCalculation, akka.stream.ThrottleMode.Shaping)

  def throttle(elements: Int, per: FiniteDuration, maximumBurst: Int,
    costCalculation: (T) ⇒ Int, mode: akka.stream.ThrottleMode)(implicit ec: ExecutionContext): Generator[T, Out] =
    use(() ⇒ source().map(_.throttle(elements, per, maximumBurst, costCalculation, mode)))

  def flatMapConcat[U](parallelism: Int)(f: T ⇒ Generator[U, Unit])(implicit ec: ExecutionContext) = use { () ⇒
    mapAsync(parallelism)(x ⇒ f(x).source()).source().map(_.flatMapConcat(x ⇒ x))
  }

  def classifyErrors[Out2 >: Out](classifier: PartialFunction[Throwable, Throwable])(implicit ec: ExecutionContext) = use { () ⇒
    source()
      .recoverWith(classifier.andThen(Future.failed))
      .map(s ⇒ s.recoverWithRetries(1, {
        case e if classifier.isDefinedAt(e) ⇒ Source.failed(classifier(e))
      }))
      .map(_.mapMaterializedValue { future ⇒
        future.recoverWith(classifier.andThen(Future.failed))
      })
  }

  def toSource(implicit ec: ExecutionContext): Source[T, Future[Out]] = Source.fromFutureSource(source()).mapMaterializedValue { ff ⇒
    for {
      f1 ← ff
      f2 ← f1
    } yield f2
  }

  private def use[U, Out2](source: () ⇒ Future[Source[U, Future[Out2]]]) = new Generator(source)
}
