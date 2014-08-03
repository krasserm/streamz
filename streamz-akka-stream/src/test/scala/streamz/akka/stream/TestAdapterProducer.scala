package streamz.akka.stream

import scala.collection.immutable.Queue

import akka.actor.Props

import TestAdapterProducer._


class TestAdapterProducer[I](strategyFactory: RequestStrategyFactory) extends AdapterProducer[I](strategyFactory) {
  override def receive = super.receive.orElse {
    case GetState => sender() ! State(elements, currentDemand)
  }
}

object TestAdapterProducer {
  case object GetState

  case class State[I](elements: Queue[Option[I]], currentDemand: Int)

  def props[I](strategyFactory: RequestStrategyFactory): Props = Props(new TestAdapterProducer[I](strategyFactory))
}
