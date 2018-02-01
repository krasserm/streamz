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

package streamz.converter

import akka.actor.Props
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage._

import streamz.converter.Converter.Callback

private[converter] object AkkaStreamPublisher {
  case class Next[A](elem: A, cb: Callback[Option[Unit]])
  case class Error(cause: Throwable)
  case object Complete

  def props[A]: Props =
    Props(new AkkaStreamPublisher[A])
}

private[converter] class AkkaStreamPublisher[A] extends ActorPublisher[A] {
  import AkkaStreamPublisher._

  private val defined = Some(())
  private var next: Option[Next[A]] = None

  override def receive = {
    //
    // Messages from upstream (fs2)
    //
    case n: Next[A] if isCanceled =>
      n.cb(Right(None))
    case n: Next[A] if totalDemand > 0 =>
      assert(next.isEmpty)
      onNext(n.elem)
      n.cb(Right(defined))
    case n: Next[A] =>
      next = Some(n)
    case Error(cause) =>
      onError(cause)
    case Complete if !isErrorEmitted =>
      onComplete()
    //
    // Messages from downstream
    //
    case r: Request =>
      next.foreach { n =>
        onNext(n.elem)
        n.cb(Right(Some(())))
        next = None
      }
  }
}
