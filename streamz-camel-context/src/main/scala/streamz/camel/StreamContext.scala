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

package streamz.camel

import java.util.concurrent.{ ExecutorService, LinkedBlockingQueue, ThreadPoolExecutor, TimeUnit }
import java.util.function.Supplier

import org.apache.camel._
import org.apache.camel.impl.{ DefaultCamelContext, DefaultExchange }

import scala.reflect.ClassTag

object StreamContext {
  type ExecutorServiceFactory = () => ExecutorService

  /**
   * Default factory for the executor service used for running blocking endpoint operations.
   */
  val DefaultExecutorServiceFactory: ExecutorServiceFactory =
    () => new ThreadPoolExecutor(2, 16, 60, TimeUnit.SECONDS, new LinkedBlockingQueue[Runnable](), new ThreadPoolExecutor.DiscardPolicy)

  /**
   * Creates and returns a started [[StreamContext]] with an internally managed `camelContext`
   * and an `executorService` created with [[DefaultExecutorServiceFactory]].
   */
  def apply(): StreamContext =
    DefaultStreamContext.apply(DefaultExecutorServiceFactory)

  /**
   * Creates and returns a started [[StreamContext]] with an internally managed `camelContext`
   * and an `executorService` created with given `executorServiceFactory`.
   *
   * @param executorServiceFactory factory for creating the stream context's `executorService`.
   */
  def apply(executorServiceFactory: ExecutorServiceFactory): StreamContext =
    DefaultStreamContext.apply(executorServiceFactory)

  /**
   * Creates and returns a started [[StreamContext]] with an externally managed `camelContext`
   * and an `executorService` created with [[DefaultExecutorServiceFactory]].
   *
   * @param camelContext externally managed [[CamelContext]]. Applications are responsible
   *                     for starting the `CamelContext` before calling this factory method
   *                     and stopping the `CamelContext` after stopping the created `StreamContext`.
   */
  def apply(camelContext: CamelContext): StreamContext =
    new StreamContext(camelContext, DefaultExecutorServiceFactory).start()

  /**
   * Creates and returns a started [[StreamContext]] with an externally managed `camelContext`
   * and an `executorService` created with given `executorServiceFactory`.
   *
   * @param camelContext externally managed [[CamelContext]]. Applications are responsible
   *                     for starting the `CamelContext` before calling this factory method
   *                     and stopping the `CamelContext` after stopping the created `StreamContext`.
   * @param executorServiceFactory factory for creating the stream context's `executorService`.
   */
  def apply(camelContext: CamelContext, executorServiceFactory: ExecutorServiceFactory): StreamContext =
    new StreamContext(camelContext, executorServiceFactory).start()

  /**
   * Java API.
   *
   * Creates and returns a started [[StreamContext]] with an internally managed `camelContext`
   * and an `executorService` created with [[DefaultExecutorServiceFactory]].
   */
  def create(): StreamContext =
    apply()

  /**
   * Java API.
   *
   * Creates and returns a started [[StreamContext]] with an internally managed `camelContext`
   * and an `executorService` created with given `executorServiceFactory`.
   *
   * @param executorServiceFactory factory for creating the stream context's `executorService`.
   */
  def create(executorServiceFactory: Supplier[ExecutorService]): StreamContext =
    apply(() => executorServiceFactory.get())

  /**
   * Java API.
   *
   * Creates and returns a started [[StreamContext]] with an externally managed `camelContext`
   * and an `executorService` created with [[DefaultExecutorServiceFactory]].
   *
   * @param camelContext externally managed [[CamelContext]]. Applications are responsible
   *                     for starting the `CamelContext` before calling this factory method
   *                     and stopping the `CamelContext` after stopping the created `StreamContext`.
   */
  def create(camelContext: CamelContext): StreamContext =
    apply(camelContext)

  /**
   * Java API.
   *
   * Creates and returns a started [[StreamContext]] with an externally managed `camelContext`
   * and an `executorService` created with given `executorServiceFactory`.
   *
   * @param camelContext externally managed [[CamelContext]]. Applications are responsible
   *                     for starting the `CamelContext` before calling this factory method
   *                     and stopping the `CamelContext` after stopping the created `StreamContext`.
   * @param executorServiceFactory factory for creating the stream context's `executorService`.
   */
  def create(camelContext: CamelContext, executorServiceFactory: Supplier[ExecutorService]): StreamContext =
    apply(camelContext, () => executorServiceFactory.get())
}

