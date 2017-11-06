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

package streamz.camel.akka

import akka.stream._
import akka.stream.stage._
import org.apache.camel._
import streamz.camel.{ StreamContext, StreamMessage }

import scala.concurrent.{ ExecutionContext, Future }
import scala.reflect.ClassTag
import scala.util.{ Failure, Success, Try }

private[akka] class EndpointConsumer[A](uri: String)(implicit streamContext: StreamContext, tag: ClassTag[A])
    extends GraphStage[SourceShape[StreamMessage[A]]] {

  private implicit val ec: ExecutionContext =
    ExecutionContext.fromExecutorService(streamContext.executorService)

  val out: Outlet[StreamMessage[A]] =
    Outlet("EndpointConsumer.out")

  override val shape: SourceShape[StreamMessage[A]] =
    SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      import streamContext.consumerTemplate

      private val consumedCallback = getAsyncCallback(consumed)

      setHandler(out, new OutHandler {
        override def onPull(): Unit =
          consumeAsync()
      })

      private def consumeAsync(): Unit = {
        Future(consumerTemplate.receive(uri, 500)).foreach(consumedCallback.invoke)
      }

      private def consumed(exchange: Exchange): Unit = {
        exchange match {
          case null =>
            if (!isClosed(out)) consumeAsync()
          case ce if ce.getPattern != ExchangePattern.InOnly =>
            failStage(new IllegalArgumentException(s"Exchange pattern ${ExchangePattern.InOnly} expected but was ${ce.getPattern}"))
          case ce if ce.getException ne null =>
            consumerTemplate.doneUoW(ce)
            failStage(ce.getException)
          case ce =>
            Try(StreamMessage.from[A](ce.getIn)) match {
              case Success(m) =>
                consumerTemplate.doneUoW(ce)
                push(out, m)
              case Failure(e) =>
                ce.setException(e)
                consumerTemplate.doneUoW(ce)
                failStage(e)
            }
        }
      }
    }
}
