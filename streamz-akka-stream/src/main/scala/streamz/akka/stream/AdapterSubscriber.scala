package streamz.akka.stream

import akka.stream.actor._

import scala.collection.immutable.Queue
import scalaz._
import Scalaz._
import scalaz.stream.Cause.{End, Terminated}

class AdapterSubscriber[A](strategyFactory: RequestStrategyFactory) extends ActorSubscriber with InFlight {
  import ActorSubscriberMessage._

  val requestStrategy = strategyFactory(this)
  var callback: Option[Throwable \/ A => Unit] = None
  var elements: Queue[Option[A]] = Queue.empty

  def inFlight: Int =
    elements.size

  def sendDownstream(): Unit = for {
    (elem, elems) <- elements.dequeueOption
    cb            <- callback
  } {
    elem match {
      case Some(e) => cb(e.right)
      case None    => cb(Terminated(End).left); context.stop(self)
    }
    callback = None
    elements = elems
  }

  def receive: Receive = {
    case OnNext(element: A) =>
      elements = elements.enqueue(Some(element))
      sendDownstream()
    case OnComplete =>
      elements = elements.enqueue(None)
    case OnError(cause) =>
      callback.foreach(_.apply(cause.left))
    case r: AdapterSubscriber.Read[A] =>
      callback = Some(r.callback)
      sendDownstream()
  }
}

object AdapterSubscriber {
  case class Read[A](callback: Throwable \/ A => Unit)
}