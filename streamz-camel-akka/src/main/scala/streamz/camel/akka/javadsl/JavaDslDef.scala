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

package streamz.camel.akka.javadsl

import akka.NotUsed
import akka.stream.{ FlowShape, Graph }
import akka.stream.javadsl.{ Flow, Source }
import streamz.camel.{ StreamContext, StreamMessage }
import streamz.camel.akka.scaladsl

import scala.reflect.ClassTag

private class JavaDslDef(streamContext: StreamContext) {
  def receive[A](uri: String, clazz: Class[A]): Source[StreamMessage[A], NotUsed] =
    Source.fromGraph(scaladsl.receive(uri)(streamContext, ClassTag(clazz)))

  def receiveBody[A](uri: String, clazz: Class[A]): Source[A, NotUsed] =
    Source.fromGraph(scaladsl.receiveBody(uri)(streamContext, ClassTag(clazz)))

  def receiveRequest[A, B](uri: String, capacity: Int, clazz: Class[B]): Flow[StreamMessage[A], StreamMessage[B], NotUsed] =
    Flow.fromGraph(scaladsl.receiveRequest(uri, capacity)(streamContext, ClassTag(clazz)))

  def receiveRequest[A, B](uri: String, clazz: Class[B]): Flow[StreamMessage[A], StreamMessage[B], NotUsed] =
    Flow.fromGraph(scaladsl.receiveRequest(uri)(streamContext, ClassTag(clazz)))

  def receiveRequestBody[A, B](uri: String, capacity: Int, clazz: Class[B]): Flow[A, B, NotUsed] =
    Flow.fromGraph(scaladsl.receiveRequestBody(uri, capacity)(streamContext, ClassTag(clazz)))

  def receiveRequestBody[A, B](uri: String, clazz: Class[B]): Flow[A, B, NotUsed] =
    Flow.fromGraph(scaladsl.receiveRequestBody(uri)(streamContext, ClassTag(clazz)))

  def send[A](uri: String, parallelism: Int): Graph[FlowShape[StreamMessage[A], StreamMessage[A]], NotUsed] =
    scaladsl.send[A](uri, parallelism)(streamContext)

  def sendBody[A](uri: String, parallelism: Int): Graph[FlowShape[A, A], NotUsed] =
    scaladsl.sendBody[A](uri, parallelism)(streamContext)

  def sendRequest[A, B](uri: String, parallelism: Int, clazz: Class[B]): Graph[FlowShape[StreamMessage[A], StreamMessage[B]], NotUsed] =
    scaladsl.sendRequest[A, B](uri, parallelism)(streamContext, ClassTag(clazz))

  def sendRequest[A, B](uri: String, clazz: Class[B]): Graph[FlowShape[StreamMessage[A], StreamMessage[B]], NotUsed] =
    scaladsl.sendRequest[A, B](uri)(streamContext, ClassTag(clazz))

  def sendRequestBody[A, B](uri: String, parallelism: Int, clazz: Class[B]): Graph[FlowShape[A, B], NotUsed] =
    scaladsl.sendRequestBody[A, B](uri, parallelism)(streamContext, ClassTag(clazz))

  def sendRequestBody[A, B](uri: String, clazz: Class[B]): Graph[FlowShape[A, B], NotUsed] =
    scaladsl.sendRequestBody[A, B](uri)(streamContext, ClassTag(clazz))
}
