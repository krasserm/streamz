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

package streamz.akka

import akka.actor.Props
import akka.stream.actor._
import akka.stream.actor.ActorSubscriberMessage._

import streamz.akka.Converter.Callback

private[akka] object AkkaStreamSubscriber {
  case class Request[A](callback: Callback[Option[A]])

  def props[A]: Props = Props(new AkkaStreamSubscriber[A])
}

private[akka] class AkkaStreamSubscriber[A] extends ActorSubscriber {
  import AkkaStreamSubscriber._

  private var termination: Option[Any] = None

  override val requestStrategy = ZeroRequestStrategy

  def waiting: Receive = {
    //
    // Messages from upstream
    //
    case t: OnError =>
      termination = Some(t)
    case OnComplete =>
      termination = Some(OnComplete)
    //
    // Messages from downstream (fs2)
    //
    case r: Request[A] =>
      termination match {
        case None =>
          request(1L)
          context.become(requesting(r.callback))
        case Some(t) =>
          t match {
            case OnError(cause) => r.callback(Left(cause))
            case _ => r.callback(Right(None))
          }
      }
  }

  def requesting(callback: Callback[Option[A]]): Receive = {
    //
    // Messages from upstream
    //
    case OnNext(elem) =>
      callback(Right(Some(elem.asInstanceOf[A])))
      context.become(waiting)
    case OnError(cause) =>
      callback(Left(cause))
    case OnComplete =>
      callback(Right(None))
  }

  override def receive = waiting
}

