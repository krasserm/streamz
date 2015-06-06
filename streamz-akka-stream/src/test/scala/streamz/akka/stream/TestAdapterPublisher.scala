package streamz.akka.stream

import akka.actor.{Actor, Props}

import TestAdapter._

import scala.reflect.ClassTag


trait TestAdapter extends Actor with InFlight {

  abstract override def receive: Receive = super.receive.orElse {
    case GetInFlight => sender() ! inFlight
  }
}

object TestAdapter {
  case object GetInFlight
}

class TestAdapterPublisher[I : ClassTag](strategyFactory: RequestStrategyFactory)
  extends AdapterPublisher[I](strategyFactory) with TestAdapter {

  override protected[stream] def stopMe() = ()
}

object TestAdapterPublisher {
  def props[I : ClassTag](args: Seq[Any]): Props =
    Props(new TestAdapterPublisher[I](args.head.asInstanceOf[RequestStrategyFactory]))
}

class TestAdapterSubscriber[I: ClassTag](strategyFactory: RequestStrategyFactory)
  extends AdapterSubscriber[I](strategyFactory) with TestAdapter

object TestAdapterSubscriber {
  def props[I: ClassTag](args: Seq[Any]): Props =
    Props(new TestAdapterSubscriber[I](args.head.asInstanceOf[RequestStrategyFactory]))
}
