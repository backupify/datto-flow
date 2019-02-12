package datto.flow

import akka.stream.scaladsl.Source
import scala.concurrent.{ Future, ExecutionContext }
import scala.language.implicitConversions

object GeneratorImplicits {
  private[flow] case class WrappedFutureGenerator[+T, +Out](
      gen: Future[Generator[T, Out]])(implicit ec: ExecutionContext) {
    def flatten: Generator[T, Out] = Generator.futureGenerator(gen)
  }

  private[flow] case class WrappedFutureSourceMat[+T, +Out](
      source: Future[Source[T, Future[Out]]])(implicit ec: ExecutionContext) {
    def generator: Generator[T, Out] = Generator.Mat.future(source, None)
  }

  private[flow] case class WrappedFutureSource[+T](
      source: Future[Source[T, akka.NotUsed]])(implicit ec: ExecutionContext) {
    def generator: Generator[T, Unit] = Generator.future(source, None)
  }

  private[flow] case class WrappedSourceMat[+T, +Out](
      source: Source[T, Future[Out]])(implicit ec: ExecutionContext) {
    def generator: Generator[T, Out] = Generator.Mat(source, None)
  }

  private[flow] case class WrappedSource[+T](source: Source[T, akka.NotUsed])(
      implicit
      ec: ExecutionContext) {
    def generator: Generator[T, Unit] = Generator(source, None)
  }

  private[flow] case class WrappedStream[+T](stream: Stream[T])(
      implicit
      ec: ExecutionContext) {
    def generator: Generator[T, Unit] = Generator(stream, None)
  }

  implicit def futureGenToWrapped[T, Out](futureGen: Future[Generator[T, Out]])(
    implicit
    ec: ExecutionContext) =
    WrappedFutureGenerator(futureGen)

  implicit def futureSourceMatToWrapped[T, Out](
    source: Future[Source[T, Future[Out]]])(implicit ec: ExecutionContext) =
    WrappedFutureSourceMat(source)

  implicit def futureSourceToWrapped[T](
    source: Future[Source[T, akka.NotUsed]])(implicit ec: ExecutionContext) =
    WrappedFutureSource(source)

  implicit def sourceMatToWrapped[T, Out](source: Source[T, Future[Out]])(
    implicit
    ec: ExecutionContext) =
    WrappedSourceMat(source)

  implicit def sourceToWrapped[T](source: Source[T, akka.NotUsed])(
    implicit
    ec: ExecutionContext) =
    WrappedSource(source)

  implicit def sourceToWrapped[T](stream: Stream[T])(
    implicit
    ec: ExecutionContext) =
    WrappedStream(stream)
}
