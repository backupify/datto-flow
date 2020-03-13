package datto.flow

trait MetadataEntry

case class Metadata(entries: Seq[MetadataEntry]) extends Seq[MetadataEntry] {
  import scala.reflect.ClassTag

  def iterator          = entries.iterator
  def apply(index: Int) = entries(index)
  def length            = entries.length

  def ofType[T <: MetadataEntry](implicit ev: ClassTag[T]): Seq[T] = entries.flatMap {
    case entry: T => Some(entry)
    case _        => None: Option[T]
  }

  def findOfType[T <: MetadataEntry](implicit ev: ClassTag[T]): Option[T] = ofType[T].headOption
}

object Metadata {
  import scala.language.implicitConversions

  implicit def seqToMetadata(entries: Seq[MetadataEntry]): Metadata = Metadata(entries)

  def apply(): Metadata = Seq[MetadataEntry]()
}
