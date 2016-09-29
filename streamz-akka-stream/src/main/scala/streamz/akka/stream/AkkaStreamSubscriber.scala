package streamz.akka.stream

import akka.actor.Props
import akka.stream.actor._
import akka.stream.actor.ActorSubscriberMessage._

import streamz.akka.stream.Converter.Callback

private [stream] object AkkaStreamSubscriber {
  case class Request[A](callback: Callback[Option[A]])

  def props[A]: Props = Props(new AkkaStreamSubscriber[A])
}

private [stream] class AkkaStreamSubscriber[A] extends ActorSubscriber {
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
        case None    =>
          request(1L)
          context.become(requesting(r.callback))
        case Some(t) =>
          t match {
            case OnError(cause) => r.callback(Left(cause))
            case _              => r.callback(Right(None))
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

