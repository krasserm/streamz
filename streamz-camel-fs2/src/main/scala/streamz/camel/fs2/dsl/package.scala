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

package streamz.camel.fs2

import java.util.concurrent.TimeUnit

import cats.effect.Async
import fs2._
import org.apache.camel.spi.Synchronization
import org.apache.camel.{ Exchange, ExchangePattern }
import streamz.camel.{ StreamContext, StreamMessage }

import scala.reflect.ClassTag
import scala.util._

package object dsl {

  /**
   * Camel endpoint combinators for [[StreamMessage]] streams of type `Stream[F, StreamMessage[A]]`.
   */
  implicit class SendDsl[F[_]: Async, A](self: Stream[F, StreamMessage[A]]) {
    /**
     * @see [[dsl.send]]
     */
    def send(uri: String)(implicit context: StreamContext): Stream[F, StreamMessage[A]] =
      self.through(dsl.send[F, A](uri))

    /**
     * @see [[dsl.sendRequest]]
     */
    def sendRequest[B](uri: String)(implicit context: StreamContext, tag: ClassTag[B]): Stream[F, StreamMessage[B]] =
      self.through(dsl.sendRequest[F, A, B](uri))
  }

  /**
   * Camel endpoint combinators for [[StreamMessage]] body streams of type `Stream[F, A]`.
   */
  implicit class SendBodyDsl[F[_]: Async, A](self: Stream[F, A]) {
    /**
     * @see [[dsl.sendBody]]
     */
    def send(uri: String)(implicit context: StreamContext): Stream[F, A] =
      self.through(dsl.sendBody[F, A](uri))

    /**
     * @see [[dsl.sendRequestBody]]
     */
    def sendRequest[B](uri: String)(implicit context: StreamContext, tag: ClassTag[B]): Stream[F, B] =
      self.through(dsl.sendRequestBody[F, A, B](uri))
  }

  /**
   * Camel endpoint combinators for [[StreamMessage]] streams of type `Stream[Pure, StreamMessage[A]]`.
   */
  implicit class SendDslPure[A](self: Stream[Pure, StreamMessage[A]]) {
    /**
     * @see [[dsl.send]]
     */
    def send[F[_]](uri: String)(implicit context: StreamContext, async: Async[F]): Stream[F, StreamMessage[A]] =
      new SendDsl[F, A](self.covary[F]).send(uri)

    /**
     * @see [[dsl.sendRequest()]]
     */
    def sendRequest[F[_], B](uri: String)(implicit context: StreamContext, tag: ClassTag[B], async: Async[F]): Stream[F, StreamMessage[B]] =
      new SendDsl[F, A](self.covary[F]).sendRequest(uri)
  }

  /**
   * Camel endpoint combinators for [[StreamMessage]] body streams of type `Stream[Pure, A]`.
   */
  implicit class SendBodyDslPure[A](self: Stream[Pure, A]) {
    /**
     * @see [[dsl.sendBody]]
     */
    def send[F[_]: Async](uri: String)(implicit context: StreamContext): Stream[F, A] =
      new SendBodyDsl[F, A](self.covary[F]).send(uri)

    /**
     * @see [[dsl.sendRequestBody]]
     */
    def sendRequest[F[_]: Async, B](uri: String)(implicit context: StreamContext, tag: ClassTag[B]): Stream[F, B] =
      new SendBodyDsl[F, A](self.covary[F]).sendRequest(uri)
  }

  /**
   * Creates a stream of [[StreamMessage]]s consumed from the Camel endpoint identified by `uri`.
   * [[StreamMessage]] bodies are converted to type `A` using a Camel type converter. The stream
   * completes with an error if the message exchange with the endpoint fails.
   *
   * Only [[ExchangePattern.InOnly]] message exchanges with the endpoint are supported at the moment.
   * Endpoints that create [[ExchangePattern.InOut]] message exchanges will not receive a reply from
   * the stream.
   *
   * @param uri Camel endpoint URI.
   * @throws org.apache.camel.TypeConversionException if type conversion fails.
   */
  def receive[F[_]: Async, A](uri: String)(implicit context: StreamContext, tag: ClassTag[A]): Stream[F, StreamMessage[A]] = {
    consume(uri).filter(_ != null)
  }

  /**
   * Creates a stream of message consumed from the Camel endpoint identified by `uri`.
   * Message are converted to type `A` using a Camel type converter. The stream completes
   * with an error if the message exchange with the endpoint fails.
   *
   * Only [[ExchangePattern.InOnly]] message exchanges with the endpoint are supported at the moment.
   * Endpoints that create [[ExchangePattern.InOut]] message exchanges will not receive a reply from
   * the stream.
   *
   * @param uri Camel endpoint URI.
   * @throws org.apache.camel.TypeConversionException if type conversion fails.
   */
  def receiveBody[F[_]: Async, A](uri: String)(implicit context: StreamContext, tag: ClassTag[A]): Stream[F, A] =
    receive(uri).map(_.body)

  /**
   * Creates a pipe that initiates an [[ExchangePattern.InOnly]] [[StreamMessage]] exchange with the Camel endpoint
   * identified by `uri` and continues the stream with the input [[StreamMessage]] after the endpoint has processed
   * that message. The pipe completes with an error if the message exchange with the endpoint fails.
   *
   * @param uri Camel endpoint URI.
   */
  def send[F[_]: Async, A](uri: String)(implicit context: StreamContext): Pipe[F, StreamMessage[A], StreamMessage[A]] =
    produce[F, A, A](uri, ExchangePattern.InOnly, (message, _) => message)

  /**
   * Creates a pipe that initiates an [[ExchangePattern.InOnly]] message exchange with the Camel endpoint
   * identified by `uri` and continues the stream with the input message after the endpoint has processed
   * that message. The pipe completes with an error if the message exchange with the endpoint fails.
   *
   * @param uri Camel endpoint URI.
   */
  def sendBody[F[_]: Async, A](uri: String)(implicit context: StreamContext): Pipe[F, A, A] =
    s => s.map(StreamMessage(_)).through(send(uri)).map(_.body)

  /**
   * Creates a pipe that initiates an [[ExchangePattern.InOut]] [[StreamMessage]] exchange with the Camel endpoint
   * identified by `uri` and continues the stream with the output [[StreamMessage]] received from the endpoint. The
   * output [[StreamMessage]] body is converted to type `B` using a Camel type converter. The pipe completes
   * with an error if the message exchange with the endpoint fails.
   *
   * @param uri Camel endpoint URI.
   * @throws org.apache.camel.TypeConversionException if type conversion fails.
   */
  def sendRequest[F[_]: Async, A, B](uri: String)(implicit context: StreamContext, tag: ClassTag[B]): Pipe[F, StreamMessage[A], StreamMessage[B]] =
    produce[F, A, B](uri, ExchangePattern.InOut, (_, exchange) => StreamMessage.from[B](exchange.getOut))

  /**
   * Creates a pipe that initiates an [[ExchangePattern.InOut]] message exchange with the Camel endpoint
   * identified by `uri` and continues the stream with the output message received from the endpoint. The
   * output message is converted to type `B` using a Camel type converter. The pipe completes
   * with an error if the message exchange with the endpoint fails.
   *
   * @param uri Camel endpoint URI.
   * @throws org.apache.camel.TypeConversionException if type conversion fails.
   */
  def sendRequestBody[F[_]: Async, A, B](uri: String)(implicit context: StreamContext, tag: ClassTag[B]): Pipe[F, A, B] =
    s => s.map(StreamMessage(_)).through(sendRequest[F, A, B](uri)).map(_.body)

  private def consume[F[_], A](uri: String)(implicit context: StreamContext, tag: ClassTag[A], F: Async[F]): Stream[F, StreamMessage[A]] = {
    val timeout = context.config.getDuration("streamz.camel.consumer.receive.timeout", TimeUnit.MILLISECONDS)
    Stream.repeatEval {
      F.async_[StreamMessage[A]] { callback =>
        Try(context.consumerTemplate.receive(uri, timeout)) match {
          case Success(null) =>
            callback(Right(null))
          case Success(ce) if ce.getException != null =>
            callback(Left(ce.getException))
            context.consumerTemplate.doneUoW(ce)
          case Success(ce) =>
            Try(StreamMessage.from[A](ce.getIn)) match {
              case Success(m) =>
                callback(Right(m))
              case Failure(e) =>
                callback(Left(e))
                ce.setException(e)
            }
            context.consumerTemplate.doneUoW(ce)
          case Failure(ex) =>
            callback(Left(ex))
        }
      }
    }
  }

  private def produce[F[_], A, B](uri: String, pattern: ExchangePattern, result: (StreamMessage[A], Exchange) => StreamMessage[B])(implicit context: StreamContext, F: Async[F]): Pipe[F, StreamMessage[A], StreamMessage[B]] = { s =>
    s.flatMap { message =>
      Stream.eval {
        F.async_[StreamMessage[B]] { callback =>
          context.producerTemplate.asyncCallback(uri, context.createExchange(message, pattern), new Synchronization {
            override def onFailure(exchange: Exchange): Unit =
              callback(Left(exchange.getException))

            override def onComplete(exchange: Exchange): Unit = Try(result(message, exchange)) match {
              case Success(smb) => callback(Right(smb))
              case Failure(e) => callback(Left(e))
            }
          })
          ()
        }
      }
    }
  }
}
