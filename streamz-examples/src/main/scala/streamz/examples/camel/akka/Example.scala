/*
 * Copyright 2014 - 2019 the original author or authors.
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

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._

import streamz.camel.akka.scaladsl._
import streamz.examples.camel.ExampleContext

import scala.collection.immutable.Iterable
import scala.collection.compat._

object Example extends ExampleContext with App {
  implicit val system = ActorSystem("example")
  implicit val materializer = ActorMaterializer()

  val tcpLineSource: Source[String, NotUsed] =
    receiveBody[String](tcpEndpointUri)

  val fileLineSource: Source[String, NotUsed] =
    receiveBody[String](fileEndpointUri).mapConcat(_.linesIterator.to(Iterable))

  val linePrefixSource: Source[String, NotUsed] =
    Source.fromIterator(() => Iterator.from(1)).sendRequest[String](serviceEndpointUri)

  val stream: Source[String, NotUsed] =
    tcpLineSource
      .merge(fileLineSource)
      .zipWith(linePrefixSource)((l, n) => n concat l)
      .send(printerEndpointUri)

  stream.runWith(Sink.ignore)
}
