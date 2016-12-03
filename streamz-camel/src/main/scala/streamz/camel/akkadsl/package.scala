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

package streamz.camel

import akka.NotUsed
import akka.stream._
import akka.stream.scaladsl._

import org.apache.camel.spi.Synchronization
import org.apache.camel.{ Exchange, ExchangePattern, TypeConversionException }

import scala.concurrent.{ Future, Promise }
import scala.reflect.ClassTag
import scala.util._

package object akkadsl {
  /**
   * Camel endpoint combinators for [[StreamMessage]] streams of type `FlowOps[StreamMessage[A], M]`.
   */
  class StreamMessageScalaDsl[A, M, FO <: FlowOps[StreamMessage[A], M]](val self: FO) {
    /**
     * @see [[akkadsl.send]]
     */
    def send(uri: String, parallelism: Int = 1)(implicit context: StreamContext): self.Repr[StreamMessage[A]] =
      self.via(akkadsl.send[A](uri, parallelism))

    /**
     * @see [[akkadsl.request]]
     */
    def request[B](uri: String, parallelism: Int = 1)(implicit context: StreamContext, tag: ClassTag[B]): self.Repr[StreamMessage[B]] =
      self.via(akkadsl.request[A, B](uri, parallelism))
  }

  /**
   * Camel endpoint combinators for [[StreamMessage]] body streams of type `FlowOps[A, M]`.
   */
  class StreamMessageBodyScalaDsl[A, M, FO <: FlowOps[A, M]](val self: FO) {
    /**
     * @see [[akkadsl.send]]
     */
    def send(uri: String, parallelism: Int = 1)(implicit context: StreamContext): self.Repr[A] =
      self.map(StreamMessage(_)).via(akkadsl.send[A](uri, parallelism)).map(_.body)

    /**
     * @see [[akkadsl.request]]
     */
    def request[B](uri: String, parallelism: Int = 1)(implicit context: StreamContext, tag: ClassTag[B]): self.Repr[B] =
      self.map(StreamMessage(_)).via(akkadsl.request[A, B](uri, parallelism)).map(_.body)
  }

  implicit def streamMessageSourceScalaDsl[A, M](self: Source[StreamMessage[A], M]): StreamMessageScalaDsl[A, M, Source[StreamMessage[A], M]] =
    new StreamMessageScalaDsl(self)

  implicit def streamMessageFlowScalaDsl[A, B, M](self: Flow[A, StreamMessage[B], M]): StreamMessageScalaDsl[B, M, Flow[A, StreamMessage[B], M]] =
    new StreamMessageScalaDsl(self)

  implicit def streamMessageSubFlowOfSourceScalaDsl[A, M](self: SubFlow[StreamMessage[A], M, Source[StreamMessage[A], M]#Repr, Source[StreamMessage[A], M]#Closed]): StreamMessageScalaDsl[A, M, SubFlow[StreamMessage[A], M, Source[StreamMessage[A], M]#Repr, Source[StreamMessage[A], M]#Closed]] =
    new StreamMessageScalaDsl(self)

  implicit def streamMessageSubFlowOfFlowScalaDsl[A, B, M](self: SubFlow[StreamMessage[B], M, Flow[A, StreamMessage[B], M]#Repr, Flow[A, StreamMessage[B], M]#Closed]): StreamMessageScalaDsl[B, M, SubFlow[StreamMessage[B], M, Flow[A, StreamMessage[B], M]#Repr, Flow[A, StreamMessage[B], M]#Closed]] =
    new StreamMessageScalaDsl(self)

  implicit def streamMessageBodySourceScalaDsl[A, M](self: Source[A, M]): StreamMessageBodyScalaDsl[A, M, Source[A, M]] =
    new StreamMessageBodyScalaDsl(self)

  implicit def streamMessageBodyFlowScalaDsl[A, B, M](self: Flow[A, B, M]): StreamMessageBodyScalaDsl[B, M, Flow[A, B, M]] =
    new StreamMessageBodyScalaDsl(self)

  implicit def streamMessageBodySubFlowOfSourceScalaDsl[A, M](self: SubFlow[A, M, Source[A, M]#Repr, Source[A, M]#Closed]): StreamMessageBodyScalaDsl[A, M, SubFlow[A, M, Source[A, M]#Repr, Source[A, M]#Closed]] =
    new StreamMessageBodyScalaDsl(self)

  implicit def streamMessageBodySubFlowOfFlowScalaDsl[A, B, M](self: SubFlow[B, M, Flow[A, B, M]#Repr, Flow[A, B, M]#Closed]): StreamMessageBodyScalaDsl[B, M, SubFlow[B, M, Flow[A, B, M]#Repr, Flow[A, B, M]#Closed]] =
    new StreamMessageBodyScalaDsl(self)

  /**
   * Creates a source of message bodies consumed from the Camel endpoint identified by `uri`.
   * Message bodies are converted to type `O` using a Camel type converter. The source completes
   * with an error if the message exchange with the endpoint fails.
   *
   * Only [[ExchangePattern.InOnly]] message exchanges with the endpoint are supported at the moment.
   * Endpoints that create [[ExchangePattern.InOut]] message exchanges will not receive a reply from
   * the stream.
   *
   * @param uri Camel endpoint URI.
   * @throws TypeConversionException if type conversion fails.
   */
  def receiveBody[O](uri: String)(implicit streamContext: StreamContext, tag: ClassTag[O]): Source[O, NotUsed] =
    consume[O](uri).map(_.body)

  /**
   * Creates a source of [[StreamMessage]]s consumed from the Camel endpoint identified by `uri`.
   * [[StreamMessage]] bodies are converted to type `O` using a Camel type converter. The source
   * completes with an error if the message exchange with the endpoint fails.
   *
   * Only [[ExchangePattern.InOnly]] message exchanges with the endpoint are supported at the moment.
   * Endpoints that create [[ExchangePattern.InOut]] message exchanges will not receive a reply from
   * the stream.
   *
   * @param uri Camel endpoint URI.
   * @throws TypeConversionException if type conversion fails.
   */
  def receive[O](uri: String)(implicit streamContext: StreamContext, tag: ClassTag[O]): Source[StreamMessage[O], NotUsed] =
    consume[O](uri)

  /**
   * Creates a flow that initiates an [[ExchangePattern.InOnly]] message exchange with the Camel endpoint
   * identified by `uri` and continues the flow with the input message after the endpoint has processed
   * that message. The flow completes with an error if the message exchange with the endpoint fails.
   *
   * @param uri Camel endpoint URI.
   */
  def send[I](uri: String, parallelism: Int)(implicit context: StreamContext): Graph[FlowShape[StreamMessage[I], StreamMessage[I]], NotUsed] =
    Flow[StreamMessage[I]].mapAsync(1)(produce[I, I](uri, _, ExchangePattern.InOnly, (message, _) => message))

  /**
   * Creates a flow that initiates an [[ExchangePattern.InOut]] message exchange with the Camel endpoint
   * identified by `uri` and continues the flow with the output message received from the endpoint. The
   * output message body is converted to type `O` using a Camel type converter. The flow completes
   * with an error if the message exchange with the endpoint fails.
   *
   * @param uri Camel endpoint URI.
   * @throws TypeConversionException if type conversion fails.
   */
  def request[I, O](uri: String, parallelism: Int)(implicit context: StreamContext, tag: ClassTag[O]): Graph[FlowShape[StreamMessage[I], StreamMessage[O]], NotUsed] =
    Flow[StreamMessage[I]].mapAsync(1)(produce[I, O](uri, _, ExchangePattern.InOut, (_, exchange) => StreamMessage.from[O](exchange.getOut)))

  private def consume[O](uri: String)(implicit streamContext: StreamContext, tag: ClassTag[O]): Source[StreamMessage[O], NotUsed] =
    Source.actorPublisher[StreamMessage[O]](EndpointConsumer.props[O](uri)).mapMaterializedValue(_ => NotUsed)

  private def produce[I, O](uri: String, message: StreamMessage[I], pattern: ExchangePattern, result: (StreamMessage[I], Exchange) => StreamMessage[O])(implicit context: StreamContext): Future[StreamMessage[O]] = {
    val promise = Promise[StreamMessage[O]]()
    context.producerTemplate.asyncCallback(uri, context.createExchange(message, pattern), new Synchronization {
      override def onFailure(exchange: Exchange): Unit =
        promise.failure(exchange.getException)
      override def onComplete(exchange: Exchange): Unit = Try(result(message, exchange)) match {
        case Success(r) => promise.success(result(message, exchange))
        case Failure(e) => promise.failure(e)
      }
    })
    promise.future
  }
}
