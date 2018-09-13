/*
 * Copyright 2014 - 2018 the original author or authors.
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

import cats.effect.IO
import cats.implicits._
import fs2._
import org.apache.camel.spi.Synchronization
import org.apache.camel.{ Exchange, ExchangePattern, TypeConversionException }
import streamz.camel.{ StreamContext, StreamMessage }

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag
import scala.util._

package object dsl {
  /**
   * Camel endpoint combinators for [[StreamMessage]] streams of type `Stream[IO, StreamMessage[A]]`.
   */
  implicit class SendDsl[A](self: Stream[IO, StreamMessage[A]]) {
    /**
     * @see [[dsl.send]]
     */
    def send(uri: String)(implicit context: StreamContext): Stream[IO, StreamMessage[A]] =
      self.through(dsl.send[A](uri))

    /**
     * @see [[dsl.sendRequest]]
     */
    def sendRequest[B](uri: String)(implicit context: StreamContext, tag: ClassTag[B]): Stream[IO, StreamMessage[B]] =
      self.through(dsl.sendRequest[A, B](uri))
  }

  /**
   * Camel endpoint combinators for [[StreamMessage]] body streams of type `Stream[IO, A]`.
   */
  implicit class SendBodyDsl[A](self: Stream[IO, A]) {
    /**
     * @see [[dsl.sendBody]]
     */
    def send(uri: String)(implicit context: StreamContext): Stream[IO, A] =
      self.through(dsl.sendBody[A](uri))

    /**
     * @see [[dsl.sendRequestBody]]
     */
    def sendRequest[B](uri: String)(implicit context: StreamContext, tag: ClassTag[B]): Stream[IO, B] =
      self.through(dsl.sendRequestBody[A, B](uri))
  }

  /**
   * Camel endpoint combinators for [[StreamMessage]] streams of type `Stream[Pure, StreamMessage[A]]`.
   */
  implicit class SendDslPure[A](self: Stream[Pure, StreamMessage[A]]) {
    /**
     * @see [[dsl.send]]
     */
    def send(uri: String)(implicit context: StreamContext): Stream[IO, StreamMessage[A]] =
      new SendDsl[A](self.covary[IO]).send(uri)

    /**
     * @see [[dsl.sendRequest()]]
     */
    def sendRequest[B](uri: String)(implicit context: StreamContext, tag: ClassTag[B]): Stream[IO, StreamMessage[B]] =
      new SendDsl[A](self.covary[IO]).sendRequest(uri)
  }

  /**
   * Camel endpoint combinators for [[StreamMessage]] body streams of type `Stream[Pure, A]`.
   */
  implicit class SendBodyDslPure[A](self: Stream[Pure, A]) {
    /**
     * @see [[dsl.sendBody]]
     */
    def send(uri: String)(implicit context: StreamContext): Stream[IO, A] =
      new SendBodyDsl[A](self.covary[IO]).send(uri)

    /**
     * @see [[dsl.sendRequestBody]]
     */
    def sendRequest[B](uri: String)(implicit context: StreamContext, tag: ClassTag[B]): Stream[IO, B] =
      new SendBodyDsl[A](self.covary[IO]).sendRequest(uri)
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
   * @throws TypeConversionException if type conversion fails.
   */
  def receive[A](uri: String)(implicit context: StreamContext, tag: ClassTag[A]): Stream[IO, StreamMessage[A]] = {
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
   * @throws TypeConversionException if type conversion fails.
   */
  def receiveBody[A](uri: String)(implicit context: StreamContext, tag: ClassTag[A]): Stream[IO, A] =
    receive(uri).map(_.body)

  /**
   * Creates a pipe that initiates an [[ExchangePattern.InOnly]] [[StreamMessage]] exchange with the Camel endpoint
   * identified by `uri` and continues the stream with the input [[StreamMessage]] after the endpoint has processed
   * that message. The pipe completes with an error if the message exchange with the endpoint fails.
   *
   * @param uri Camel endpoint URI.
   */
  def send[A](uri: String)(implicit context: StreamContext): Pipe[IO, StreamMessage[A], StreamMessage[A]] =
    produce[A, A](uri, ExchangePattern.InOnly, (message, _) => message)

  /**
   * Creates a pipe that initiates an [[ExchangePattern.InOnly]] message exchange with the Camel endpoint
   * identified by `uri` and continues the stream with the input message after the endpoint has processed
   * that message. The pipe completes with an error if the message exchange with the endpoint fails.
   *
   * @param uri Camel endpoint URI.
   */
  def sendBody[A](uri: String)(implicit context: StreamContext): Pipe[IO, A, A] =
    s => s.map(StreamMessage(_)).through(send(uri)).map(_.body)

  /**
   * Creates a pipe that initiates an [[ExchangePattern.InOut]] [[StreamMessage]] exchange with the Camel endpoint
   * identified by `uri` and continues the stream with the output [[StreamMessage]] received from the endpoint. The
   * output [[StreamMessage]] body is converted to type `B` using a Camel type converter. The pipe completes
   * with an error if the message exchange with the endpoint fails.
   *
   * @param uri Camel endpoint URI.
   * @throws TypeConversionException if type conversion fails.
   */
  def sendRequest[A, B](uri: String)(implicit context: StreamContext, tag: ClassTag[B]): Pipe[IO, StreamMessage[A], StreamMessage[B]] =
    produce[A, B](uri, ExchangePattern.InOut, (_, exchange) => StreamMessage.from[B](exchange.getOut))

  /**
   * Creates a pipe that initiates an [[ExchangePattern.InOut]] message exchange with the Camel endpoint
   * identified by `uri` and continues the stream with the output message received from the endpoint. The
   * output message is converted to type `B` using a Camel type converter. The pipe completes
   * with an error if the message exchange with the endpoint fails.
   *
   * @param uri Camel endpoint URI.
   * @throws TypeConversionException if type conversion fails.
   */
  def sendRequestBody[A, B](uri: String)(implicit context: StreamContext, tag: ClassTag[B]): Pipe[IO, A, B] =
    s => s.map(StreamMessage(_)).through(sendRequest[A, B](uri)).map(_.body)

  private def consume[A](uri: String)(implicit context: StreamContext, tag: ClassTag[A]): Stream[IO, StreamMessage[A]] = {
    implicit val contextShift = IO.contextShift(ExecutionContext.fromExecutor(context.executorService))
    Stream.repeatEval {
      IO.shift >> IO.async[StreamMessage[A]] { callback =>
        Try(context.consumerTemplate.receive(uri, 500)) match {
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

  private def produce[A, B](uri: String, pattern: ExchangePattern, result: (StreamMessage[A], Exchange) => StreamMessage[B])(implicit context: StreamContext): Pipe[IO, StreamMessage[A], StreamMessage[B]] = { s =>
    implicit val contextShift = IO.contextShift(ExecutionContext.fromExecutor(context.executorService))
    s.flatMap { message =>
      Stream.eval {
        IO.shift >> IO.async[StreamMessage[B]] { callback =>
          context.producerTemplate.asyncCallback(uri, context.createExchange(message, pattern), new Synchronization {
            override def onFailure(exchange: Exchange): Unit =
              callback(Left(exchange.getException))
            override def onComplete(exchange: Exchange): Unit = Try(result(message, exchange)) match {
              case Success(r) => callback(Right(result(message, exchange)))
              case Failure(e) => callback(Left(e))
            }
          })
        }
      }
    }
  }
}
