Streamz
=======

Streamz is a resource combinator library for [scalaz-stream](https://github.com/scalaz/scalaz-stream). It allows [`Process`](http://docs.typelevel.org/api/scalaz-stream/stable/latest/doc/#scalaz.stream.Process) instances to consume from and produce to

- [Apache Camel](http://camel.apache.org/) endpoints
- [Akka Persistence](http://doc.akka.io/docs/akka/2.3.11/scala/persistence.html) journals and snapshot stores and
- [Akka Stream](http://akka.io/docs/#akka-streams-and-http) flows (reactive streams) with full back-pressure support.

Dependencies
------------

    resolvers += "scalaz at bintray" at "http://dl.bintray.com/scalaz/releases"

    resolvers += "krasserm at bintray" at "http://dl.bintray.com/krasserm/maven"

    // transitively depends on akka-camel 2.3.13
    libraryDependencies += "com.github.krasserm" %% "streamz-akka-camel" % "0.3.1"

    // transitively depends on akka-persistence-experimental 2.3.13
    libraryDependencies += "com.github.krasserm" %% "streamz-akka-persistence" % "0.3.1"

    // transitively depends on akka-stream-experimental 1.0
    libraryDependencies += "com.github.krasserm" %% "streamz-akka-stream" % "0.3.1"

Combinators for Apache Camel
----------------------------

```scala
import akka.actor.ActorSystem
import scalaz.concurrent.Task
import scalaz.stream.Process

import streamz.akka.camel._

implicit val system = ActorSystem("example")

val p: Process[Task,Unit] =
  // receive from endpoint
  receive[String]("seda:q1")
  // in-only message exchange with endpoint and continue stream with in-message
  .sendW("seda:q3")
  // in-only message exchange with endpoint and continue stream with out-message
  .request[Int]("bean:service?method=length")
  // in-only message exchange with endpoint
  .send("seda:q2")

  // create concurrent task from process
  val t: Task[Unit] = p.run

  // run task (side effects only here) ...
  t.run
```

An implicit ``ActorSystem`` must be in scope  because the combinator implementation depends on [Akka Camel](http://doc.akka.io/docs/akka/2.3.11/scala/camel.html). A discrete stream starting from a Camel endpoint can be created with ``receive`` where its type parameter is used to convert received message bodies to given type using a Camel type converter, if needed:

```scala
val p1: Process[Task,String] = receive[String]("seda:q1")
```

Streams can also be targeted at Camel endpoints. The ``send`` and ``sendW`` combinators initiate in-only message exchanges with a Camel endpoint:

```scala
val p2: Process[Task,Unit] = p1.send("seda:q2")
val p3: Process[Task,String] = p1.sendW("seda:q3")
```
    
The ``request`` combinator initiates in-out message exchanges with a Camel endpoint where its type parameter is used to convert response message bodies to given type using a Camel type converter, if needed:

```scala
val p4: Process[Task,Int] = p1.request[Int]("bean:service?method=length")
```
   
These combinators are compatible with all available [Camel endpoints](http://camel.apache.org/components.html) and all [`Process`](http://docs.typelevel.org/api/scalaz-stream/stable/latest/doc/#scalaz.stream.Process) combinators (and now supersede the [scalaz-camel](https://github.com/krasserm/scalaz-camel) project). A more concrete example how to process files from an FTP server is available [here](https://github.com/krasserm/streamz/blob/master/streamz-akka-camel/src/test/scala/streamz/example/FtpExample.scala).

Combinators for Akka Persistence
--------------------------------

A discrete stream of persistent events (that are written by an ``akka.persistence.PersistentActor``) can be created with ``replay``: 

```scala
import akka.actor.ActorSystem
import scalaz.concurrent.Task
import scalaz.std.string._
import scalaz.stream.Process

import streamz.akka.persistence._

implicit val system = ActorSystem("example")

// replay "processor-1" events starting from scratch (= sequence number 1)
val p1: Process[Task, Event[Any]] = replay("processor-1")

// replay "processor-1" events starting from sequence number 3
val p2: Process[Task, Event[Any]] = replay("processor-1", from = 3L)
```

where `Event` is defined as
 
```scala
package streamz.akka.persistence

case class Event[A](persistenceId: String, sequenceNr: Long, data: A)
```

The created ``Process[Task,  Event[Any]]`` does not only replay already journaled events but also emits new events that ``processor-1`` is going to write. Assuming journaled events  ``Event("processor-1", 1L, "a")``, ``Event("processor-1", 2L, "b")``, ``Event("processor-1", 3L, "c")``, ``Event("processor-1", 4L, "d")``
 
- ``p1`` produces ``Event("processor-1", 1L, "a")``, ``Event("processor-1", 2L, "b")``, ``Event("processor-1", 3L, "c")``, ``Event("processor-1", 4L, "d")``, ... and 
- ``p2`` produces ``Event("processor-1", 3L, "c")``, ``Event("processor-1", 4L, "d")``, ... 

State can be accumulated with with scalaz-stream's ``scan``

```scala
val p3: Process[Task, String] = p1.scan("")((acc, evt) => acc + evt.data)
```

so that ``p3`` produces ``""``, ``"a"``, ``"ab"``, ``"abc"``, ``"abcd"``, ... . State accumulation starting from a snapshot can be done with ``snapshot``:
 
```scala
val p4: Process[Task, String] = for {
  s @ Snapshot(md, data) <- snapshot[String]("processor-1")
  currentState <- replay(md.persistenceId, s.nextSequenceNr).scan(data)((acc, evt) => acc + evt.data)
} yield currentState
```

Additionally assuming that a snapshot ``"ab"`` has already been written by ``processor-1`` (after events ``Event("processor-1", 1L, "a")`` and ``Event("processor-1", 2L, "b")``), ``p4`` produces ``"ab"``, ``"abc"``, ``"abcd"``, ... . Finally, writing to an Akka Persistence journal from a stream can done with ``journal``:

```scala
val p5: Process[Task, Unit] = Process("a", "b", "c").journal("processor-2")
```

Combinators for Akka Stream
---------------------------

The following examples use these imports and definitions (full source code [here](https://github.com/krasserm/streamz/blob/master/streamz-akka-stream/src/test/scala/streamz/akka/stream/example/AkkaStreamExample.scala)):

```scala
import akka.actor.ActorSystem
import akka.stream.scaladsl._
import akka.stream.ActorMaterializer

import scala.concurrent.ExecutionContext
import scalaz.concurrent.Task
import scalaz.stream._

import streamz.akka.stream._

implicit val system = ActorSystem("example")
implicit val executionContext: ExecutionContext = system.dispatcher
implicit val materializer = ActorMaterializer()
```

### `Process` publishes to managed flow

A `Process` can publish its values to an internally created (i.e. *managed*) `Source`.
This flow can be customized by providing a function that turns it into a `RunnableGraph`.
To get to the materialized result of the flow another function taking this result
as input can optionally be provided.

```scala
// Create process
val p1: Process[Task, Int] = Process.emitAll(1 to 20)
// Compose process with (managed) flow
val sink = Sink.foreach(println)
val p2: Process[Task, Unit] = p1.publish()
  // Customize flow (done when running process)
  { source => source.toMat(sink)(Keep.right) }
  // get matrialized result (here used for cleanup)
  { materialized => materialized.onComplete(_ => system.terminate()) }

// Run process
p2.run.run
```

### `Process` publishes to un-managed flow

Instead of using a managed flow `publisher` creates a plain `Publisher` than can
be used for creating custom (*un-managed*) flows.

```scala
// Create process
val p1: Process[Task, Int] = Process.emitAll(1 to 20)
// Create publisher (= process adapter)
val (p2, publisher) = p1.publisher()
// Create (un-managed) flow from publisher
private val sink = Sink.foreach(println)
val m = Source.fromPublisher(publisher).runWith(sink)
// Run process
p2.run.run
// use materialized result for cleanup
m.onComplete(_ => system.terminate())
```

### `Process` subscribes to flow

A `Source` can publish its values to a `Process` by using `toProcess`

```scala
// Create flow
val f1: Source[Int, Unit] = Source(1 to 20)
// Create process that subscribes to the flow
val p1: Process[Task, Int] = f1.toProcess()
// Run process
p1.map(println).run.run
// When p1 is done, f1 must be done as well
system.terminate()
```