/**
 * A stream context required by the Camel DSL elements for FS2 and Akka Streams.
 *
 * @param camelContext externally managed [[CamelContext]]. Applications are responsible
 *                     for starting the `CamelContext` before starting this `StreamContext`
 *                     and stopping the `CamelContext` after stopping this `StreamContext`.
 * @param executorServiceFactory factory for creating this stream context's `executorService`.
 */
class StreamContext(val camelContext: CamelContext, executorServiceFactory: StreamContext.ExecutorServiceFactory = StreamContext.DefaultExecutorServiceFactory) {
  /**
   * Executor service used for running blocking endpoint operations. It is created with `executorServiceFactory`.
   */
  lazy val executorService: ExecutorService =
    executorServiceFactory()

  /**
   * A Camel producer template created from `camelContext`.
   */
  lazy val producerTemplate: ProducerTemplate =
    camelContext.createProducerTemplate()

  /**
   * A Camel consumer template created from `camelContext`.
   */
  lazy val consumerTemplate: ConsumerTemplate =
    camelContext.createConsumerTemplate()

  /**
   * A Camel consumer for given `uri`. If the corresponding endpoint doesn't exist yet, it is
   * created and started. The returned consumer must be started (and stopped) by the caller.
   */
  def consumer(uri: String, processor: AsyncProcessor): Consumer = {
    val endpoint = camelContext.getEndpoint(uri)
    if (!camelContext.getEndpoints.contains(endpoint)) {
      endpoint.start()
    }
    endpoint.createConsumer(processor)
  }

  /**
   * Creates a Camel [[Exchange]] of given `pattern` and input `message`.
   */
  def createExchange[A](message: StreamMessage[A], pattern: ExchangePattern): Exchange = {
    val exchange = new DefaultExchange(camelContext, pattern)
    exchange.setIn(message.camelMessage(camelContext))
    exchange
  }

  /**
   * Converts `obj` to type `A` using a Camel type converter.
   *
   * @throws TypeConversionException if type conversion fails.
   */
  def convertObject[A](obj: Any)(implicit classTag: ClassTag[A]): A = {
    val clazz = classTag.runtimeClass.asInstanceOf[Class[A]]
    val result = camelContext.getTypeConverter.mandatoryConvertTo[A](clazz, obj)

    obj match {
      case cache: StreamCache => cache.reset()
      case _ =>
    }

    result
  }

  /**
   * Java API.
   *
   * Converts `obj` to `clazz` using a Camel type converter.
   *
   * @throws TypeConversionException if type conversion fails.
   */
  def convertObject[A](obj: Any, clazz: Class[A]): A =
    convertObject(obj)(ClassTag(clazz))

  /**
   * Starts this `StreamContext` and returns `this`.
   */
  def start(): StreamContext = {
    consumerTemplate.start()
    producerTemplate.start()
    this
  }

  /**
   * Stops this `StreamContext`.
   */
  def stop(): Unit = {
    consumerTemplate.stop()
    producerTemplate.stop()
    executorService.shutdown()
  }
}

object DefaultStreamContext {
  /**
   * Creates and returns a started [[DefaultStreamContext]].
   *
   * @param executorServiceFactory factory for creating the stream context's `executorService`.
   *
   */
  def apply(executorServiceFactory: StreamContext.ExecutorServiceFactory): DefaultStreamContext =
    new DefaultStreamContext(executorServiceFactory).start()

  /**
   * Java API.
   *
   * Creates and returns a started [[DefaultStreamContext]].
   *
   * @param executorServiceFactory factory for creating the stream context's `executorService`.
   *
   */
  def create(executorServiceFactory: StreamContext.ExecutorServiceFactory): DefaultStreamContext =
    apply(executorServiceFactory)
}

/**
 * A default [[StreamContext]] with an internally managed [[DefaultCamelContext]]. The lifecycle
 * of the `DefaultCamelContext` is bound to the lifecycle of this `DefaultStreamContext`.
 *
 * @param executorServiceFactory factory for creating this stream context's `executorService`.
 *
 */
class DefaultStreamContext(executorServiceFactory: StreamContext.ExecutorServiceFactory) extends StreamContext(new DefaultCamelContext, executorServiceFactory) {
  /**
   * Starts this `StreamContext` including the internally managed [[DefaultCamelContext]] and returns `this`.
   */
  override def start(): DefaultStreamContext = {
    camelContext.start()
    super.start()
    this
  }

  /**
   * Stops this `StreamContext` including the internally managed [[DefaultCamelContext]].
   */
  override def stop(): Unit = {
    super.stop()
    camelContext.stop()
  }
}
