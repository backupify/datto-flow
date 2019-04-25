package datto.flow

import akka.stream.scaladsl._
import akka.stream._

/**
  * MergeFlow provides a way to apply branching logic to a flow.
  * It works as follows: with each flow, you assign a predicate that determines the conditions under which that flow
  * should be applied. Generally, these predicates should be mutually exclusive and cover all possible cases, but this
  * is not enforced. Together, these form a list of flow-predicate pairs.
  * From these, a new flow is constructed, in which (in order)
  * - Each item in the flow is broadcast to N child flows
  * - Each child flow is filtered according to the associated predicate.
  * - The items for which the predicate is true are passed down the associated flow.
  * - Items that are errors (and hence to which none of the predicates apply) are propogated in another error flow.
  * - The items in each flow are merged back into a single flow.
  *
  *              +------------+
  *              |            |
  *              |  Broadcast |
  *              |            |
  *              +------+-----+
  *                    /|\
  *                   / | \
  *                  /  |  \
  *                 /   |   \
  *                /    |    \
  *               /     |     \
  *              /      |      \
  *             /       |       \
  *   +----------+ +-------+ +-------------+
  *   |Predicate | | ...   | |   Errors    |
  *   |1 Applied | |       | | Propgated   |
  *   |          | |       | |             |
  *   +-----+----+ +---+---+ +------+------+
  *         |          |            |
  *         |          |            |
  *   +-----+----+ +---+---+        /
  *   | Flow 1   | | Flows |       /
  *   |          | |       |      /
  *   +-----\----+ +---+---+     /
  *          \         |        /
  *           \        |       /
  *            \       |      /
  *             \      |     /
  *              \     |    /
  *               \    |   /
  *                \   |  /
  *                 \  | /
  *                  \ |/
  *          +----------------+
  *          |                |
  *          |     Merge      |
  *          |                |
  *          +----------------+
  *
  * @example
  *   // Given two flows, one for handling positive ints and another
  *   // for handling negative ints, (both of type ContextFlow[Int, Int, Ctx]),
  *   // create a new flow that will handle positive ints with the postive flow
  *   // and negative ints with the negative flow.
  *
  *   val flow: ContextFlow[Int, Int, Ctx] = MergeFlow(
  *     (positiveIntFlow, _ >= 0),
  *     (negativeIntFlow, _ < 0)
  *   )
  */
object MergeFlow {

  def withContext[A, B, Ctx](
      flowPredicatePairs: (ContextFlow[A, B, Ctx], (A, Ctx, Metadata) ⇒ Boolean)*
  ): ContextFlow[A, B, Ctx] = {
    val errorPropogatingFlow: ContextFlow[A, B, Ctx] =
      FlowBuilder[A, Ctx](1).filter(_.isFailure).flow.map(_.asInstanceOf[FlowResult[B, Ctx]])

    val filteredFlows = flowPredicatePairs.map { pair ⇒
      FlowBuilder[A, Ctx](1)
        .filter(r ⇒ r.value.toOption.map(value ⇒ pair._2(value, r.context, r.metadata)).getOrElse(false))
        .flow
        .via(pair._1)
    }

    val allFlows: Seq[ContextFlow[A, B, Ctx]] = filteredFlows :+ errorPropogatingFlow

    merge(allFlows: _*)
  }

  def apply[A, B, Ctx](flowPredicatePairs: (ContextFlow[A, B, Ctx], A ⇒ Boolean)*): ContextFlow[A, B, Ctx] =
    withContext(flowPredicatePairs.map {
      case (flow, predicate) ⇒ (flow, (value: A, context: Ctx, md: Metadata) ⇒ predicate(value))
    }: _*)

  def merge[A, B, Ctx](flows: ContextFlow[A, B, Ctx]*): ContextFlow[A, B, Ctx] =
    Flow.fromGraph(GraphDSL.create() { implicit b ⇒
      import GraphDSL.Implicits._
      val broadcast = b.add(Broadcast[FlowResult[A, Ctx]](flows.length))
      val merge     = b.add(Merge[FlowResult[B, Ctx]](flows.length))

      flows.zipWithIndex.foreach(pair ⇒ broadcast.out(pair._2) ~> pair._1 ~> merge.in(pair._2))
      FlowShape(broadcast.in, merge.out)
    })
}
