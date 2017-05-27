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

import java.util.concurrent.{ ArrayBlockingQueue, TimeUnit }

import akka.stream._
import akka.stream.stage._
import org.apache.camel.{ AsyncCallback => CamelAsyncCallback, _ }
import streamz.camel.{ StreamContext, StreamMessage }

import scala.collection.mutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.reflect.ClassTag
import scala.util.{ Failure, Success, Try }

private case class AsyncExchange(exchange: Exchange, callback: CamelAsyncCallback)

private class AsyncExchangeProcessor(capacity: Int) extends AsyncProcessor {
  val receivedExchanges = new ArrayBlockingQueue[AsyncExchange](capacity)

  def fail(t: Throwable): Unit =
    receivedExchanges.iterator()

  override def process(exchange: Exchange): Unit =
    throw new UnsupportedOperationException("Synchronous processing not supported")

  override def process(exchange: Exchange, callback: CamelAsyncCallback): Boolean = {
    receivedExchanges.put(AsyncExchange(exchange, callback))
    false
  }
}

private[akka] class EndpointConsumerReplier[A, B](uri: String, capacity: Int)(implicit streamContext: StreamContext, tag: ClassTag[B])
    extends GraphStage[FlowShape[StreamMessage[A], StreamMessage[B]]] {

  private implicit val ec: ExecutionContext =
    ExecutionContext.fromExecutorService(streamContext.executorService)

  val in: Inlet[StreamMessage[A]] =
    Inlet("EndpointConsumerReplier.in")

  val out: Outlet[StreamMessage[B]] =
    Outlet("EndpointConsumerReplier.out")

  override val shape: FlowShape[StreamMessage[A], StreamMessage[B]] =
    FlowShape.of(in, out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      private val processor = new AsyncExchangeProcessor(capacity)
      private val emittedExchanges = mutable.Queue.empty[AsyncExchange]
      private val consumedCallback = getAsyncCallback(consumed)

      private var consuming: Boolean = false
      private var consumer: Consumer = _

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          val AsyncExchange(ce, ac) = emittedExchanges.dequeue()
          ce.setOut(grab(in).camelMessage)
          ac.done(false)
          pull(in)
          if (!consuming && isAvailable(out)) consumeAsync()
        }
      })

      setHandler(out, new OutHandler {
        override def onPull(): Unit =
          if (!consuming && hasCapacity) consumeAsync()
      })

      private def hasCapacity: Boolean =
        emittedExchanges.size < capacity

      private def consumeAsync(): Unit = {
        Future(processor.receivedExchanges.poll(500, TimeUnit.MILLISECONDS)).foreach(consumedCallback.invoke)
        consuming = true
      }

      private def consumed(asyncExchange: AsyncExchange): Unit = {
        consuming = false
        asyncExchange match {
          case null =>
            if (!isClosed(out)) consumeAsync()
          case ae @ AsyncExchange(ce, ac) if ce.getPattern != ExchangePattern.InOut =>
            ac.done(false)
            failStage(new IllegalArgumentException(s"Exchange pattern ${ExchangePattern.InOut} expected but was ${ce.getPattern}"))
          case ae @ AsyncExchange(ce, ac) if ce.getException ne null =>
            ac.done(false)
            failStage(ce.getException)
          case ae @ AsyncExchange(ce, ac) =>
            Try(StreamMessage.from[B](ce.getIn)) match {
              case Success(m) =>
                push(out, m)
                emittedExchanges.enqueue(ae)
                if (hasCapacity && isAvailable(out)) consumeAsync()
              case Failure(e) =>
                ce.setException(e)
                ac.done(false)
                failStage(e)
            }
        }
      }

      override def preStart(): Unit = {
        consumer = streamContext.consumer(uri, processor)
        consumer.start()
        pull(in)
      }
    }
}
