package datto.flow

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.collection.immutable.{Iterable, Seq}

/**
  * A FlowResult[T, Ctx] is a Try[T] with an associated context and metadata that are are automatically
  * carried along with each transformation applied to the FlowResult. It is meant to be used as part of an
  * akka.streams Stream, making it easy for additional information about an item passing through the stream
  * to be propogated.
  */
object FlowResult {
  def apply[T, Ctx](orig: FlowResult[_, _], value: Try[T], context: Ctx, metadata: Metadata): FlowResult[T, Ctx] =
    FlowResult(value, context, metadata, orig.timings.add(value.map(v => v.getClass.toString()).getOrElse("Error")))
}

case class FlowResult[+T, Ctx](
    value: Try[T],
    context: Ctx,
    metadata: Metadata = Metadata(),
    timings: TimingList = TimingList()
) {
  // try interface
  def map[U](f: T => U)                                          = use(value.map(f))
  def transform[U](s: (T) => Try[U], f: (Throwable) => Try[U])   = use(value.transform(s, f))
  def flatMap[U](f: T => Try[U])                                 = use(value.flatMap(f))
  def filter[U](f: T => Boolean)                                 = use(value.filter(f))
  def recover[U >: T](f: PartialFunction[Throwable, U])          = use(value.recover(f))
  def recoverWith[U >: T](f: PartialFunction[Throwable, Try[U]]) = use(value.recoverWith(f))
  def orElse[U >: T](default: => Try[U])                         = use(value.orElse(default))
  def flatten[U](implicit ev: <:<[T, Try[U]])                    = use(value.flatten)
  def getOrElse[U >: T](default: => U)                           = value.getOrElse(default)
  def toOption                                                   = value.toOption
  def isFailure                                                  = value.isFailure
  def isSuccess                                                  = value.isSuccess

  def mapAsync[U](f: T => Future[U])(implicit ec: ExecutionContext): Future[FlowResult[U, Ctx]] =
    mapWithContextAsync((v, _, _) => f(v))

  def mapWithContext[U](f: (T, Ctx, Metadata) => U): FlowResult[U, Ctx] = map(v => f(v, context, metadata))

  def mapConcat[U](f: T => Iterable[U]): Iterable[FlowResult[U, Ctx]] =
    value.map(v => f(v)) match {
      case Success(iter)      => iter.map(u => use(Success(u)))
      case Failure(throwable) => Seq(FlowResult(Failure[U](throwable), context, metadata, timings))
    }

  def mapWithContextAsync[U](
      f: (T, Ctx, Metadata) => Future[U]
  )(implicit ec: ExecutionContext): Future[FlowResult[U, Ctx]] =
    value match {
      case Success(v) =>
        f(v, context, metadata)
          .map(newV => Success(newV))
          .recover { case e => Failure[U](e): Try[U] }
          .map(newV => FlowResult(this, newV, context, metadata))
      case Failure(e) => Future.successful(FlowResult(this, Failure[U](e), context, metadata))
    }

  def flatMapWithContext[U](f: (T, Ctx, Metadata) => Try[U]): FlowResult[U, Ctx] = flatMap(v => f(v, context, metadata))

  def mapResultAsync[U](
      f: FlowResult[T, Ctx] => Future[FlowResult[U, Ctx]]
  )(implicit ec: ExecutionContext): Future[FlowResult[U, Ctx]] =
    f(this).recover { case e => FlowResult(this, Failure[U](e), context, metadata) }

  def recoverAsync[U >: T](p: PartialFunction[Throwable, Future[U]])(implicit ec: ExecutionContext) = mapResultAsync {
    res =>
      res.value match {
        case Failure(e) if p.isDefinedAt(e) => p(e).map(value => res.use(Success(value)))
        case _                              => Future.successful(res)
      }
  }

  def addMetadata(entry: MetadataEntry) = ifSuccess(_ => FlowResult(this, value, context, metadata :+ entry))

  def addMetadata(entries: Seq[MetadataEntry]) = ifSuccess(_ => FlowResult(this, value, context, metadata ++ entries))

  def addMetadata(f: T => MetadataEntry) = ifSuccess(v => FlowResult(this, value, context, metadata :+ f(v)))

  private def use[U](f: => Try[U]) = FlowResult(this, f, context, metadata)

  private def ifSuccess[U](f: T => FlowResult[U, Ctx]): FlowResult[U, Ctx] = value match {
    case Success(v) => f(v)
    case Failure(e) => FlowResult(this, Failure[U](e), context, metadata)
  }

  val creationTime = System.currentTimeMillis()
}

case object FlowSuccess {
  def apply[T, Ctx](value: T, context: Ctx, metadata: Metadata) = FlowResult[T, Ctx](Success(value), context, metadata)

  def apply[T, Ctx](value: T, context: Ctx) = FlowResult[T, Ctx](Success(value), context)

  def unapply[T, Ctx](obj: FlowResult[T, Ctx]): Option[(T, Ctx, Metadata)] = obj.value match {
    case Success(v) => Some((v, obj.context, obj.metadata))
    case _          => None
  }
}

case object FlowFailure {
  def apply[T, Ctx](error: Throwable, context: Ctx, metadata: Metadata): FlowResult[T, Ctx] =
    FlowResult[T, Ctx](Failure[T](error), context, metadata)

  def apply[T, Ctx](error: Throwable, context: Ctx): FlowResult[T, Ctx] =
    FlowResult[T, Ctx](Failure[T](error), context)

  def unapply[T, Ctx](obj: FlowResult[T, Ctx]): Option[(Throwable, Ctx, Metadata)] = obj.value match {
    case Failure(e) => Some((e, obj.context, obj.metadata))
    case _          => None
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  def from[T, U, Ctx](obj: FlowResult[T, Ctx]): FlowResult[U, Ctx] = obj match {
    case FlowSuccess(v, ctx, md) => FlowResult(obj, Failure[U](new Exception("Coerced to Failure")), ctx, md)
    case FlowFailure(e, ctx, md) => obj.asInstanceOf[FlowResult[U, Ctx]]
  }
}

object FlowResultFutureImplicits {
  import scala.language.implicitConversions

  implicit def futureToWrapper[T, Ctx](future: Future[FlowResult[T, Ctx]]) = FutureWrapper(future)
  implicit def wrapperToFuture[T, Ctx](wrapper: FutureWrapper[T, Ctx])     = wrapper.future

  case class FutureWrapper[T, Ctx](future: Future[FlowResult[T, Ctx]]) {
    def mapResultValue[U](f: T => U)(implicit ec: ExecutionContext): Future[FlowResult[U, Ctx]] =
      flatMapResultValue(v => Success(f(v)))

    def flatMapResultValue[U](f: T => Try[U])(implicit ec: ExecutionContext): Future[FlowResult[U, Ctx]] =
      future.map { res =>
        try {
          res.flatMap(f)
        } catch { case e: Exception => FlowFailure[U, Ctx](e, res.context) }
      }
  }
}

private[flow] case class TimingStage(description: String, taken: Long) {
  override def toString() = s"($description: ${taken.toString} ms)"
}

private[flow] case class TimingList(
    created: Long = System.currentTimeMillis(),
    stages: Vector[TimingStage] = Vector[TimingStage]()
) {
  def add(desc: String)   = TimingList(created, stages :+ TimingStage(desc, System.currentTimeMillis() - created))
  override def toString() = s"Timings(${stages.mkString(",")})"
}
