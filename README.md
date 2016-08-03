Streamz
=======

Streamz is a resource combinator library for [fs2](https://github.com/functional-streams-for-scala/fs2). It allows [`Stream`](https://oss.sonatype.org/service/local/repositories/releases/archive/co/fs2/fs2-core_2.12.0-M5/0.9.0-RC1/fs2-core_2.12.0-M5-0.9.0-RC1-javadoc.jar/!/fs2/Stream.html) instances to consume from and produce to

- [Apache Camel](http://camel.apache.org/) endpoints
- [Akka Persistence](http://doc.akka.io/docs/akka/2.4/scala/persistence.html) journals and snapshot stores and
- [Akka Stream](http://doc.akka.io/docs/akka/2.4/scala/stream/index.html) flows (reactive streams) with full back-pressure support.

Dependencies
------------

    resolvers += "scalaz at bintray" at "http://dl.bintray.com/scalaz/releases"

    resolvers += "krasserm at bintray" at "http://dl.bintray.com/krasserm/maven"

    // transitively depends on akka-camel 2.4.6
    libraryDependencies += "com.github.krasserm" %% "streamz-akka-camel" % "0.3.2"

    // transitively depends on akka-persistence-experimental 2.4.6
    libraryDependencies += "com.github.krasserm" %% "streamz-akka-persistence" % "0.3.2"

    // transitively depends on akka-stream-experimental 2.4.6
    libraryDependencies += "com.github.krasserm" %% "streamz-akka-stream" % "0.3.2"

Combinators for Apache Camel
----------------------------

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
  // in-only message exchange with endpoint and continue stream with out-message
  .request[Int]("bean:service?method=length")
  // in-only message exchange with endpoint
  .send("seda:q2")

  // create concurrent task from stream
  val t: Task[Unit] = s.run

  // run task (side effects only here) ...
  t.unsafeRun
```

An implicit ``ActorSystem`` must be in scope  because the combinator implementation depends on [Akka Camel](http://doc.akka.io/docs/akka/2.3.11/scala/camel.html). A discrete stream starting from a Camel endpoint can be created with ``receive`` where its type parameter is used to convert received message bodies to given type using a Camel type converter, if needed:

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
   
These combinators are compatible with all available [Camel endpoints](http://camel.apache.org/components.html) and all [`Process`](http://docs.typelevel.org/api/scalaz-stream/stable/latest/doc/#scalaz.stream.Process) combinators (and now supersede the [scalaz-camel](https://github.com/krasserm/scalaz-camel) project). A more concrete example how to process files from an FTP server is available [here](https://github.com/krasserm/streamz/blob/master/streamz-akka-camel/src/test/scala/streamz/example/FtpExample.scala).

Combinators for Akka Persistence
--------------------------------

A discrete stream of persistent events (that are written by an ``akka.persistence.PersistentActor``) can be created with ``replay``: 

```scala
import akka.actor.ActorSystem
import fs2.Stream
import fs2.Task

import streamz.akka.persistence._

implicit val system = ActorSystem("example")

// replay "processor-1" events starting from scratch (= sequence number 1)
val s1: Stream[Task, Event[Any]] = replay("processor-1")

// replay "processor-1" events starting from sequence number 3
val s2: Stream[Task, Event[Any]] = replay("processor-1", from = 3L)
```

where `Event` is defined as
 
```scala
package streamz.akka.persistence

case class Event[A](persistenceId: String, sequenceNr: Long, data: A)
```

The created ``Stream[Task,  Event[Any]]`` does not only replay already journaled events but also emits new events that ``processor-1`` is going to write. Assuming journaled events  ``Event("processor-1", 1L, "a")``, ``Event("processor-1", 2L, "b")``, ``Event("processor-1", 3L, "c")``, ``Event("processor-1", 4L, "d")``
 
- ``s1`` produces ``Event("processor-1", 1L, "a")``, ``Event("processor-1", 2L, "b")``, ``Event("processor-1", 3L, "c")``, ``Event("processor-1", 4L, "d")``, ... and 
- ``s2`` produces ``Event("processor-1", 3L, "c")``, ``Event("processor-1", 4L, "d")``, ... 

State can be accumulated with with ``Stream``'s ``scan``

```scala
val s3: Stream[Task, String] = p1.scan("")((acc, evt) => acc + evt.data)
```

so that ``s3`` produces ``""``, ``"a"``, ``"ab"``, ``"abc"``, ``"abcd"``, ... . State accumulation starting from a snapshot can be done with ``snapshot``:
 
```scala
val s4: Stream[Task, String] = for {
  s @ Snapshot(md, data) <- snapshot[String]("processor-1")
  currentState <- replay(md.persistenceId, s.nextSequenceNr).scan(data)((acc, evt) => acc + evt.data)
} yield currentState
```

Additionally assuming that a snapshot ``"ab"`` has already been written by ``processor-1`` (after events ``Event("processor-1", 1L, "a")`` and ``Event("processor-1", 2L, "b")``), ``p4`` produces ``"ab"``, ``"abc"``, ``"abcd"``, ... . Finally, writing to an Akka Persistence journal from a stream can done with ``journal``:

```scala
val s5: Stream[Task, Unit] = Stream("a", "b", "c").journal("processor-2")
```

Combinators for Akka Stream
---------------------------

The following examples use these imports and definitions (full source code [here](https://github.com/krasserm/streamz/blob/master/streamz-akka-stream/src/test/scala/streamz/akka/stream/example/AkkaStreamExample.scala)):

```scala
import akka.actor.ActorSystem
import akka.stream.scaladsl._
import akka.stream.ActorMaterializer

import scala.concurrent.ExecutionContext
import fs2.Stream
import fs2.Task

implicit val system = ActorSystem("example")
implicit val executionContext: ExecutionContext = system.dispatcher
implicit val materializer = ActorMaterializer()
```

### `Stream` publishes to managed flow

A `Stream` can publish its values to an internally created (i.e. *managed*) `Source`.
This flow can be customized by providing a function that turns it into a `RunnableGraph`.
To get to the materialized result of the flow another function taking this result
as input can optionally be provided.

```scala
// Create stream
val p1: Stream[Task, Int] = Stream.emits(1 to 20)
// Compose stream with (managed) flow
val sink = Sink.foreach(println)
val s2: Stream[Task, Unit] = s1.publish()
  // Customize source (done when running stream)
  { source => source.toMat(sink)(Keep.right) }
  // get matrialized result (here used for cleanup)
  { materialized => materialized.onComplete(_ => system.terminate()) }

// Run stream
s2.run.unsafeRun
```

### `Stream` publishes to un-managed flow

Instead of using a managed flow `publisher` creates a plain `Publisher` than can
be used for creating custom (*un-managed*) flows.

```scala
// Create stream
val s1: Stream[Task, Int] = Stream.emits(1 to 20)
// Create publisher (= stream adapter)
val (s2, publisher) = s1.publisher()
// Create (un-managed) flow from publisher
private val sink = Sink.foreach(println)
val m = Source.fromPublisher(publisher).runWith(sink)
// Run stream
p2.run.unsafeRun
// use materialized result for cleanup
m.onComplete(_ => system.terminate())
```

### `Stream` subscribes to flow

A `Source` can publish its values to a `Stream` by using `toStream`

```scala
// Create flow
val f1: Source[Int, akka.NotUsed] = Source(1 to 20)
// Create stream that subscribes to the flow
val s1: Stream[Task, Int] = f1.toStream()
// Run stream
s1.map(println).run.unsafeRun
// When p1 is done, f1 must be done as well
system.terminate()
```
