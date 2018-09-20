Stream converters
-----------------

Stream converters convert Akka Stream `Source`s, `Flow`s and `Sink`s to FS2 `Stream`s, `Pipe`s and `Sink`s, respectively, and vice versa. They are provided by the 

    resolvers += "krasserm at bintray" at "http://dl.bintray.com/krasserm/maven"

    libraryDependencies += "com.github.krasserm" %% "streamz-converter" % "0.9.1"

artifact and can be imported with: 

```scala
import streamz.converter._
```

They require the following `implicit`s in scope:

```scala
import akka.actor.ActorRefFactory
import akka.stream.ActorMaterializer

import scala.concurrent.ExecutionContext

val factory: ActorRefFactory = ???

implicit val executionContext: ExecutionContext = factory.dispatcher
implicit val materializer: ActorMaterializer = ActorMaterializer()(factory)
```

### Conversions from Akka Stream to FS2 

**Overview**:

|From                        |With         |To                 |
|----------------------------|-------------|-------------------|
|`Graph[SourceShape[A], M]`  |`toStream()` |`Stream[IO, A]`    |
|`Graph[SinkShape[A], M]`    |`toSink()`   |`Sink[IO, A]`      |
|`Graph[FlowShape[A, B], M]` |`toPipe()`   |`Pipe[IO, A, B]`   |

**Examples** ([source code](https://github.com/krasserm/streamz/blob/master/streamz-examples/src/main/scala/streamz/examples/converter/Example.scala)):

```scala
import akka.stream.scaladsl.{ Flow => AkkaFlow, Sink => AkkaSink, Source => AkkaSource }
import akka.{ Done, NotUsed }

import cats.effect.IO
import fs2.{ Pipe, Sink, Stream }

import scala.collection.immutable.Seq
import scala.concurrent.Future

val numbers: Seq[Int] = 1 to 10
def f(i: Int) = List(s"$i-1", s"$i-2")

val aSink1: AkkaSink[Int, Future[Done]] = AkkaSink.foreach[Int](println)
val fSink1: Sink[IO, Int] = aSink1.toSink[IO]()

val aSource1: AkkaSource[Int, NotUsed] = AkkaSource(numbers)
val fStream1: Stream[IO, Int] = aSource1.toStream[IO]()

val aFlow1: AkkaFlow[Int, String, NotUsed] = AkkaFlow[Int].mapConcat(f)
val fPipe1: Pipe[IO, Int, String] = aFlow1.toPipe[IO]()

fStream1.to(fSink1).compile.drain.unsafeRunSync() // prints numbers
assert(fStream1.compile.toVector.unsafeRunSync() == numbers)
assert(fStream1.through(fPipe1).compile.toVector.unsafeRunSync() == numbers.flatMap(f))
```

`aSink1`, `aSource1` and `aFlow1` are materialized when the `IO`s of the FS2 streams that compose `fSink1`, `fStream1` and `fPipe1` are run. Their materialized value can be obtained via the `onMaterialization` callback that is a parameter of `toStream(onMaterialization: M => Unit)`, `toSink(onMaterialization: M => Unit)` and `toPipe(onMaterialization: M => Unit)` (not shown in the examples). 

### Conversions from FS2 to Akka Stream 

**Overview**:

|From               |With         |To                                  |
|-------------------|-------------|------------------------------------|
|`Stream[F[_], A]`  |`toSource()` |`Graph[SourceShape[A], NotUsed]`    |
|`Sink[F[_], A]`    |`toSink()`   |`Graph[SinkShape[A], Future[Done]]` |
|`Pipe[F[_], A, B]` |`toFlow()`   |`Graph[FlowShape[A, B], NotUsed]`   |

**Examples** ([source code](https://github.com/krasserm/streamz/blob/master/streamz-examples/src/main/scala/streamz/examples/converter/Example.scala)):

```scala
import akka.stream.scaladsl.{ Flow => AkkaFlow, Sink => AkkaSink, Source => AkkaSource, Keep }
import akka.{ Done, NotUsed }

import fs2.{ Pipe, Pure, Sink, Stream }

import scala.collection.immutable.Seq
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

val numbers: Seq[Int] = 1 to 10
def g(i: Int) = i + 10

val fSink2: Sink[Pure, Int] = s => s.map(g).map(println)
val aSink2: AkkaSink[Int, Future[Done]] = AkkaSink.fromGraph(fSink2.toSink)

val fStream2: Stream[Pure, Int] = Stream.emits(numbers)
val aSource2: AkkaSource[Int, NotUsed] = AkkaSource.fromGraph(fStream2.toSource)

val fpipe2: Pipe[Pure, Int, Int] = s => s.map(g)
val aFlow2: AkkaFlow[Int, Int, NotUsed] = AkkaFlow.fromGraph(fpipe2.toFlow)

aSource2.toMat(aSink2)(Keep.right).run() // prints numbers
assert(Await.result(aSource2.toMat(AkkaSink.seq)(Keep.right).run(), 5.seconds) == numbers)
assert(Await.result(aSource2.via(aFlow2).toMat(AkkaSink.seq)(Keep.right).run(), 5.seconds) == numbers.map(g))
```

`fSink2`, `fStream2` and `fPipe2` are run when the Akka Streams that compose `aSink2`, `aSource2` and `aFlow2` are materialized.

### Backpressure, cancellation, completion and errors

Downstream demand and cancellation as well as upstream completion and error signals are properly mediated between Akka Stream and FS2 (see also [ConverterSpec](https://github.com/krasserm/streamz/blob/master/streamz-converter/src/test/scala/streamz/converter/ConverterSpec.scala)).  

