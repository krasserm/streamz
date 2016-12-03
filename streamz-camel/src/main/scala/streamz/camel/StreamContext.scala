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

import org.apache.camel._
import org.apache.camel.impl.{ DefaultExchange, DefaultCamelContext }

import scala.reflect.ClassTag

object StreamContext {
  /**
   * Creates and returns a started [[DefaultStreamContext]].
   */
  def apply(): StreamContext =
    DefaultStreamContext.apply()

  /**
   * Creates and returns a started [[StreamContext]].
   *
   * @param camelContext externally managed [[CamelContext]]. Applications are responsible
   *                     for starting the `CamelContext` before calling this factory method
   *                     and stopping the `CamelContext` after stopping the created `StreamContext`.
   */
  def apply(camelContext: CamelContext): StreamContext =
    new StreamContext(camelContext).start()
}

/**
 * A stream context required by the [[akkadsl]] and [[fs2dsl]] stream DSL elements.
 *
 * @param camelContext externally managed [[CamelContext]]. Applications are responsible
 *                     for starting the `CamelContext` before starting this `StreamContext`
 *                     and stopping the `CamelContext` after stopping this `StreamContext`.
 */
class StreamContext(val camelContext: CamelContext) {
  /**
   * A Camel consumer template created from `camelContext`.
   */
  lazy val consumerTemplate: ConsumerTemplate =
    camelContext.createConsumerTemplate()

  /**
   * A Camel producer template created from `camelContext`.
   */
  lazy val producerTemplate: ProducerTemplate =
    camelContext.createProducerTemplate()

  /**
   * Creates a Camel [[Exchange]] of given `pattern` and input `message`.
   */
  def createExchange[A](message: StreamMessage[A], pattern: ExchangePattern): Exchange = {
    val exchange = new DefaultExchange(camelContext, pattern)
    exchange.setIn(message.camelMessage)
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
  }
}

object DefaultStreamContext {
  /**
   * Creates and returns a started [[DefaultStreamContext]].
   */
  def apply(): DefaultStreamContext =
    new DefaultStreamContext().start()
}

/**
 * A default [[StreamContext]] with an internally managed [[DefaultCamelContext]]. The lifecycle
 * of the `DefaultCamelContext` is bound to the lifecycle of this `DefaultStreamContext`.
 */
class DefaultStreamContext extends StreamContext(new DefaultCamelContext) {
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
