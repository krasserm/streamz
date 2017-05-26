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

package streamz.examples.camel.akka

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import streamz.camel.{ StreamContext, StreamMessage }
import streamz.camel.akka.scaladsl._

object Greeter extends App {
  implicit val system = ActorSystem("example")
  implicit val materializer = ActorMaterializer()
  implicit val context = StreamContext()

  // TCP greeter service. Use with e.g. "telnet localhost 5150"
  receiveRequestBody[String, String]("netty4:tcp://localhost:5150?textline=true")
    .map(s => s"hello $s")
    .reply.run()

  // HTTP greeter service. Use with e.g. "curl http://localhost:8080/greeter?name=..."
  receiveRequest[String, String]("jetty:http://localhost:8080/greeter")
    .map(msg => StreamMessage(s"Hello ${msg.headers.getOrElse("name", "unknown")}\n"))
    .reply.run()
}
