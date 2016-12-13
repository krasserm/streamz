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

package streamz.examples.camel.fs2

import fs2.{ Strategy, Stream, Task, text }

import streamz.camel.fs2.dsl._
import streamz.examples.camel.ExampleContext

object Example extends ExampleContext with App {
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
