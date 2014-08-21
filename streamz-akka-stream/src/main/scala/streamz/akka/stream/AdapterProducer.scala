package streamz.akka.stream

import scala.collection.immutable.Queue

import akka.actor.Props
import akka.stream.actor.ActorConsumer.{OnComplete, OnNext}
import akka.stream.actor.ActorProducer
import akka.stream.actor.ActorProducer.Request

import scalaz.\/
import scalaz.syntax.either._

private[stream] class AdapterProducer[A](strategyFactory: RequestStrategyFactory) extends ActorProducer[A] with InFlight {

  type AcknowledgeCallback = \/[Throwable, Unit] => Unit
  final val AckSuccess = ().right

  var elements: Queue[Option[A]] = Queue.empty
  var ackCallback: Option[AcknowledgeCallback] = None
  var currentDemand: Int = 0

  override def inFlight = elements.size

  override def receive = {
    case _: Request =>
      handleRequest()

    case OnNext((element: A, callback: AcknowledgeCallback)) =>
      enqueueElement(Some(element))
      safeAckCallback(callback)
      handleRequest()

    case OnComplete =>
      enqueueElement(None)
      handleRequest()
  }

  private def handleRequest() {
    sendElementsDownstream()
    requestDemandUpstream()
  }

  private def sendElementsDownstream(): Unit =
    while (totalDemand > 0 && elements.nonEmpty) dequeueElement().fold(onComplete())(onNext)

  private def requestDemandUpstream() {
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

  private def enqueueElement(element: Option[A]): Unit =
    elements = elements.enqueue(element)

  private def safeAckCallback(c: AcknowledgeCallback): Unit = {
    assert(!ackCallback.isDefined)
    ackCallback = Some(c)
  }

  private def dequeueElement(): Option[A] = {
    val (queued, newElements) = elements.dequeue
    elements = newElements
    queued
  }

  private val requestStrategy = strategyFactory(this)
}

private[stream] object AdapterProducer {
  def props[A](strategyFactory: RequestStrategyFactory): Props = Props(new AdapterProducer[A](strategyFactory))
}
