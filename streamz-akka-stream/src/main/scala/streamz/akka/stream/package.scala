package streamz.akka

import akka.actor._
import akka.stream.Materializer
import akka.stream.actor._
import akka.stream.actor.ActorSubscriberMessage._
import akka.stream.scaladsl._

import org.reactivestreams.Publisher

import scala.reflect.ClassTag
import scalaz.concurrent.Task
import scalaz.stream.Process.Halt
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
  def subscribe[I, O : ClassTag, Mat](flow: Source[O, Mat],
                                      strategyFactory: RequestStrategyFactory = maxInFlightStrategyFactory(10),
                                      name: Option[String] = None)
                                     (implicit actorRefFactory: ActorRefFactory, flowMaterializer: Materializer): Process[Task, O] =
    io.resource[Task, ActorRef, O]
    { Task.delay[ActorRef] {
      val adapterProps = AdapterSubscriber.props[O](strategyFactory)
      val adapterActor = name.fold(actorRefFactory.actorOf(adapterProps))(actorRefFactory.actorOf(adapterProps, _))
      flow.runWith(Sink.fromSubscriber(ActorSubscriber(adapterActor)))
      adapterActor
    }}
    { adapterActor => Task.delay(adapterActor ! PoisonPill) }
    { adapterActor => Task.async(callback => adapterActor ! AdapterSubscriber.Request[O](callback)) }

  /**
   * Creates a process that publishes to the managed flow which is passed as argument to `f`.
   */
  def publish[I : ClassTag, Mat, O](process: Process[Task, I],
                                    strategyFactory: RequestStrategyFactory = maxInFlightStrategyFactory(10),
                                    name: Option[String] = None)
                                   (f: Source[I, akka.NotUsed] => RunnableGraph[Mat])
                                   (m: Mat => Unit = (_: Mat) => ())
                                   (implicit actorRefFactory: ActorRefFactory, materializer: Materializer): Process[Task, Unit] =
  {
    val (processWithAdapterSink, publisherSink) = publisher(process, strategyFactory, name)
    m(f(Source.fromPublisher(publisherSink)).run())
    processWithAdapterSink
  }

  /**
   * Creates a process and a publisher from which un-managed downstream flows can be constructed.
   */
  def publisher[O : ClassTag](process: Process[Task, O],
                              strategyFactory: RequestStrategyFactory = maxInFlightStrategyFactory(10),
                              name: Option[String] = None)
                             (implicit actorRefFactory: ActorRefFactory): (Process[Task, Unit], Publisher[O]) = {
    val adapterProps = AdapterPublisher.props[O](strategyFactory)
    val adapter = name.fold(actorRefFactory.actorOf(adapterProps))(actorRefFactory.actorOf(adapterProps, _))
    (process.onHalt {
      case cause@Cause.End =>
        adapter ! OnComplete
        Halt(cause)
      case cause@Cause.Kill =>
        adapter ! OnError(new Exception("processed killed")) // Test missing
        Halt(cause)
      case cause@Cause.Error(ex) =>
        adapter ! OnError(ex)
        Halt(cause)
    }.to(adapterSink[O](adapter)), ActorPublisher(adapter))
  }

  implicit class ProcessSyntax[I : ClassTag](self: Process[Task,I]) {
    def publish[O, Mat](strategyFactory: RequestStrategyFactory = maxInFlightStrategyFactory(10),
                        name: Option[String] = None)
                       (f: Source[I, akka.NotUsed] => RunnableGraph[Mat])
                       (m: Mat => Unit = (_: Mat) => ())
                       (implicit actorRefFactory: ActorRefFactory, materializer: Materializer): Process[Task, Unit] =
      outer.publish(self, strategyFactory)(f)(m)

    def publisher(strategyFactory: RequestStrategyFactory = maxInFlightStrategyFactory(10),
                  name: Option[String] = None)
                 (implicit actorRefFactory: ActorRefFactory): (Process[Task, Unit], Publisher[I]) =
      outer.publisher(self, strategyFactory, name)
  }

  implicit class FlowSyntax[I, Mat, O : ClassTag](self: Source[O, Mat]) {
    def toProcess(strategyFactory: RequestStrategyFactory = maxInFlightStrategyFactory(10),
                  name: Option[String] = None)
                 (implicit actorRefFactory: ActorRefFactory, flowMaterializer: Materializer): Process[Task, O] =
      outer.subscribe(self, strategyFactory, name)
  }

  private def adapterSink[I, Mat, O](adapterProps: Props,
                                     name: Option[String] = None,
                                     f: Source[I, akka.NotUsed] => RunnableGraph[Mat],
                                     m: Mat => Unit)
                                    (implicit actorRefFactory: ActorRefFactory, materializer: Materializer): Sink[Task, I] =
    adapterSink {
      val adapter = name.fold(actorRefFactory.actorOf(adapterProps))(actorRefFactory.actorOf(adapterProps, _))
      val publisher = ActorPublisher[I](adapter)
      val materialized = f(Source.fromPublisher(publisher)).run()
      m(materialized)
      adapter
    }

  private def adapterSink[I](adapter: => ActorRef): Sink[Task,I] = {
    io.resource[Task, ActorRef, I => Task[Unit]]
    { Task.delay[ActorRef](adapter) }
    { adapterActor => Task.delay(()) }
    { adapterActor => Task.delay(i => Task.async[Unit](callback => adapterActor ! OnNext(i, callback))) }
  }
}
