package streamz.akka

import akka.actor._
import akka.stream.FlowMaterializer
import akka.stream.actor.ActorConsumer._
import akka.stream.actor.ActorProducer
import akka.stream.scaladsl.Flow
import org.reactivestreams.api.Producer

import scalaz.concurrent.Task
import scalaz.stream._

package object stream { outer =>
  type RequestStrategyFactory = InFlight => RequestStrategy
  
  trait InFlight {
    def inFlight: Int
  }

  def maxInFlightStrategyFactory(max: Int): RequestStrategyFactory = inFlight => new MaxInFlightRequestStrategy(max) {
    override def inFlightInternally = inFlight.inFlight
  }

  def consume[O](flow: Flow[O])(implicit actorRefFactory: ActorRefFactory, flowMaterializer: FlowMaterializer): Process[Task, O] = ???

  def produce[O](process: Process[Task, O], strategyFactory: RequestStrategyFactory = maxInFlightStrategyFactory(10))(f: Flow[O] => Unit)
                     (implicit actorRefFactory: ActorRefFactory): Process[Task, Unit] =
    process.to(adapterSink(AdapterProducer.props[O](strategyFactory), f))

  def producer[O](process: Process[Task, O], strategyFactory: RequestStrategyFactory = maxInFlightStrategyFactory(10), name: Option[String] = None)
                   (implicit actorRefFactory: ActorRefFactory):(Process[Task, Unit], Producer[O]) = {
    val adapterProps = AdapterProducer.props[O](strategyFactory)
    val actorRef = name.fold(actorRefFactory.actorOf(adapterProps))(actorRefFactory.actorOf(adapterProps, _))
    (process.to(adapterSink[O](actorRef)), ActorProducer[O](actorRef))
  }

  implicit class StreamSyntax[O](self: Process[Task,O]) {
    def produce(strategyFactory: RequestStrategyFactory = maxInFlightStrategyFactory(10), name: Option[String] = None)(f: Flow[O] => Unit)
               (implicit actorRefFactory: ActorRefFactory): Process[Task, Unit] =
      outer.produce(self, strategyFactory)(f)(actorRefFactory)

    def producer(strategyFactory: RequestStrategyFactory = maxInFlightStrategyFactory(10), name: Option[String] = None)
                (implicit actorRefFactory: ActorRefFactory):(Process[Task, Unit], Producer[O]) =
      outer.producer(self, strategyFactory, name)(actorRefFactory)
  }

  private def adapterSink[I](adapterProps: Props, f: Flow[I] => Unit)(implicit actorRefFactory: ActorRefFactory): Sink[Task, I] = adapterSink {
    val adapter = actorRefFactory.actorOf(adapterProps)
    val producer = ActorProducer[I](adapter)
    f(Flow(producer))
    adapter
  }

  private def adapterSink[I](adapter: => ActorRef): Sink[Task,I] = {
    io.resource[ActorRef, I => Task[Unit]]
    { Task.delay[ActorRef](adapter) }
    { adapterActor => Task.delay(adapterActor ! OnComplete) } // on error?
    { adapterActor => Task.delay(i => Task.async[Unit](callback => adapterActor ! OnNext(i, callback))) }
  }
}
