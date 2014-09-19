package streamz.akka

import akka.actor._
import akka.stream.actor._
import akka.stream.actor.ActorSubscriberMessage._
import akka.stream.scaladsl2._

import org.reactivestreams.Publisher

import scalaz.concurrent.Task
import scalaz.stream.Sink
import scalaz.stream._

package object stream { outer =>
  type RequestStrategyFactory = InFlight => RequestStrategy
  
  trait InFlight {
    def inFlight: Int
  }

  def maxInFlightStrategyFactory(max: Int): RequestStrategyFactory = inFlight => new MaxInFlightRequestStrategy(max) {
    override def inFlightInternally = inFlight.inFlight
  }

  /**
   * Creates a process that subscribes to the specified `flow`.
   */
  def subscribe[I, O](flow: FlowWithSource[I, O],
                      strategyFactory: RequestStrategyFactory = maxInFlightStrategyFactory(10))
                     (implicit actorRefFactory: ActorRefFactory, flowMaterializer: FlowMaterializer): Process[Task, O] =
    io.resource
    { Task.delay[ActorRef] {
      val adapterActor = actorRefFactory.actorOf(Props(new AdapterSubscriber[O](strategyFactory)))
      flow.withSink(SubscriberSink(ActorSubscriber(adapterActor))).run()
      adapterActor
    }}
    { adapterActor => Task.delay() } // cleanup done by stream OnComplete signal
    { adapterActor => Task.async(callback => adapterActor ! AdapterSubscriber.Read(callback)) }

  /**
   * Creates a process that publishes to the managed flow which is passed as argument to `f`.
   */
  def publish[A](process: Process[Task, A],
                 strategyFactory: RequestStrategyFactory = maxInFlightStrategyFactory(10),
                 name: Option[String] = None)
                (f: FlowWithSource[A, A] => Unit)
                (implicit actorRefFactory: ActorRefFactory): Process[Task, Unit] =
    process.to(adapterSink(AdapterPublisher.props[A](strategyFactory), name, f))

  /**
   * Creates a process and a publisher from which un-managed downstream flows can be constructed.
   */
  def publisher[O](process: Process[Task, O],
                   strategyFactory: RequestStrategyFactory = maxInFlightStrategyFactory(10),
                   name: Option[String] = None)
                  (implicit actorRefFactory: ActorRefFactory): (Process[Task, Unit], Publisher[O]) = {
    val adapterProps = AdapterPublisher.props[O](strategyFactory)
    val adapter = name.fold(actorRefFactory.actorOf(adapterProps))(actorRefFactory.actorOf(adapterProps, _))
    (process.to(adapterSink[O](adapter)), ActorPublisher[O](adapter))
  }

  implicit class StreamSyntax[A](self: Process[Task,A]) {
    def publish(strategyFactory: RequestStrategyFactory = maxInFlightStrategyFactory(10), name: Option[String] = None)
               (f: FlowWithSource[A, A] => Unit)
               (implicit actorRefFactory: ActorRefFactory): Process[Task, Unit] =
      outer.publish(self, strategyFactory)(f)(actorRefFactory)

    def publisher(strategyFactory: RequestStrategyFactory = maxInFlightStrategyFactory(10), name: Option[String] = None)
                 (implicit actorRefFactory: ActorRefFactory): (Process[Task, Unit], Publisher[A]) =
      outer.publisher(self, strategyFactory, name)(actorRefFactory)
  }

  private def adapterSink[A](adapterProps: Props, name: Option[String] = None, f: FlowWithSource[A, A] => Unit)
                            (implicit actorRefFactory: ActorRefFactory): Sink[Task, A] = adapterSink {
    val adapter = name.fold(actorRefFactory.actorOf(adapterProps))(actorRefFactory.actorOf(adapterProps, _))
    val publisher = ActorPublisher[A](adapter)
    f(FlowFrom(publisher))
    adapter
  }

  private def adapterSink[I](adapter: => ActorRef): Sink[Task,I] =
    io.resource[ActorRef, I => Task[Unit]]
    { Task.delay[ActorRef](adapter) }
    { adapterActor => Task.delay(adapterActor ! OnComplete) } // on error?
    { adapterActor => Task.delay(i => Task.async[Unit](callback => adapterActor ! OnNext(i, callback))) }
}
