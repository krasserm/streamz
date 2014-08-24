package streamz.akka.stream

import akka.actor.{Actor, Props}

import TestAdapter._


trait TestAdapter extends Actor with InFlight {

  abstract override def receive: Receive = super.receive.orElse {
    case GetInFlight => sender() ! inFlight
  }
}

object TestAdapter {
  case object GetInFlight
}

class TestAdapterPublisher[I](strategyFactory: RequestStrategyFactory)
  extends AdapterPublisher[I](strategyFactory) with TestAdapter {

  override protected[stream] def stopMe() = ()
}

object TestAdapterPublisher {
  def props[I](args: Seq[Any]): Props = Props(new TestAdapterPublisher[I](args(0).asInstanceOf[RequestStrategyFactory]))
}

class TestAdapterSubscriber[I](strategyFactory: RequestStrategyFactory)
  extends AdapterSubscriber[I](strategyFactory) with TestAdapter

object TestAdapterSubscriber {
  def props[I](args: Seq[Any]): Props = Props(new TestAdapterSubscriber[I](args(0).asInstanceOf[RequestStrategyFactory]))
}
