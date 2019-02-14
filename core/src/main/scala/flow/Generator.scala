package datto.flow

import akka.stream.{ ActorMaterializer, FlowShape, Graph }
import akka.stream.scaladsl.{ Flow, Keep, RunnableGraph, Sink, Source }
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import scala.collection.immutable

case class GeneratorOptions(onShutdown: () ⇒ Future[Unit])

object GeneratorOptions {
  def fromFuture(futureOptions: Future[Option[GeneratorOptions]])(
    implicit
    ec: ExecutionContext): GeneratorOptions =
    GeneratorOptions(() ⇒
      futureOptions.flatMap {
        case Some(options) ⇒ options.onShutdown()
        case None          ⇒ Future({})
      })
}

object Generator {

  object Mat {
    def future[T, Out](
      sourceBuilder: ⇒ Future[Source[T, Future[Out]]],
      maybeOptions: Option[GeneratorOptions] = None): Generator[T, Out] =
      new Generator(() ⇒ sourceBuilder, maybeOptions)

    def apply[T, Out](
      source: ⇒ Source[T, Future[Out]],
      maybeOptions: Option[GeneratorOptions] = None): Generator[T, Out] =
      Generator.Mat.future(Future.successful(source), maybeOptions)
  }

  def future[T](
    sourceBuilder: ⇒ Future[Source[T, akka.NotUsed]],
    maybeOptions: Option[GeneratorOptions] = None)(
    implicit
    ec: ExecutionContext): Generator[T, Unit] =
    new Generator(() ⇒ sourceBuilder.map(toUnit), maybeOptions)

  def apply[T](
    source: Source[T, akka.NotUsed],
    maybeOptions: Option[GeneratorOptions])(
    implicit
    ec: ExecutionContext): Generator[T, Unit] =
    Generator.Mat(toUnit(source), maybeOptions)

  def apply[T](source: Source[T, akka.NotUsed])(
    implicit
    ec: ExecutionContext): Generator[T, Unit] = apply(source, None)

  def apply[T](stream: Stream[T], maybeOptions: Option[GeneratorOptions])(
    implicit
    ec: ExecutionContext): Generator[T, Unit] =
    Generator.Mat(toUnit(Source(stream)), maybeOptions)

  def apply[T](stream: Stream[T])(implicit ec: ExecutionContext): Generator[T, Unit] =
    apply(stream, None)

  def empty[T](implicit ec: ExecutionContext) =
    Generator[T](Source.empty[T], None)

  def single[T](item: T, maybeOptions: Option[GeneratorOptions] = None)(
    implicit
    ec: ExecutionContext) =
    Generator[T](Source.single(item), maybeOptions)

  def iterator[T](
    it: () ⇒ Iterator[T],
    maybeOptions: Option[GeneratorOptions] = None)(
    implicit
    ec: ExecutionContext) =
    Generator(Source.fromIterator(it), maybeOptions)

  def futureGenerator[T, Out](futureGen: ⇒ Future[Generator[T, Out]])(
    implicit
    ec: ExecutionContext): Generator[T, Out] =
    //TODO: For code review, let's take an extra close look at this.  I _think_ I am not making it evaluate futureGen
    //any sooner than it otherwise would have, but I want another set of eyes
    {
      val evaluatedFutureGen = futureGen

      Generator.Mat.future(
        evaluatedFutureGen.flatMap(_.source()),
        Some(GeneratorOptions.fromFuture(evaluatedFutureGen.map(gen ⇒
          gen.maybeOptions))))
    }

  def failed[T, Out](e: Throwable) =
    Generator.Mat.future[T, Out](Future.failed[Source[T, Future[Out]]](e), None)

  private def toUnit[T](source: Source[T, akka.NotUsed])(
    implicit
    ec: ExecutionContext): Source[T, Future[Unit]] =
    source.mapMaterializedValue(_ ⇒ Future.successful({}))
}

