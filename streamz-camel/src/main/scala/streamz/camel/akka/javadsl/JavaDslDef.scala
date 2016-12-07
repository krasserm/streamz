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

package streamz.camel.akka.javadsl

import akka.NotUsed
import akka.stream.{ FlowShape, Graph }
import akka.stream.javadsl.Source

import streamz.camel.{ StreamMessage, StreamContext }
import streamz.camel.akka.scaladsl

import scala.reflect.ClassTag

private class JavaDslDef(streamContext: StreamContext) {
  def receive[O](uri: String, clazz: Class[O]): Source[StreamMessage[O], NotUsed] =
    Source.fromGraph(scaladsl.receive(uri)(streamContext, ClassTag(clazz)))

  def send[I](uri: String, parallelism: Int): Graph[FlowShape[StreamMessage[I], StreamMessage[I]], NotUsed] =
    scaladsl.send[I](uri, parallelism)(streamContext)

  def request[I, O](uri: String, parallelism: Int, clazz: Class[O]): Graph[FlowShape[StreamMessage[I], StreamMessage[O]], NotUsed] =
    scaladsl.request[I, O](uri, parallelism)(streamContext, ClassTag(clazz))

  def receiveBody[O](uri: String, clazz: Class[O]): Source[O, NotUsed] =
    Source.fromGraph(scaladsl.receiveBody(uri)(streamContext, ClassTag(clazz)))

  def sendBody[I](uri: String, parallelism: Int): Graph[FlowShape[I, I], NotUsed] =
    scaladsl.sendBody[I](uri, parallelism)(streamContext)

  def requestBody[I, O](uri: String, parallelism: Int, clazz: Class[O]): Graph[FlowShape[I, O], NotUsed] =
    scaladsl.requestBody[I, O](uri, parallelism)(streamContext, ClassTag(clazz))
}
