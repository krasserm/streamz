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

package streamz.camel.akka.scaladsl

import akka.stream.scaladsl.FlowOps
import streamz.camel.{ StreamContext, StreamMessage }
import streamz.camel.akka.scaladsl

import scala.reflect.ClassTag

/**
 * Send combinators for [[StreamMessage]] streams of type `FlowOps[StreamMessage[A], M]`.
 */
class SendDsl[A, M, FO <: FlowOps[StreamMessage[A], M]](val self: FO) {
  /**
   * @see [[streamz.camel.akka.scaladsl.send]]
   */
  def send(uri: String, parallelism: Int = 1)(implicit context: StreamContext): self.Repr[StreamMessage[A]] =
    self.via(scaladsl.send[A](uri, parallelism))

  /**
   * @see [[streamz.camel.akka.scaladsl.sendRequest]]
   */
  def sendRequest[B](uri: String, parallelism: Int = 1)(implicit context: StreamContext, tag: ClassTag[B]): self.Repr[StreamMessage[B]] =
    self.via(scaladsl.sendRequest[A, B](uri, parallelism))
}