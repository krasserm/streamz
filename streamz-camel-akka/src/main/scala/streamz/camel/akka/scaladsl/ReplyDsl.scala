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

import akka.stream.scaladsl.{ Flow, Keep, RunnableGraph }

/**
 * Reply combinator for streams of type `Flow[A, A, M]`.
 */
class ReplyDsl[A, M](val self: Flow[A, A, M]) {
  /**
   * Pipes the flow's output to its input. Terminal operation on a flow created with [[receiveRequest]]
   * or [[receiveRequestBody]] whose output type has been transformed to its input type.
   *
   * @see [[receiveRequest]]
   * @see [[receiveRequestBody]]
   */
  def reply: RunnableGraph[M] = self.joinMat(Flow[A])(Keep.left)
}