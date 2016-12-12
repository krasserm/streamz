Camel DSL for Akka Streams
--------------------------

[Apache Camel endpoints](http://camel.apache.org/components.html) can be integrated into [Akka Stream](http://doc.akka.io/docs/akka/2.4/scala/stream/index.html) applications with a [Scala DSL](#scala-dsl) or [Java DSL]((#java-dsl)) that is provided by the `streamz-camel-akka` artifact.

<a name="scala-dsl">
### Scala DSL

The Scala DSL can be imported with:

```scala
import streamz.camel.akka.scaladsl._
```

Its usage requires an implicit [`StreamContext`](http://krasserm.github.io/streamz/scala-2.12/unidoc/streamz/camel/StreamContext.html) in scope. A `StreamContext` uses a `CamelContext` to manage the endpoints that are created and referenced by applications. A `StreamContext` with an internally managed `CamelContext` can be created with `StreamContext()`:

```scala
import streamz.camel.StreamContext

// contains an internally managed CamelContext 
implicit val streamContext: StreamContext = StreamContext()
```

Applications that want to re-use an existing, externally managed `CamelContext` should create a `StreamContext` with  `StreamContext(camelContext: CamelContext)`: 

```scala
import org.apache.camel.CamelContext
import streamz.camel.StreamContext

// externally managed CamelContext
val camelContext: CamelContext = ...

// re-uses the externally managed CamelContext
implicit val streamContext: StreamContext = StreamContext(camelContext)
```

After usage, a `StreamContext` should be stopped with `streamContext.stop()`. 

#### Consuming from an endpoint

An Akka Stream `Source` that emits messages consumed from a Camel endpoint can be created with `receive`. Endpoints are referenced by their [endpoint URI](http://camel.apache.org/uris.html). For example,

```scala
import akka.NotUsed
import akka.stream.scaladsl._
import streamz.camel.StreamMessage

val s1: Source[StreamMessage[String], NotUsed] = receive[String]("seda:q1")
```

creates an Akka Stream `Source` that consumes messages from the [SEDA endpoint](http://camel.apache.org/seda.html) `seda:q1` and converts them to `StreamMessage[String]`s. A [`StreamMessage[A]`](http://krasserm.github.io/streamz/scala-2.12/unidoc/streamz/camel/StreamMessage.html) contains a message `body` of type `A` and message `headers`. Calling `receive` with a `String` type parameter creates an Akka Stream `Source` that converts consumed message bodies to type `String` before emitting them as `StreamMessage[String]`. Type conversion internally uses a Camel [type converter](http://camel.apache.org/type-converter.html). An Akka Stream `Source` that only emits the converted message bodies can be created with `receiveBody`:

```scala
val s1b: Source[String, NotUsed] = receiveBody[String]("seda:q1")
```

This is equivalent to `receive[String]("seda:q1").map(_.body)`.

#### Sending to an endpoint

For sending a `StreamMessage` to a Camel endpoint, the `send` combinator should be used:

```scala
val s2: Source[StreamMessage[String], NotUsed] = s1.send("seda:q2”, parallelism = 3)
```

This initiates an in-only message [exchange](http://camel.apache.org/exchange.html) with an endpoint and continues the stream with the sent `StreamMessage`. The optional `parallelism` parameter determines how many sends can be executed in parallel and defaults to 1. For values greater than 1 the message order is still preserved by `send`.

The `send` combinator is not only available for sources of type `Source[StreamMessage[A], M]` but more generally for any source of type `Source[A, M]`:

```scala
val s2b: Source[String, NotUsed] = s1b.send("seda:q2")
```

If `A` is not a `StreamMessage`, `send` automatically wraps the message into a `StreamMessage[A]` before sending it to the endpoint and continues the stream with the unwrapped `A`. The `send` combinator is also available for flows of type 

- `Flow[A, B, M]`
- `SubFlow[A, M, Source[A, M]#Repr, Source[A, M]#Closed]` and 
- `SubFlow[B, M, Flow[A, B, M]#Repr, Flow[A, B, M]#Closed]`

Instead of using the implicit `send` combinator, applications can also use the `scaladsl.send` graph stage 

```scala
package object scaladsl {
  // ...

  def send[I](uri: String, parallelism: Int = 1)(implicit context: StreamContext): 
    Graph[FlowShape[StreamMessage[I], StreamMessage[I]], NotUsed]

  // ...
}
```

together with the Akka Streams `via` combinator:


```scala
val s2 = s1.via(send("seda:q2"))
```

#### Requesting from an endpoint

For requesting a reply from an endpoint to an input `StreamMessage`, the `request` combinator should be used:

```scala
val s3: Source[StreamMessage[Int], NotUsed] = s2.request[Int]("bean:service?method=weight”, parallelism = 3)
```

This initiates an in-out message exchange with the endpoint and continues the stream with the output `StreamMessage`. Here, a [Bean endpoint](https://camel.apache.org/bean.html) is used to call the `weight(String): Int` method on an object that is registered in the `CamelContext` under the name `service`. The input message body is used as `weight` call argument, the output message body is assigned the return value. The `receive` type parameter `[Int]` specifies the expected output value type. The output message body can also be converted to another type provided that an appropriate Camel type converter is available, (`Double`, for example). The optional `parallelism` parameter determines how many requests can be executed in parallel and defaults to 1. For values greater than 1 the message order is still preserved by `request`.

The `request` combinator is not only available for sources of type `Source[StreamMessage[A], M]` but more generally for any source of type `Source[A, M]`:

```scala
val s3b: Source[Int, NotUsed] = s2b.request[Int]("bean:service?method=weight")
```

If `A` is not a `StreamMessage`, `request` automatically wraps the message into a `StreamMessage[A]` before sending it to the endpoint and continues the stream with the unwrapped message body `B` of the output `StreamMessage[B]`. The `request` combinator is also available for flows of type 

- `Flow[A, B, M]`
- `SubFlow[A, M, Source[A, M]#Repr, Source[A, M]#Closed]` and 
- `SubFlow[B, M, Flow[A, B, M]#Repr, Flow[A, B, M]#Closed]`

Instead of using the implicit `request` combinator, applications can also use the `scaladsl.request` graph stage 

```scala
package object scaladsl {
  // ...

  def request[I, O](uri: String, parallelism: Int = 1)(implicit context: StreamContext, tag: ClassTag[O]): 
    Graph[FlowShape[StreamMessage[I], StreamMessage[O]], NotUsed]

  // ...
}
```

together with the Akka Streams `via` combinator:

```scala
val s3 = s2.via(request[String, Int]("bean:service?method=weight", parallelism = 3))
```

<a name="java-dsl">
### Java DSL

...
