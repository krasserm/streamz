package streamz.akka.stream

import akka.actor.{PoisonPill, Props}
import akka.stream.actor.ActorSubscriberMessage.{OnComplete, OnError, OnNext}
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.Request
import streamz.akka.stream.AdapterPublisher.AcknowledgeCallback

import scala.reflect.{ClassTag, classTag}

private[stream] class AdapterPublisher[A : ClassTag](strategyFactory: RequestStrategyFactory) extends Adapter(strategyFactory)
  with ActorPublisher[A] {

  override type Receiver = ActorPublisher[A]

  final val AckSuccess = Right(())

  var ackCallback: Option[AcknowledgeCallback] = None
  var currentDemand: Int = 0
  private var endOfStream = false

  override def receiveElement = {
    case OnNext((element: A, callback: AcknowledgeCallback)) =>
      enqueueSender(_.onNext(element))
      saveAckCallback(callback)

    case OnError(ex) if !endOfStream =>
      endOfStream = true
      enqueueSender { publisher => publisher.onError(ex); stopMe() }

    case OnComplete if !endOfStream =>
      endOfStream = true
      enqueueSender { publisher => publisher.onComplete(); stopMe() }
  }
  // allow override in test
  protected[stream] def stopMe() = self ! PoisonPill

  private def saveAckCallback(c: AcknowledgeCallback): Unit = {
    assert(ackCallback.isEmpty)
    ackCallback = Some(c)
  }

  override def receiveDemand = {
    case _: Request =>
  }

  protected override def requestUpstream() {
    currentDemand += requestStrategy.requestDemand(currentDemand)
    if(currentDemand > 0 && ackCallback.isDefined) {
      acknowledgeSuccess()
      currentDemand -= 1
    }
  }

  private def acknowledgeSuccess() {
    ackCallback.get(AckSuccess)
    ackCallback = None
  }

  override protected def receiver = if(totalDemand>0) Some(this) else None

  override protected def reduceDemand() = () // done by ActorPublisher
}

private[streamz] object AdapterPublisher {
  type AcknowledgeCallback = Either[Throwable, Unit] => Unit

  def props[A : ClassTag](strategyFactory: RequestStrategyFactory): Props =
    Props(classOf[AdapterPublisher[A]], strategyFactory, classTag[A]) // allows override in test
}
