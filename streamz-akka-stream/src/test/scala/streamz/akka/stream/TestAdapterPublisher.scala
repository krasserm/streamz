package streamz.akka.stream

import scala.collection.immutable.Queue

import akka.actor.Props

import TestAdapterPublisher._


class TestAdapterPublisher[I](strategyFactory: RequestStrategyFactory) extends AdapterPublisher[I](strategyFactory) {
  override def receive = super.receive.orElse {
    case GetState => sender() ! State(elements, currentDemand)
  }
}

object TestAdapterPublisher {
  case object GetState

  case class State[I](elements: Queue[Option[I]], currentDemand: Int)

  def props[I](strategyFactory: RequestStrategyFactory): Props = Props(new TestAdapterPublisher[I](strategyFactory))
}
