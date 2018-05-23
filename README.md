# datto.flow [![Build Status](https://travis-ci.org/backupify/datto-flow.svg?branch=master)](https://travis-ci.org/backupify/datto-flow)

Datto-flow augments [Akka-stream](http://doc.akka.io/docs/akka/current/scala/stream/index.html) to make streams that carry context easy to construct and manipulate.

When constructing a stream, one often wants to do two things:
1. Maintain a unchanged context about each item in the stream, which may be accessed by each processing step of the stream.
2. Allow a processing step to fail when processing on a particular item, such that subsequent processing steps will not be executed.

Akka-stream does not directly provide tools for these tasks. Instead, the solution is to create streams of more complicated types. For example, instead of a akka flow of type `Flow[A, B, akka.NotUsed]`, one could create a flow of type `Flow[(A, Context), (Try[B], Context), akka.NotUsed]`, where `Context` is some context type associated with each item in the stream.

Manipulating such streams is tiresome. Datto-flow provides tools to make this easy. The `FlowResult[A, Context]` type represents the items in a stream, which have a success/failure state and a context. `FlowBuilder` provides a tool for constructing flows of type `Flow[FlowResult[A, Context], FlowResult[B, Context]]` (this type is abbreviated to `ContextFlow[A, B, Context]`).

## FlowResult

The simplified definition of `FlowResult` is as follows:

```scala
case class FlowResult[+T, Ctx](value: Try[T], context: Ctx, metadata: Metadata = Metadata())
```

So, a flow result contains either a value or a failure, a context that is unchanged over the course of the stream, as well as metadata, which represents additional information that may have been generated during the course of the stream.

`FlowResult` is also a monad: it supports `map[B](f: A => B)`, `flatMap[B](f: A => Try[B])` and `mapAsync[B](f: A => Future[B])` operations. It also supports operations that expose the context, such as `mapWithContext[B](f: (A, Context, Metadata) => B)`. Many other useful operations are available. In all cases, these operations are only applied to successful flow results. Failures are propogated unchanged.

## FlowBuilder

`FlowBuilder` makes building flows of `FlowResults` easy. A standard use of `FlowBuilder` would be as follows:

```scala
val myFlow: ContextFlow[Int, Int, MyContext] = FlowBuilder[Int, MyContext]()
  .map(i => i + 2)
  .mapWithContextAsync((i, context, metadata) => i + context.baseSize)
  .flow
```

`FlowBuilder` supports building flows using most operations provided by `FlowResult`. It also supports other useful operations, such as `flatMapGrouped`, which will process multiple items at once.

## MergeFlow

 MergeFlow provides a way to apply branching logic to a flow.
 It works as follows: with each flow, you assign a predicate that determines the conditions under which that flow
 should be applied. Generally, these predicates should be mutually exclusive and cover all possible cases, but this
 is not enforced. Together, these form a list of flow-predicate pairs.
 From these, a new flow is constructed, in which (in order)
 - Each item in the flow is broadcast to N child flows
 - Each child flow is filtered according to the associated predicate.
 - The items for which the predicate is true are passed down the associated flow.
 - Items that are errors (and hence to which none of the predicates apply) are propogated in another error flow.
 - The items in each flow are merged back into a single flow.

```
              +------------+
              |            |
              |  Broadcast |
              |            |
              +------+-----+
                    /|\
                   / | \
                  /  |  \
                 /   |   \
                /    |    \
               /     |     \
              /      |      \
             /       |       \
   +----------+ +-------+ +-------------+
   |Predicate | | ...   | |   Errors    |
   |1 Applied | |       | | Propgated   |
   |          | |       | |             |
   +-----+----+ +---+---+ +------+------+
         |          |            |
         |          |            |
   +-----+----+ +---+---+        /
   | Flow 1   | | Flows |       /
   |          | |       |      /
   +-----\----+ +---+---+     /
          \         |        /
           \        |       /
            \       |      /
             \      |     /
              \     |    /
               \    |   /
                \   |  /
                 \  | /
                  \ |/
          +----------------+
          |                |
          |     Merge      |
          |                |
          +----------------+

```
For example, given two flows, one for handling positive integers and another for handling negative ones,
we can create a new flow that will handle all integers according to the combined flow operation:
```scala
   val flow: ContextFlow[Int, Int, Ctx] = MergeFlow(
     (positiveIntFlow, _ >= 0),
     (negativeIntFlow, _ < 0)
   )
```

## Generator

Often, a few asynchronous operations are needed to properly construct a `Source`. This can lead to working with objects of type `Future[Source[_]]` instead of type `Source[_]`. `Generator` encapsulates this, making such objects easier to manipulate.

Some examples:

Creating a generator from data retrieved by a future:
```scala

def getDataFuture(): Future[Stream[Int]]

val generator = Generator.future[Int, Unit] {
  getDataFuture().map(dataCollection => Source(dataCollection))
}
generator.runWith(Sink.seq) //returns a Future[Seq[Int]]

```

Performing a task prior to executing the stream:
```scala
def preRunHook(): Future[Unit]

val generator = Generator.future {
  preRunHook().map(_ => mySource)
}
generator.runWith(Sink.seq)
```

The materialization of the underlying source is always of type `Future[T]`. In the above examples, it is of type
`Future[Unit]`. For other materialization types, use the `Generator.Mat` functions:

```scala
val generator = Generator.Mat.future {
  getDataFuture().map(dataCollection => Source(dataCollection)).mapMaterializedValue(_ => 1)
}

generator.runWithMat(Sink.ignore)(Keep.left) // returns a Future[Int]
```

Another way to express the materialization in the last line would be:

```scala
generator.to(Sink.ignore).map(_.run())
```

## Publishing this library

0. Update the version in build.sbt, `git commit`, and create a tag using `git tag -a`
1. Run `core/publishSigned` in sbt console, and enter the PGP key.
2. Visit https://oss.sonatype.org/#welcome and log in.
3. Select com.datto from the list of repositories, and click close.
4. Wait a while and hit refresh.
5. Select com.datto from the list of repositories, and click release (make sure automatically drop is selected).
