Streamz
=======

[![Gitter](https://badges.gitter.im/krasserm/streamz.svg)](https://gitter.im/krasserm/streamz?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge)

Streamz is a resource combinator library for [FS2](https://github.com/functional-streams-for-scala/fs2). It supports 

- conversion of [Akka Stream](http://doc.akka.io/docs/akka/2.4/scala/stream/index.html) `Source`s, `Flow`s and `Sink`s to and from FS2 `Stream`s, `Pipe`s and `Sink`s, respectively.
- usage of [Apache Camel](http://camel.apache.org/) endpoints in FS2 `Stream`s.

Dependencies
------------

Streamz artifacts are available for Scala 2.11 and 2.12:

    resolvers += "krasserm at bintray" at "http://dl.bintray.com/krasserm/maven"

    // transitively depends on akka-camel 2.4.13
    libraryDependencies += "com.github.krasserm" %% "streamz-akka-camel" % "0.5.1"

    // transitively depends on akka-stream 2.4.13
    libraryDependencies += "com.github.krasserm" %% "streamz-akka-stream" % "0.5.1"

Combinators for Akka Stream
---------------------------

These combinators support the conversion of [Akka Stream](http://doc.akka.io/docs/akka/2.4/scala/stream/index.html) `Source`s, `Flow`s and `Sink`s to and from FS2 `Stream`s, `Pipe`s and `Sink`s, respectively. They can be imported with 

```scala
import streamz.akka.stream._
```

and require the following implicit values:

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

**Examples** ([source code](https://github.com/krasserm/streamz/blob/master/streamz-akka-stream/src/test/scala/streamz/akka/stream/example/ConverterExample.scala)):

```scala
import akka.stream.scaladsl.{Flow => AkkaFlow, Sink => AkkaSink, Source => AkkaSource}
import akka.{Done, NotUsed}

import fs2.{Pipe, Sink, Stream, Task}

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

**Examples** ([source code](https://github.com/krasserm/streamz/blob/master/streamz-akka-stream/src/test/scala/streamz/akka/stream/example/ConverterExample.scala)):

```scala
import akka.stream.scaladsl.{Flow => AkkaFlow, Sink => AkkaSink, Source => AkkaSource, Keep}
import akka.{Done, NotUsed}

import fs2.{Pipe, Pure, Sink, Stream, pipe}

import scala.collection.immutable.Seq
import scala.concurrent.{Await, Future}
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

`fSink2`, `fStream2` and `fPipe2` are run when the Akka streams that compose `aSink2`, `aSource2` and `aFlow2` are materialized.

### Backpressure, cancellation, completion and errors

Downstream demand and cancellation as well as upstream completion and error signals are properly mediated between Akka Stream and FS2 (see also [ConverterSpec](https://github.com/krasserm/streamz/blob/master/streamz-akka-stream/src/test/scala/streamz/akka/stream/ConverterSpec.scala)).  

Combinators for Apache Camel
----------------------------

These combinators support the usage of [Apache Camel](http://camel.apache.org/) endpoints in FS2 [`Stream`](https://oss.sonatype.org/service/local/repositories/releases/archive/co/fs2/fs2-core_2.11/0.9.1/fs2-core_2.11-0.9.1-javadoc.jar/!/index.html#fs2.Stream)s.

```scala
import akka.actor.ActorSystem
import fs2.Task
import fs2.Stream

import streamz.akka.camel._

implicit val system = ActorSystem("example")

val s: Stream[Task,Unit] =
  // receive from endpoint
  receive[String]("seda:q1")
  // in-only message exchange with endpoint and continue stream with in-message
  .sendW("seda:q3")
  // in-out message exchange with endpoint and continue stream with out-message
  .request[Int]("bean:service?method=length")
  // in-only message exchange with endpoint
  .send("seda:q2")

  // create concurrent task from stream
  val t: Task[Unit] = s.run

  // run task (side effects only here) ...
  t.unsafeRun
```

An implicit ``ActorSystem`` must be in scope  because the combinator implementation depends on [Akka Camel](http://doc.akka.io/docs/akka/2.4/scala/camel.html). A discrete stream starting from a Camel endpoint can be created with ``receive`` where its type parameter is used to convert received message bodies to given type using a Camel type converter, if needed:

```scala
val s1: Stream[Task,String] = receive[String]("seda:q1")
```

Streams can also be targeted at Camel endpoints. The ``send`` and ``sendW`` combinators initiate in-only message exchanges with a Camel endpoint:

```scala
val s2: Stream[Task,Unit] = s1.send("seda:q2")
val s3: Stream[Task,String] = s1.sendW("seda:q3")
```
    
The ``request`` combinator initiates in-out message exchanges with a Camel endpoint where its type parameter is used to convert response message bodies to given type using a Camel type converter, if needed:

```scala
val s4: Stream[Task,Int] = s1.request[Int]("bean:service?method=length")
```
   
These combinators are compatible with all available [Camel endpoints](http://camel.apache.org/components.html) and all `Stream` combinators (and now supersede the [scalaz-camel](https://github.com/krasserm/scalaz-camel) project). A more concrete example how to process files from an FTP server is available [here](https://github.com/krasserm/streamz/blob/master/streamz-akka-camel/src/test/scala/streamz/example/FtpExample.scala).
