package datto.flow

import akka.event.LoggingAdapter
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{ Flow, Source }

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }
import collection.immutable.{ Iterable, Seq }

/**
  * A FlowBuilder provides tools for cunstructing an akka.stream Flow that transforms a
  * FlowResult into another FlowResult. More explicitly, it is used to construct a
  * Flow[FlowResult[I, Ctx], FlowResult[T, Ctx], akka.NotUsed]. Here I is thought of as the
  * initial or input type, T is the terminal or output type, and Ctx is the context type which
  * remains unchanged throughout the flow.
  *
  * We prefer to transform FlowResults rather than the raw types I and T because it allows us to
  * carry along all the extra information contained in the FlowResult. Namely, the context,
  * the success/failure status, and the metadata. FlowBuilder makes it easy to transform the underlying
  * types without affecting this context, and will transparently propogate failures.
  *
  * @example
  * val flow: Flow[FlowResult[Int, Int], FlowResult[String, Int], akka.NotUsed] = FlowBuilder.simple[Int]
  *   .map(i => i * 2)
  *   .flatMap {
  *     case i if i < 0 => Failure(new Exception("number is negative!"))
  *     case i => Success(i)
  *   }.mapWithContext((i, context, metadata) => s"Value: $i, Context: $context")
  *   .flow
  *
  * This starts with an initial Flow containing FlowResults of integers (and with context as the initia int value).
  * It then:
  *   - multiplies the initial value by 2.
  *   - if the value is less than 0, converts it to a failure. This means that subsequent steps in the flow will not
  *     be performed.
  *   - Uses both the context and the value to produce a new String value.
  *   - retrieves the underlying akka.stream Flow.
  */
object FlowBuilder {
  /**
    * Return an empty (identity transformation) FlowBuilder in which the initial type
    *  and the context type are the same.
    */
  def simple[I](parallelism: Int): FlowBuilder[I, I, I] = apply[I, I](parallelism)

  /**
    * Return an empty (identity transformation) FlowBuilder based on the initial and context types.
    */
  def apply[I, Ctx](): FlowBuilder[I, I, Ctx] = apply(defaultParallelism)

  def apply[I, Ctx](parallelism: Int): FlowBuilder[I, I, Ctx] =
    FlowBuilder(Flow[FlowResult[I, Ctx]].map(identity), parallelism)

  def apply[I, T, Ctx](flow: Flow[FlowResult[I, Ctx], FlowResult[T, Ctx], akka.NotUsed]): FlowBuilder[I, T, Ctx] =
    FlowBuilder(flow, defaultParallelism)

  /*
   * A sane default value for parallelism. Generally, you should specify this yourself if you are doing async operations.
   * However, it's useful to not have to specify this sometimes, especially when working on a flow with no async behaviour.
   */
  private val defaultParallelism = 4
}

