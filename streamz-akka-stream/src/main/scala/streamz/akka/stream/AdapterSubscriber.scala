package streamz.akka.stream

import akka.actor.Props
import akka.stream.actor._
import akka.stream.actor.ActorSubscriberMessage.{OnError, OnComplete, OnNext}

import scala.reflect.{ClassTag, classTag}

class AdapterSubscriber[A : ClassTag](strategyFactory: RequestStrategyFactory) extends Adapter(strategyFactory) with ActorSubscriber {
  import AdapterSubscriber._

  override type Receiver = Callback[A]

  private var callback: Option[Callback[A]] = None
  private var endOfStream = false

  def receiveElement: Receive = { // from upstream
    case OnNext(element: A) =>
      enqueueSender(cb => cb(Right(Some(element))))
    case OnComplete =>
      endOfStream = true
      enqueueSender(cb => cb(Right(None)))
    case OnError(cause) =>
      endOfStream = true
      enqueueSender(cb => cb(Left(cause)))
  }

  def receiveDemand: Receive = {
    case Request(cb: Callback[A]) =>
      callback = Some(cb)
  }

  override protected def receiver = callback
  override protected def reduceDemand() = callback = None
  override protected def requestUpstream() = () // done through callback
  override def inFlight: Int = super.inFlight - (if(endOfStream) 1 else 0)
}

object AdapterSubscriber {
  type Callback[A] = Either[Throwable, Option[A]] => Unit
  case class Request[A](callback: Callback[A])

  def props[A: ClassTag](strategyFactory: RequestStrategyFactory): Props =
    Props(classOf[AdapterSubscriber[A]], strategyFactory, classTag[A]) // allows override in test
}