class Generator[+T, +Out](
    val source: () ⇒ Future[Source[T, Future[Out]]],
    val maybeOptions: Option[GeneratorOptions]) {
  def map[U](f: T ⇒ U)(implicit ec: ExecutionContext): Generator[U, Out] =
    use(() ⇒ source().map(_.map(f)))

  def mapAsync[U](parallelism: Int)(f: T ⇒ Future[U])(
    implicit
    ec: ExecutionContext): Generator[U, Out] =
    use(() ⇒ source().map(_.mapAsyncUnordered(parallelism)(f)))

  def mapAsyncOrdered[U](parallelism: Int)(f: T ⇒ Future[U])(
    implicit
    ec: ExecutionContext): Generator[U, Out] =
    use(() ⇒ source().map(_.mapAsync(parallelism)(f)))

  def mapConcat[U](f: T ⇒ immutable.Iterable[U])(
    implicit
    ec: ExecutionContext): Generator[U, Out] =
    use(() ⇒ source().map(_.mapConcat(f)))

  def mapMaterializedValue[Out2](f: Out ⇒ Out2)(implicit ec: ExecutionContext) =
    flatMapMaterializedValue(out ⇒ Future.successful(f(out)))

  def flatMapMaterializedValue[Out2](f: Out ⇒ Future[Out2])(
    implicit
    ec: ExecutionContext) =
    use(() ⇒
      source().map(_.mapMaterializedValue(outFuture ⇒ outFuture.flatMap(f))))

  def recoverMaterializedValue[Out2 >: Out](
    p: PartialFunction[Throwable, Out2])(
    implicit
    ec: ExecutionContext): Generator[T, Out2] =
    use(() ⇒
      source().map(_.mapMaterializedValue(outFuture ⇒ outFuture.recover(p))))

  def recoverWithMaterializedValue[Out2 >: Out](
    p: PartialFunction[Throwable, Future[Out2]])(
    implicit
    ec: ExecutionContext): Generator[T, Out2] =
    use(() ⇒
      source().map(_.mapMaterializedValue(outFuture ⇒
        outFuture.recoverWith[Out2](p))))

  def via[U](flow: Graph[FlowShape[T, U], akka.NotUsed])(
    implicit
    ec: ExecutionContext): Generator[U, Out] =
    use(() ⇒ source().map(_.via(flow)))

  def via[U](flow: Flow[T, U, akka.NotUsed])(
    implicit
    ec: ExecutionContext): Generator[U, Out] =
    use(() ⇒ source().map(_.via(flow)))

  def viaMat[U, Mat2, Mat3](flow: Graph[FlowShape[T, U], Mat2])(
    combine: (Future[Out], Mat2) ⇒ Future[Mat3])(
    implicit
    ec: ExecutionContext): Generator[U, Mat3] =
    use(() ⇒ source().map(_.viaMat(flow)(combine)))

  def viaMat[U, Mat2, Mat3](flow: Flow[T, U, Mat2])(
    combine: (Future[Out], Mat2) ⇒ Future[Mat3])(
    implicit
    ec: ExecutionContext): Generator[U, Mat3] =
    use(() ⇒ source().map(_.viaMat(flow)(combine)))

  def toMat[Out2, Out3](sink: Sink[T, Out2])(
    combine: (Future[Out], Out2) ⇒ Out3)(
    implicit
    ec: ExecutionContext): Future[RunnableGraph[Out3]] =
    source().map(_.toMat(sink)(combine))

  def to[Out2](sink: Sink[T, Out2])(implicit ec: ExecutionContext) =
    toMat(sink)(Keep.left)

  def runWithMat[Out2, Out3](sink: Sink[T, Future[Out2]])(
    combine: (Future[Out], Future[Out2]) ⇒ Future[Out3])(
    implicit
    ec: ExecutionContext,
    mat: ActorMaterializer): Future[Out3] = {
    toMat(sink)(combine).flatMap(_.run()).andThen {
      case _ ⇒ maybeOptions.foreach(_.onShutdown())
    }
  }

  def runWith[Out2](sink: Sink[T, Future[Out2]])(
    implicit
    ec: ExecutionContext,
    mat: ActorMaterializer): Future[Out2] = runWithMat(sink)(Keep.right)

  def orElse[U >: T, Out2 >: Out](other: Generator[U, Out2])(
    implicit
    ec: ExecutionContext): Generator[U, Out2] =
    use(() ⇒ source().recoverWith { case _ ⇒ other.source() })

  def concatMat[U >: T, Out2, Out3](other: Generator[U, Out2])(
    combine: (Future[Out], Future[Out2]) ⇒ Future[Out3])(
    implicit
    ec: ExecutionContext): Generator[U, Out3] =
    use(() ⇒
      for {
        source1 ← source()
        source2 ← other.source()
      } yield source1.concatMat(source2)(combine))

  def filter(predicate: T ⇒ Boolean)(implicit ec: ExecutionContext) =
    use(() ⇒ source().map(_.filter(predicate)))

  def concat[U >: T](other: Generator[U, _])(
    implicit
    ec: ExecutionContext): Generator[U, Out] =
    concatMat(other)(Keep.left)

  def grouped(size: Int)(
    implicit
    ec: ExecutionContext): Generator[Seq[T], Out] =
    use(() ⇒ source().map(_.grouped(size)))

  def throttle(elements: Int, per: FiniteDuration = 1.second)(
    implicit
    ec: ExecutionContext): Generator[T, Out] =
    throttle(elements, per, elements)

  def throttle(elements: Int, per: FiniteDuration, maximumBurst: Int)(
    implicit
    ec: ExecutionContext): Generator[T, Out] =
    use(
      () ⇒
        source().map(
          _.throttle(
            elements,
            per,
            maximumBurst,
            akka.stream.ThrottleMode.Shaping)))

  def throttle(
    elements: Int,
    per: FiniteDuration,
    maximumBurst: Int,
    costCalculation: (T) ⇒ Int)(
    implicit
    ec: ExecutionContext): Generator[T, Out] =
    throttle(
      elements,
      per,
      maximumBurst,
      costCalculation,
      akka.stream.ThrottleMode.Shaping)

  def throttle(
    elements: Int,
    per: FiniteDuration,
    maximumBurst: Int,
    costCalculation: (T) ⇒ Int,
    mode: akka.stream.ThrottleMode)(
    implicit
    ec: ExecutionContext): Generator[T, Out] =
    use(
      () ⇒
        source().map(
          _.throttle(elements, per, maximumBurst, costCalculation, mode)))

  def flatMapConcat[U](parallelism: Int)(f: T ⇒ Generator[U, Unit])(
    implicit
    ec: ExecutionContext) = use { () ⇒
    mapAsync(parallelism)(x ⇒ f(x).source())
      .source()
      .map(_.flatMapConcat(x ⇒ x))
  }

  def classifyErrors[Out2 >: Out](
    classifier: PartialFunction[Throwable, Throwable])(
    implicit
    ec: ExecutionContext) =
    use { () ⇒
      source()
        .recoverWith(classifier.andThen(Future.failed))
        .map(s ⇒
          s.recoverWithRetries(1, {
            case e if classifier.isDefinedAt(e) ⇒ Source.failed(classifier(e))
          }))
        .map(_.mapMaterializedValue { future ⇒
          future.recoverWith(classifier.andThen(Future.failed))
        })
    }

  def toSource(implicit ec: ExecutionContext): Source[T, Future[Out]] =
    Source.fromFutureSource(source()).mapMaterializedValue { ff ⇒
      for {
        f1 ← ff
        f2 ← f1
      } yield f2
    }

  private def use[U, Out2](source: () ⇒ Future[Source[U, Future[Out2]]]) =
    new Generator(source, maybeOptions)
}
