package streamz.akka.stream

import akka.actor.Props
import akka.stream.actor._
import akka.stream.actor.ActorSubscriberMessage.{OnError, OnComplete, OnNext}

import scala.reflect.{classTag, ClassTag}
import scalaz.\/
import scalaz.syntax.either._
import scalaz.stream.Cause.{End, Terminated, Error}

class AdapterSubscriber[A : ClassTag](strategyFactory: RequestStrategyFactory) extends Adapter(strategyFactory)
    with ActorSubscriber {
  import AdapterSubscriber._

  override type Receiver = Callback[A]

  private var callback: Option[Callback[A]] = None

  def receiveElement: Receive = { // from upstream
    case OnNext(element: A) => enqueueSender(cb => cb(element.right))
    case OnComplete         => enqueueSender(cb => cb(Terminated(End).left))
    case OnError(cause)     => enqueueSender(cb => cb(Terminated(Error(cause)).left))
  }

  def receiveDemand: Receive = {
    case Request(cb: Callback[A]) => callback = Some(cb)
  }

  override protected def receiver = callback
  override protected def reduceDemand() = callback = None
  override protected def requestUpstream() = () // done through callback
}

object AdapterSubscriber {
  type Callback[A] = Throwable \/ A => Unit
  case class Request[A](callback: Callback[A])

  def props[A: ClassTag](strategyFactory: RequestStrategyFactory): Props =
    Props(classOf[AdapterSubscriber[A]], strategyFactory, classTag[A]) // allows override in test
}