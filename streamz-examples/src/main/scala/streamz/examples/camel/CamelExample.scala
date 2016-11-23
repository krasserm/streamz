/*
 * Copyright 2014 - 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package streamz.examples.camel

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
