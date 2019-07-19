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

package streamz.examples.camel

import org.apache.camel.impl.{ DefaultCamelContext, SimpleRegistry }

import streamz.camel.StreamContext

trait ExampleContext {
  private val camelRegistry = new SimpleRegistry
  private val camelContext = new DefaultCamelContext

  camelContext.start()
  camelContext.setRegistry(camelRegistry)
  camelRegistry.put("exampleService", new ExampleService)

  implicit val context: StreamContext =
    StreamContext(camelContext)

  val tcpEndpointUri: String =
    "netty4:tcp://localhost:5150?sync=false&textline=true&encoding=utf-8"

  val fileEndpointUri: String =
    "file:input?charset=utf-8"

  val serviceEndpointUri: String =
    "bean:exampleService?method=linePrefix"

  val printerEndpointUri: String =
    "stream:out"
}

class ExampleService {
  def linePrefix(lineNumber: Int): String = s"[$lineNumber] "
}
