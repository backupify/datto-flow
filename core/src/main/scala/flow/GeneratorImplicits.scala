package datto.flow

import akka.stream.scaladsl.Source
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

object GeneratorImplicits {
  private[flow] case class WrappedFutureGenerator[+T, +Out](gen: Future[Generator[T, Out]])(
      implicit ec: ExecutionContext
  ) {
    def generator = Generator.futureGenerator(() => gen)
  }

  private[flow] case class WrappedFutureSourceMat[+T, +Out](source: Future[Source[T, Future[Out]]]) {
    def generator: Generator[T, Out] = Generator.Mat.future(() => source)
  }

  private[flow] case class WrappedFutureSource[+T](source: Future[Source[T, akka.NotUsed]])(
      implicit ec: ExecutionContext
  ) {
    def generator: Generator[T, Unit] = Generator.future(() => source)
  }

  private[flow] case class WrappedSourceMat[+T, +Out](source: Source[T, Future[Out]]) {
    def generator: Generator[T, Out] = Generator.Mat(source)
  }

  private[flow] case class WrappedSource[+T](source: Source[T, akka.NotUsed]) {
    def generator: Generator[T, Unit] = Generator(source)
  }

  private[flow] case class WrappedStream[+T](stream: LazyList[T]) {
    def generator: Generator[T, Unit] = Generator(stream)
  }

  implicit def futureGenToWrapped[T, Out](futureGen: Future[Generator[T, Out]])(implicit ec: ExecutionContext) =
    WrappedFutureGenerator(futureGen)

  implicit def futureSourceMatToWrapped[T, Out](source: Future[Source[T, Future[Out]]]) =
    WrappedFutureSourceMat(source)

  implicit def futureSourceToWrapped[T](source: Future[Source[T, akka.NotUsed]])(implicit ec: ExecutionContext) =
    WrappedFutureSource(source)

  implicit def sourceMatToWrapped[T, Out](source: Source[T, Future[Out]]) =
    WrappedSourceMat(source)

  implicit def sourceToWrapped[T](source: Source[T, akka.NotUsed]) =
    WrappedSource(source)

  implicit def sourceToWrapped[T](stream: LazyList[T]) =
    WrappedStream(stream)
}
