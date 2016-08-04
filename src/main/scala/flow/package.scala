package datto

package object flow {
  type ContextFlow[A, B, Ctx] = akka.stream.scaladsl.Flow[FlowResult[A, Ctx], FlowResult[B, Ctx], akka.NotUsed]
}