case class FlowBuilder[I, T, Ctx](flow: Flow[FlowResult[I, Ctx], FlowResult[T, Ctx], akka.NotUsed], defaultParallelism: Int) {

  // Methods that synchronously transform the underlying FlowResults
  def map[U](f: T ⇒ U): FlowBuilder[I, U, Ctx] = use(flow.map(_.map(f)))
  def flatMap[U](f: T ⇒ Try[U]): FlowBuilder[I, U, Ctx] = use(flow.map(_.flatMap(f)))
  def mapWithContext[U](f: (T, Ctx, Metadata) ⇒ U) = use(flow.map(_.mapWithContext(f)))
  def flatMapWithContext[U](f: (T, Ctx, Metadata) ⇒ Try[U]) = use(flow.map(_.flatMapWithContext(f)))

  // Methods that asynchronously transform the underlying FlowResults
  def mapAsync[U](f: T ⇒ Future[U])(implicit ec: ExecutionContext): FlowBuilder[I, U, Ctx] =
    use(flow.mapAsyncUnordered(defaultParallelism)(_.mapAsync(f)))
  def flatMapAsync[U](f: T ⇒ Future[Try[U]])(implicit ec: ExecutionContext): FlowBuilder[I, U, Ctx] =
    use(flow.mapAsyncUnordered(defaultParallelism)(_.flatMapAsync(f)))
  def mapWithContextAsync[U](f: (T, Ctx, Metadata) ⇒ Future[U])(implicit ec: ExecutionContext) =
    use(flow.mapAsyncUnordered(defaultParallelism)(_.mapWithContextAsync(f)))
  def flatMapWithContextAsync[U](f: (T, Ctx, Metadata) ⇒ Future[Try[U]])(implicit ec: ExecutionContext) =
    use(flow.mapAsyncUnordered(defaultParallelism)(_.flatMapWithContextAsync(f)))

  def mapConcat[U](f: T ⇒ Iterable[U]): FlowBuilder[I, U, Ctx] = use(flow.mapConcat(_.mapConcat(f)))
  def mapConcatAsyncWithContext[U](f: (T, Ctx, Metadata) ⇒ Future[Iterable[U]])(implicit ec: ExecutionContext): FlowBuilder[I, U, Ctx] =
    use(flow.mapAsyncUnordered(defaultParallelism)(_.mapWithContextAsync(f))).mapConcat({ x ⇒ x })

  def mapResult[U](f: FlowResult[T, Ctx] ⇒ FlowResult[U, Ctx]) = use(flow.map(f))
  def mapResultAsync[U](f: FlowResult[T, Ctx] ⇒ Future[FlowResult[U, Ctx]])(implicit ec: ExecutionContext) =
    use(flow.mapAsyncUnordered(defaultParallelism)(_.mapResultAsync(f)))

  def withParallelism(parallelism: Int) = FlowBuilder(flow, parallelism)

  def filterValue(predicate: T ⇒ Boolean) = filter {
    case FlowSuccess(v, _, _) ⇒ predicate(v)
    case FlowFailure(e, _, _) ⇒ false
  }

  def filter(predicate: FlowResult[T, Ctx] ⇒ Boolean) = use(flow.filter(predicate))

  def throttle(elements: Int, per: FiniteDuration = 1.second): FlowBuilder[I, T, Ctx] =
    throttle(elements, per, elements)

  def throttle(elements: Int, per: FiniteDuration, maximumBurst: Int): FlowBuilder[I, T, Ctx] =
    use(flow.throttle(elements, per, maximumBurst, akka.stream.ThrottleMode.Shaping))

  def addMetadata(entry: MetadataEntry) = use(flow.map(_.addMetadata(entry)))
  def addMetadata(entries: Seq[MetadataEntry]) = use(flow.map(_.addMetadata(entries)))
  def addMetadata(f: T ⇒ MetadataEntry) = use(flow.map(_.addMetadata(f)))

  def oldflatMapConcat[U](f: FlowResult[T, Ctx] ⇒ Generator[FlowResult[U, Ctx], Unit])(implicit ec: ExecutionContext): FlowBuilder[I, U, Ctx] = {
    val sourceFlow: Flow[FlowResult[I, Ctx], Source[FlowResult[U, Ctx], Future[Unit]], akka.NotUsed] =
      flow.mapAsyncUnordered(defaultParallelism)(res ⇒ f(res).source())
    use(sourceFlow.flatMapConcat(x ⇒ x))
  }

  @SuppressWarnings(Array("org.brianmckenna.wartremover.warts.AsInstanceOf"))
  def flatMapConcat[U](f: (T, Ctx, Metadata) ⇒ Generator[FlowResult[U, Ctx], Unit])(implicit ec: ExecutionContext): FlowBuilder[I, U, Ctx] = {
    val sourceFlow: Flow[FlowResult[I, Ctx], FlowResult[Source[FlowResult[U, Ctx], Future[Unit]], Ctx], akka.NotUsed] =
      mapWithContextAsync((value, ctx, md) ⇒ f(value, ctx, md).source()).flow
    use(sourceFlow.flatMapConcat { sourceResult ⇒
      sourceResult.value match {
        case Success(source) ⇒ source
        case Failure(e)      ⇒ Source.single(sourceResult.asInstanceOf[FlowResult[U, Ctx]])
      }
    })
  }

  @SuppressWarnings(Array("org.brianmckenna.wartremover.warts.AsInstanceOf", "org.brianmckenna.wartremover.warts.TryPartial"))
  def flatMapGrouped[U](n: Int)(f: Seq[(T, Ctx, Metadata)] ⇒ Seq[Try[U]]) = {
    val groupMap: Seq[FlowResult[T, Ctx]] ⇒ Seq[FlowResult[U, Ctx]] = (irs: Seq[FlowResult[T, Ctx]]) ⇒ {
      val (successes, failures) = irs.partition(r ⇒ r.value.isSuccess)
      val transformedSuccesses: Seq[FlowResult[U, Ctx]] =
        successes.length match {
          case 0 ⇒ Seq[FlowResult[U, Ctx]]()
          case _ ⇒ Try(f(successes.map(ir ⇒ (ir.value.get, ir.context, ir.metadata))).zip(successes).map {
            case (tryResult, FlowSuccess(_, context, metadata)) ⇒ FlowResult[U, Ctx](tryResult, context, metadata)
          }) match {
            case Success(result) ⇒ result
            case Failure(e)      ⇒ successes.map(success ⇒ FlowResult[U, Ctx](Failure[U](e), success.context, success.metadata))
          }
        }

      transformedSuccesses ++ failures.asInstanceOf[Seq[FlowResult[U, Ctx]]]
    }

    use(flow.grouped(n).map(groupMap).mapConcat(u ⇒ u))
  }

  @SuppressWarnings(Array("org.brianmckenna.wartremover.warts.AsInstanceOf", "org.brianmckenna.wartremover.warts.TryPartial"))
  def flatMapAsyncGrouped[U](n: Int)(f: Seq[(T, Ctx, Metadata)] ⇒ Future[Seq[Try[U]]])(implicit ec: ExecutionContext) = {
    val groupMap: Seq[FlowResult[T, Ctx]] ⇒ Future[Seq[FlowResult[U, Ctx]]] = (irs: Seq[FlowResult[T, Ctx]]) ⇒ {
      val (successes, failures) = irs.partition(r ⇒ r.value.isSuccess)

      // Apply the provided transformation to the successes, and reconstruct the resulting FlowResults
      val transformedSuccesses: Future[Seq[FlowResult[U, Ctx]]] =
        successes.length match {
          case 0 ⇒ Future.successful(Seq[FlowResult[U, Ctx]]())
          case _ ⇒ Try(f(successes.map(ir ⇒ (ir.value.get, ir.context, ir.metadata))).zip(FastFuture.successful(successes)).map {
            case (results, originals) ⇒ results.zip(originals).map {
              case (tryResult, FlowSuccess(_, context, metadata)) ⇒ FlowResult[U, Ctx](tryResult, context, metadata)
            }
          }) match {
            case Success(result) ⇒ result.recover({
              case e: Throwable ⇒ successes.map(success ⇒ FlowFailure[U, Ctx](e, success.context, success.metadata))
            })
            case Failure(e) ⇒ Future.successful(successes.map(success ⇒ FlowFailure[U, Ctx](e, success.context, success.metadata)))
          }
        }
      transformedSuccesses.map(_ ++ failures.asInstanceOf[Seq[FlowResult[U, Ctx]]])
    }

    use(flow.grouped(n).mapAsyncUnordered(defaultParallelism)(groupMap).mapConcat(u ⇒ u))
  }

  def from[Mat](source: Source[FlowResult[I, Ctx], Mat]): Source[FlowResult[T, Ctx], Mat] =
    source.via(flow)

  def via[U](other: Flow[FlowResult[T, Ctx], FlowResult[U, Ctx], akka.NotUsed]) = use(flow.via(other))

  def logResult(log: LoggingAdapter, prefix: String = "") =
    use(flow.map { res ⇒
      log.debug(s"$prefix $res")
      res
    })

  private def use[U, NCtx](flow: Flow[FlowResult[I, NCtx], FlowResult[U, NCtx], akka.NotUsed]) = FlowBuilder(flow, defaultParallelism)
}
