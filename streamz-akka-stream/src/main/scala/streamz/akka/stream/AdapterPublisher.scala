package streamz.akka.stream

import akka.actor.{PoisonPill, Props}
import akka.stream.actor.ActorSubscriberMessage.{OnError, OnComplete, OnNext}
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.Request

import scala.reflect.{classTag, ClassTag}
import scalaz.\/
import scalaz.syntax.either._

private[stream] class AdapterPublisher[A : ClassTag](strategyFactory: RequestStrategyFactory) extends Adapter(strategyFactory)
  with ActorPublisher[A] {

  override type Receiver = ActorPublisher[A]

  type AcknowledgeCallback = \/[Throwable, Unit] => Unit
  final val AckSuccess = ().right

  var ackCallback: Option[AcknowledgeCallback] = None
  var currentDemand: Int = 0

  override def receiveElement = {
    case OnNext((element: A, callback: AcknowledgeCallback)) =>
      enqueueSender(_.onNext(element))
      saveAckCallback(callback)

    case OnError(ex) =>
      enqueueSender { publisher => publisher.onError(ex); stopMe() }

    case OnComplete =>
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

private[stream] object AdapterPublisher {
  def props[A : ClassTag](strategyFactory: RequestStrategyFactory): Props =
    Props(classOf[AdapterPublisher[A]], strategyFactory, classTag[A]) // allows override in test
}
