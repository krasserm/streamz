/*
 * Copyright 2014 - 2020 the original author or authors.
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

import cats.effect.IO
import fs2.{ Stream, text }
import streamz.camel.fs2.dsl._
import streamz.examples.camel.ExampleContext
import cats.effect.{ ExitCode, IOApp }

object Example extends IOApp with ExampleContext {

  def run(args: List[String]) = {

    val tcpLineStream: Stream[IO, String] =
      receiveBody[IO, String](tcpEndpointUri)

    val fileLineStream: Stream[IO, String] =
      receiveBody[IO, String](fileEndpointUri).through(text.lines)

    val linePrefixStream: Stream[IO, String] =
      Stream.iterate(1)(_ + 1).sendRequest[IO, String](serviceEndpointUri)

    val stream: Stream[IO, String] =
      tcpLineStream
        .merge(fileLineStream)
        .zipWith(linePrefixStream)((l, n) => n concat l)
        .send(printerEndpointUri)

    stream.compile.drain.as(ExitCode.Success)
  }
}
