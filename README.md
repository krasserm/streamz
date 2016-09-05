Streamz
=======

Streamz is a resource combinator library for [fs2](https://github.com/functional-streams-for-scala/fs2). It allows [`Stream`](https://oss.sonatype.org/service/local/repositories/releases/archive/co/fs2/fs2-core_2.11/0.9.0-RC2/fs2-core_2.11-0.9.0-RC2-javadoc.jar/!/index.html#fs2.Stream) instances to consume from and produce to

- [Apache Camel](http://camel.apache.org/) endpoints
- [Akka Stream](http://doc.akka.io/docs/akka/2.4/scala/stream/index.html) sources and sinks with full back-pressure support.

Dependencies
------------

    resolvers += "krasserm at bintray" at "http://dl.bintray.com/krasserm/maven"

    // transitively depends on akka-camel 2.4.9
    libraryDependencies += "com.github.krasserm" %% "streamz-akka-camel" % "0.4"

    // transitively depends on akka-stream 2.4.9
    libraryDependencies += "com.github.krasserm" %% "streamz-akka-stream" % "0.4"

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
   
These combinators are compatible with all available [Camel endpoints](http://camel.apache.org/components.html) and all [`Process`](http://docs.typelevel.org/api/scalaz-stream/stable/latest/doc/#scalaz.stream.Process) combinators (and now supersede the [scalaz-camel](https://github.com/krasserm/scalaz-camel) project). A more concrete example how to process files from an FTP server is available [here](https://github.com/krasserm/streamz/blob/master/streamz-akka-camel/src/test/scala/streamz/example/FtpExample.scala).

Combinators for Akka Stream
---------------------------

**These combinators are highly experimental at the moment. Expect major changes here.**

The following examples use these imports and definitions (full source code [here](https://github.com/krasserm/streamz/blob/master/streamz-akka-stream/src/test/scala/streamz/akka/stream/example/AkkaStreamExample.scala)):

```scala
import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._

import fs2.Stream
import fs2.Task

import scala.concurrent.ExecutionContext

import streamz.akka.stream._

implicit val system = ActorSystem("example")
implicit val executionContext: ExecutionContext = system.dispatcher
implicit val materializer = ActorMaterializer()
```

### `Stream` publishes to sink

A `Stream` can publish to a `Sink`. The sink is materialized when the 
stream is run. 

```scala
// Create stream
val s1: Stream[Task, Int] = Stream.emits(1 to 20)
// Compose stream with managed sink
val s2: Stream[Task, Unit] = s1.publish()
  // Managed sink
  { Sink.foreach(println) }
  // Get materialized value from sink (here used for cleanup)
  { materialized => materialized.onComplete(_ => system.terminate()) }

// Run stream
s2.run.unsafeRun
```

### `Stream` subscribes to source

A `Stream` can subscribe to a `Source`. The source is materialized when the 
stream is run.

```scala
// Create source
val f1: Source[Int, NotUsed] = Source(1 to 20)
// Create stream that subscribes to the source
val s1: Stream[Task, Int] = f1.subscribe()
// Run stream
s1.map(println).run.unsafeRun
// When s1 is done, f1 must be done as well
system.terminate()
```

### `Stream` subscribes to source and publishes to sink

```scala
val f1: Source[Int, akka.NotUsed] = Source(1 to 20)
val s1: Stream[Task, Int] = f1.subscribe()
val s2: Stream[Task, Unit] = s1.publish() {
Flow[Int].map(println).to(Sink.onComplete(_ => system.terminate()))
}()
s2.run.unsafeRun
```
