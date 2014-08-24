package streamz.akka.stream

import akka.actor.Actor

import scala.collection.immutable.Queue

abstract class Adapter(strategyFactory: RequestStrategyFactory) extends Actor with InFlight {

  type Receiver
  type Sender = Receiver => Unit

  protected val requestStrategy = strategyFactory(this)
  private var senders: Queue[Sender] = Queue.empty

  override def receive = receiveElement.orElse(receiveDemand).andThen(_ => handleRequest())

  protected def receiveElement: Receive // from upstream
  protected def receiveDemand: Receive // from downstream

  private def handleRequest(): Unit = {
    sendDownstream()
    requestUpstream()
  }

  private def sendDownstream(): Unit = while(sendNext().isDefined) ()

  private def sendNext(): Option[Unit] = for {
    (sender, newSenders) <- senders.dequeueOption
    receiver <- receiver
  } yield {
    sender(receiver)
    senders = newSenders
    reduceDemand()
  }

  protected def receiver: Option[Receiver]
  protected def reduceDemand(): Unit

  protected def requestUpstream()

  protected def enqueueSender(sender: Sender): Unit = senders = senders.enqueue(sender)

  override def inFlight = senders.size
}
