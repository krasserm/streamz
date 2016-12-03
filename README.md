Streamz
=======

[![Gitter](https://badges.gitter.im/krasserm/streamz.svg)](https://gitter.im/krasserm/streamz?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge)
[![Build Status](https://travis-ci.org/krasserm/streamz.svg?branch=master)](https://travis-ci.org/krasserm/streamz)

Streamz is a combinator library for integrating [Functional Streams for Scala](https://github.com/functional-streams-for-scala/fs2) (FS2), [Akka Streams](http://doc.akka.io/docs/akka/2.4/scala/stream/index.html) and [Apache Camel](http://camel.apache.org/). It integrates

- **FS2 with Akka Streams:** FS2 `Stream`s, `Pipe`s and `Sink`s can be converted to Akka Stream `Source`s, `Flow`s and `Sink`s, respectively, and vice versa with [Stream converters](#stream-converters).
- **FS2 with Apache Camel:** [Camel endpoints](http://camel.apache.org/components.html) can be integrated into FS2 streams with the [Camel DSL for FS2](#dsl-for-fs2).
- **Akka Streams with Apache Camel:** Camel endpoints can be integrated into Akka Streams with the [Camel DSL for Akka Streams](#dsl-for-as).

![Streamz intro](images/streamz-intro.png)

API docs
--------

- [API docs for Scala 2.12](http://krasserm.github.io/streamz/scala-2.12/unidoc/index.html)
- [API docs for Scala 2.11](http://krasserm.github.io/streamz/scala-2.11/unidoc/index.html)

Dependencies
------------

Streamz artifacts are available for Scala 2.11 and 2.12:

    resolvers += "krasserm at bintray" at "http://dl.bintray.com/krasserm/maven"

    // transitively depends on akka-stream 2.4.14
    libraryDependencies += "com.github.krasserm" %% "streamz-akka" % "0.6"

    // transitively depends on Apache Camel 2.18.0
    libraryDependencies += "com.github.krasserm" %% "streamz-camel" % "0.6"

<a name="stream-converters">
Stream converters
-----------------

Stream converters convert FS2 `Stream`s, `Pipe`s and `Sink`s to Akka Stream `Source`s, `Flow`s and `Sink`s, respectively, and vice versa. They are provided by the `streamz-akka` artifact and can be imported with 

```scala
import streamz.akka._
```

and require the following `implicit`s in scope:

```scala
import akka.actor.ActorRefFactory
import akka.stream.ActorMaterializer

import scala.concurrent.ExecutionContext

val factory: ActorRefFactory = ...

implicit val executionContext: ExecutionContext = factory.dispatcher
implicit val materializer: ActorMaterializer = ActorMaterializer()(factory)
```

### Conversions from Akka Stream to FS2 

**Overview**:

|From                        |With         |To                 |
|----------------------------|-------------|-------------------|
|`Graph[SourceShape[O], M]`  |`toStream()` |`Stream[Task, O]`  |
|`Graph[SinkShape[I], M]`    |`toSink()`   |`Sink[Task, I]`    |
|`Graph[FlowShape[I, O], M]` |`toPipe()`   |`Pipe[Task, I, O]` |

**Examples** ([source code](https://github.com/krasserm/streamz/blob/master/streamz-examples/src/main/scala/streamz/examples/akka/ConverterExample.scala)):

```scala
import akka.stream.scaladsl.{ Flow => AkkaFlow, Sink => AkkaSink, Source => AkkaSource }
import akka.{ Done, NotUsed }

import fs2.{ Pipe, Sink, Stream, Task }

import scala.collection.immutable.Seq
import scala.concurrent.Future

val numbers: Seq[Int] = 1 to 10
def f(i: Int) = List(s"$i-1", s"$i-2")

val aSink1: AkkaSink[Int, Future[Done]] = AkkaSink.foreach[Int](println)
val fSink1: Sink[Task, Int] = aSink1.toSink()

val aSource1: AkkaSource[Int, NotUsed] = AkkaSource(numbers)
val fStream1: Stream[Task, Int] = aSource1.toStream()

val aFlow1: AkkaFlow[Int, String, NotUsed] = AkkaFlow[Int].mapConcat(f)
val fPipe1: Pipe[Task, Int, String] = aFlow1.toPipe()

fStream1.to(fSink1).run.unsafeRun() // prints numbers
assert(fStream1.runLog.unsafeRun() == numbers)
assert(fStream1.through(fPipe1).runLog.unsafeRun() == numbers.flatMap(f))
```

`aSink1`, `aSource1` and `aFlow1` are materialized when the `Task`s of the FS2 streams that compose `fSink1`, `fStream1` and `fPipe1` are run. Their materialized value can be obtained via the `onMaterialization` callback that is a parameter of `toStream(onMaterialization: M => Unit)`, `toSink(onMaterialization: M => Unit)` and `toPipe(onMaterialization: M => Unit)` (not shown in the examples). 

### Conversions from FS2 to Akka Stream 

**Overview**:

|From               |With         |To                                  |
|-------------------|-------------|------------------------------------|
|`Stream[F[_], O]`  |`toSource()` |`Graph[SourceShape[O], NotUsed]`    |
|`Sink[F[_], I]`    |`toSink()`   |`Graph[SinkShape[I], Future[Done]]` |
|`Pipe[F[_], I, O]` |`toFlow()`   |`Graph[FlowShape[I, O], NotUsed]`   |

**Examples** ([source code](https://github.com/krasserm/streamz/blob/master/streamz-examples/src/main/scala/streamz/examples/akka/ConverterExample.scala)):

```scala
import akka.stream.scaladsl.{ Flow => AkkaFlow, Sink => AkkaSink, Source => AkkaSource, Keep }
import akka.{ Done, NotUsed }

import fs2.{ Pipe, Pure, Sink, Stream, pipe }

import scala.collection.immutable.Seq
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

val numbers: Seq[Int] = 1 to 10
def g(i: Int) = i + 10

val fSink2: Sink[Pure, Int] = s => pipe.lift(g)(s).map(println)
val aSink2: AkkaSink[Int, Future[Done]] = AkkaSink.fromGraph(fSink2.toSink)

val fStream2: Stream[Pure, Int] = Stream.emits(numbers)
val aSource2: AkkaSource[Int, NotUsed] = AkkaSource.fromGraph(fStream2.toSource)

val fpipe2: Pipe[Pure, Int, Int] = pipe.lift[Pure, Int, Int](g)
val aFlow2: AkkaFlow[Int, Int, NotUsed] = AkkaFlow.fromGraph(fpipe2.toFlow)

aSource2.toMat(aSink2)(Keep.right).run() // prints numbers
assert(Await.result(aSource2.toMat(AkkaSink.seq)(Keep.right).run(), 5.seconds) == numbers)
assert(Await.result(aSource2.via(aFlow2).toMat(AkkaSink.seq)(Keep.right).run(), 5.seconds) == numbers.map(g))
```

`fSink2`, `fStream2` and `fPipe2` are run when the Akka Streams that compose `aSink2`, `aSource2` and `aFlow2` are materialized.

### Backpressure, cancellation, completion and errors

Downstream demand and cancellation as well as upstream completion and error signals are properly mediated between Akka Stream and FS2 (see also [ConverterSpec](https://github.com/krasserm/streamz/blob/master/streamz-akka/src/test/scala/streamz/akka/ConverterSpec.scala)).  

Apache Camel integration
------------------------
 
Apache Camel endpoints can be integrated into FS2 streams and Akka Streams with a Camel DSL that is provided by the `streamz-camel` artifact. The Camel DSL requires an implicit [`StreamContext`](http://krasserm.github.io/streamz/scala-2.12/unidoc/streamz/camel/StreamContext.html) in scope. A `StreamContext` uses a `CamelContext` to manage the endpoints 
referenced by FS2 streams and Akka Streams. A `StreamContext` with an internally managed `CamelContext` can be created with `StreamContext()`:

```scala
import streamz.camel.StreamContext

// contains an internally managed CamelContext 
implicit val streamContext: StreamContext = StreamContext()
```

Applications that want to re-use an existing, externally managed `CamelContext` should create a `StreamContext` with  `StreamContext(camelContext: CamelContext)`: 

```scala
import org.apache.camel.CamelContext
import streamz.camel.StreamContext

// an externally managed CamelContext
val camelContext: CamelContext = ...

// re-uses the externally managed CamelContext
implicit val streamContext: StreamContext = StreamContext(camelContext)
```

After usage, a `StreamContext` should be stopped with `streamContext.stop()`. 

The following two subsections introduce the Camel DSL; a more realistic usage example is given in subsection [Example application](#example-application). The full source code presented in these subsections is available [here](https://github.com/krasserm/streamz/blob/master/streamz-examples/src/main/scala/streamz/examples/camel).


<a name="dsl-for-fs2">
### Camel DSL for FS2

The Camel DSL for FS2 can be imported with:

```scala
import streamz.camel.fs2dsl._
```

#### Consuming from an endpoint

An FS2 stream that emits messages consumed from a Camel endpoint can be created with `receive`. Endpoints are referenced by their [endpoint URI](http://camel.apache.org/uris.html). For example,

```scala
import streamz.camel.StreamMessage

val s1: Stream[Task, StreamMessage[String]] = receive[String]("seda:q1")
```

creates an FS2 stream that consumes messages from the [SEDA endpoint](http://camel.apache.org/seda.html) `seda:q1` and converts them to `StreamMessage[String]`s. A [`StreamMessage[A]`](http://krasserm.github.io/streamz/scala-2.12/unidoc/streamz/camel/StreamMessage.html) contains a message `body` of type `A` and message `headers`. Calling `receive` with a `String` type parameter creates an FS2 stream that converts consumed message bodies to type `String` before emitting them as `StreamMessage[String]`. Type conversion internally uses a Camel [type converter](http://camel.apache.org/type-converter.html). An FS2 stream that only emits the converted message bodies can be created with `receiveBody`:

```scala
val s1b: Stream[Task, String] = receiveBody[String]("seda:q1")
```

This is equivalent to `receive[String]("seda:q1").map(_.body)`.

#### Sending to an endpoint

For sending a `StreamMessage` to a Camel endpoint, the `send` combinator should be used:

```scala
val s2: Stream[Task, StreamMessage[String]] = s1.send("seda:q2")
```

This initiates an in-only message [exchange](http://camel.apache.org/exchange.html) with an endpoint and continues the stream with the sent `StreamMessage`. The `send` combinator is also available for streams of message bodies:

```scala
val s2b: Stream[Task, String] = s1b.send("seda:q2")
```

#### Requesting from an endpoint

For requesting a reply from an endpoint to an input `StreamMessage`, the `request` combinator should be used:

```scala
val s3: Stream[Task, StreamMessage[Int]] = s2.request[Int]("bean:service?method=weight")
```

This initiates an in-out message exchange with the endpoint and continues the stream with the output `StreamMessage`. Here, a [Bean endpoint](https://camel.apache.org/bean.html) is used to call the `weight(String): Int` method on an object that is registered in the `CamelContext` under the name `service`. The input message body is used as `weight` call argument, the output message body is assigned the return value. The `receive` type parameter (`Int`) specifies the expected output value type. The output message body can also be converted to another type provided that an appropriate Camel type converter is available (`Double`, for example). The `request` combinator is also available for streams of message bodies:

```scala
val s3b: Stream[Task, Int] = s2b.request[Int]("bean:service?method=weight")
```

<a name="dsl-for-as">
### Camel DSL for Akka Streams

The Camel DSL for Akka Streams can be imported with:

```scala
import streamz.camel.akkadsl._
```

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
val s2: Source[StreamMessage[String], NotUsed] = s1.send("seda:q2")
```

This initiates an in-only message [exchange](http://camel.apache.org/exchange.html) with an endpoint and continues the stream with the sent `StreamMessage`. 

The `send` combinator is not only available for sources of type `Source[StreamMessage[A], M]` but more generally for any source of type `Source[A, M]`:

```scala
val s2b: Source[String, NotUsed] = s1b.send("seda:q2")
```

If `A` is not a `StreamMessage`, `send` automatically wraps the message into a `StreamMessage[A]` before sending it to the endpoint and continues the stream with the unwrapped `A`. The `send` combinator is also available for flows of type `Flow[A, B, M]`, `SubFlow[A, M, Source[A, M]#Repr, Source[A, M]#Closed]` and `SubFlow[B, M, Flow[A, B, M]#Repr, Flow[A, B, M]#Closed]`. Instead of using the implicit `send` combinator, applications can also use the `akkadsl.send` graph stage 

```scala
package object akkadsl {
  // ...

  def send[I](uri: String, parallelism: Int)(implicit context: StreamContext): 
    Graph[FlowShape[StreamMessage[I], StreamMessage[I]], NotUsed]

  // ...
}
```

together with the Akka Streams `via` combinator:


```scala
val s2 = s1.via(send("seda:q2", parallelism = 3))
```

The `parallelism` parameter determines how many `send`s can be executed in parallel. It is required for the `akkadsl.send` graph stage but optional for the `send` comninator (and has a default value of 1).

#### Requesting from an endpoint

For requesting a reply from an endpoint to an input `StreamMessage`, the `request` combinator should be used:

```scala
val s3: Source[StreamMessage[Int], NotUsed] = s2.request[Int]("bean:service?method=weight")
```

This initiates an in-out message exchange with the endpoint and continues the stream with the output `StreamMessage`. Here, a [Bean endpoint](https://camel.apache.org/bean.html) is used to call the `weight(String): Int` method on an object that is registered in the `CamelContext` under the name `service`. The input message body is used as `weight` call argument, the output message body is assigned the return value. The `receive` type parameter `[Int]` specifies the expected output value type. The output message body can also be converted to another type provided that an appropriate Camel type converter is available, (`Double`, for example). 

The `request` combinator is not only available for sources of type `Source[StreamMessage[A], M]` but more generally for any source of type `Source[A, M]`:

```scala
val s3b: Source[Int, NotUsed] = s2b.request[Int]("bean:service?method=weight")
```

If `A` is not a `StreamMessage`, `request` automatically wraps the message into a `StreamMessage[A]` before sending it to the endpoint and continues the stream with the unwrapped message body `B` of the output `StreamMessage[B]`. The `request` combinator is also available for flows of type `Flow[A, B, M]`, `SubFlow[A, M, Source[A, M]#Repr, Source[A, M]#Closed]` and `SubFlow[B, M, Flow[A, B, M]#Repr, Flow[A, B, M]#Closed]`. Instead of using the implicit `request` combinator, applications can also use the `akkadsl.request` graph stage 

```scala
package object akkadsl {
  // ...

  def request[I, O](uri: String, parallelism: Int)(implicit context: StreamContext, tag: ClassTag[O]): 
    Graph[FlowShape[StreamMessage[I], StreamMessage[O]], NotUsed]

  // ...
}
```

together with the Akka Streams `via` combinator:

```scala
val s3 = s2.via(request[String, Int]("bean:service?method=weight", parallelism = 3))
```

The `parallelism` parameter determines how many `request`s can be executed in parallel. It is required for the `akkadsl.request` graph stage but optional for the `request` comninator (and has a default value of 1).

A Java version of the Camel DSL for Akka Streams is coming soon.

<a name="example-application">
### Example application

The example application consumes file content line by line, either from a TCP endpoint or a from file endpoint, and prints the consumed lines prefixed with a formatted line number to `stdout`:

![Streamz example](images/streamz-example.png)

- The TCP endpoint is implemented with the [Netty4 component](http://camel.apache.org/netty4.html). It listens on `localhost:5051` and is configured to use a text line codec (see `tcpEndpointUri` below) so that consumers receive a separate message for each line.
- The file endpoint is implemented with the [File component](http://camel.apache.org/file2.html). It scans the `input` directory for new files and serves them as `String`s to consumers. The consumed file content is split into lines in a separate *Line Split* step.
- Lines consumed from both endpoints are merged into a single stream in a *Merge* step.
- To generate line numbers for the consumed lines, a *Line Number Source* is configured to generate numbers 1, 2, ..., n. These numbers are then formatted to a line prefix using the `[$lineNumber] ` template. The line number formatter is an object registered in the `CamelContext` under the name `exampleService` and accessed with a [Bean endpoint](https://camel.apache.org/bean.html) configured to call the `linePrefix` method.
- The line prefixes are then concatenated with the actual lines in a *ZipWith* step.
- Finally, the concatenation results are sent to `stream:out`, a [Stream endpoint](http://camel.apache.org/stream.html) that writes messages to `stdout`.

In the following two subsections, the implementations for both, FS2 and Akka Streams, are shown which closely match the above diagram. Both implementations share the definitions of `ExampleService`, `StreamContext` and endpoint URIs:

```scala
class ExampleService {
  def linePrefix(lineNumber: Int): String = s"[$lineNumber] "
}

trait ExampleContext {
  import org.apache.camel.impl.{ DefaultCamelContext, SimpleRegistry }
  import streamz.camel.StreamContext

  private val camelRegistry = new SimpleRegistry
  private val camelContext = new DefaultCamelContext

  camelContext.start()
  camelContext.setRegistry(camelRegistry)
  camelRegistry.put("exampleService", new ExampleService)

  implicit val context: StreamContext =
    StreamContext(camelContext)

  val tcpEndpointUri: String =
    "netty4:tcp://localhost:5150?sync=false&textline=true&encoding=utf-8"

  val fileEndpointUri: String =
    "file:input?charset=utf-8"

  val serviceEndpointUri: String =
    "bean:exampleService?method=linePrefix"

  val printerEndpointUri: String =
    "stream:out"
}
```

#### Implementation for FS2

```scala
object CamelFs2Example extends ExampleContext with App {
  import fs2._

  // import Camel DSL for FS2
  import streamz.camel.fs2dsl._

  implicit val strategy: Strategy =
    Strategy.fromExecutionContext(scala.concurrent.ExecutionContext.global)

  val tcpLineStream: Stream[Task, String] =
    receiveBody[String](tcpEndpointUri)

  val fileLineStream: Stream[Task, String] =
    receiveBody[String](fileEndpointUri).through(text.lines)

  val linePrefixStream: Stream[Task, String] =
    Stream.iterate(1)(_ + 1).request[String](serviceEndpointUri)

  val stream: Stream[Task, String] =
    tcpLineStream
      .merge(fileLineStream)
      .zipWith(linePrefixStream)((l, n) => n concat l)
      .send(printerEndpointUri)

  stream.run.unsafeRun
}
```

#### Implementation for Akka Streams

```scala
object CamelAkkaExample extends ExampleContext with App {
  import akka.NotUsed
  import akka.actor.ActorSystem
  import akka.stream.ActorMaterializer
  import akka.stream.scaladsl.{ Sink, Source }
  import scala.collection.immutable.Iterable

  // import Camel DSL for Akka Streams
  import streamz.camel.akkadsl._

  implicit val system = ActorSystem("example")
  implicit val materializer = ActorMaterializer()

  val tcpLineSource: Source[String, NotUsed] =
    receiveBody[String](tcpEndpointUri)

  val fileLineSource: Source[String, NotUsed] =
    receiveBody[String](fileEndpointUri).mapConcat(_.lines.to[Iterable])

  val linePrefixSource: Source[String, NotUsed] =
    Source.fromIterator(() => Iterator.from(1)).request[String](serviceEndpointUri)

  val stream: Source[String, NotUsed] =
    tcpLineSource
      .merge(fileLineSource)
      .zipWith(linePrefixSource)((l, n) => n concat l)
      .send(printerEndpointUri)

  stream.runWith(Sink.ignore)
}
```

#### Example application usage

Depending on the implementation, the example application can be started with

```
$ sbt 'examples/runMain streamz.examples.camel.CamelFs2Example'
```

or 

```
$ sbt 'examples/runMain streamz.examples.camel.CamelAkkaExample'
```

Before submitting data to the application, let’s create an input file with two lines:

```
$ cat >> example.txt
hello
streamz
^D
```

Copy the generated file to the `input` directory so that it can be consumed by the file endpoint:

```
$ cp example.txt input/
```

You should see the following stream output:

```
[1] hello
[2] streamz
```

Then send the file content to the TCP endpoint (with `nc` on Mac OS X or `netcat` on Linux):

```
$ cat example.txt | nc localhost 5150
```

You should see the following stream output:

```
[3] hello
[4] streamz
```
