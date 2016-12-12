### Example application

The example application consumes file content line by line, either from a TCP endpoint or a from file endpoint, and prints the consumed lines prefixed with a formatted line number to `stdout`:

![Streamz example](../images/streamz-example.png)

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
  import streamz.camel.fs2.dsl._

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
  import streamz.camel.akka.scaladsl._

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
