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
  def publish[I, O](process: Process[Task, I],
                    strategyFactory: RequestStrategyFactory = maxInFlightStrategyFactory(10),
                    name: Option[String] = None)
                   (f: FlowWithSource[I, I] => RunnableFlow[I, O])
                   (m: MaterializedFlow => Unit = _ => ())
                   (implicit actorRefFactory: ActorRefFactory, materializer: FlowMaterializer): Process[Task, Unit] =
    process.to(adapterSink(AdapterPublisher.props[I](strategyFactory), name, f, m))

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

  implicit class ProcessSyntax[I](self: Process[Task,I]) {
    def publish[O](strategyFactory: RequestStrategyFactory = maxInFlightStrategyFactory(10), name: Option[String] = None)
                  (f: FlowWithSource[I, I] => RunnableFlow[I,O])
                  (m: MaterializedFlow => Unit = _ => ())
                  (implicit actorRefFactory: ActorRefFactory, materializer: FlowMaterializer): Process[Task, Unit] =
      outer.publish(self, strategyFactory)(f)(m)

    def publisher(strategyFactory: RequestStrategyFactory = maxInFlightStrategyFactory(10), name: Option[String] = None)
                 (implicit actorRefFactory: ActorRefFactory): (Process[Task, Unit], Publisher[I]) =
      outer.publisher(self, strategyFactory, name)(actorRefFactory)
  }

  implicit class FlowSyntax[I, O](self: FlowWithSource[I, O]) {
    def toProcess(strategyFactory: RequestStrategyFactory = maxInFlightStrategyFactory(10))
                 (implicit actorRefFactory: ActorRefFactory, flowMaterializer: FlowMaterializer): Process[Task, O] =
      outer.subscribe(self, strategyFactory)
  }

  private def adapterSink[I, O](adapterProps: Props,
                                name: Option[String] = None,
                                f: FlowWithSource[I, I] => RunnableFlow[I, O],
                                m: MaterializedFlow => Unit)
                               (implicit actorRefFactory: ActorRefFactory, materializer: FlowMaterializer): Sink[Task, I] =
    adapterSink {
      val adapter = name.fold(actorRefFactory.actorOf(adapterProps))(actorRefFactory.actorOf(adapterProps, _))
      val publisher = ActorPublisher[I](adapter)
      val materializedFlow = f(FlowFrom(publisher)).run()
      m(materializedFlow)
      adapter
    }

  private def adapterSink[I](adapter: => ActorRef): Sink[Task,I] =
    io.resource[ActorRef, I => Task[Unit]]
    { Task.delay[ActorRef](adapter) }
    { adapterActor => Task.delay(adapterActor ! OnComplete) } // on error?
    { adapterActor => Task.delay(i => Task.async[Unit](callback => adapterActor ! OnNext(i, callback))) }
}
