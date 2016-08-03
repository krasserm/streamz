package streamz.akka

import akka.actor._
import akka.stream.Materializer
import akka.stream.actor.ActorSubscriberMessage._
import akka.stream.actor._
import akka.stream.scaladsl._
import fs2.Task
import fs2.{Strategy, Stream, pipe}
import org.reactivestreams.Publisher
import streamz.akka.stream.AdapterSubscriber.{Callback, Request}

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

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
                                     (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext, flowMaterializer: Materializer): Stream[Task, O] = {
    Stream.bracket[Task, ActorRef, O] {
      val adapterProps = AdapterSubscriber.props[O](strategyFactory)
      val adapterActor = name.fold(actorRefFactory.actorOf(adapterProps))(actorRefFactory.actorOf(adapterProps, _))
      flow.runWith(Sink.fromSubscriber(ActorSubscriber(adapterActor)))
      Task.delay(adapterActor)
    }(
      { adapterActor =>
        val requester = Task.async((callback: Callback[O]) => adapterActor ! Request(callback))
        Stream.repeatEval(requester).through(pipe.unNoneTerminate)
      },
      adapterActor => Task.delay(adapterActor ! PoisonPill)
    )
  }

  /**
   * Creates a process that publishes to the managed flow which is passed as argument to `f`.
   */
  def publish[I : ClassTag, Mat, O](process: Stream[Task, I],
                                    strategyFactory: RequestStrategyFactory = maxInFlightStrategyFactory(10),
                                    name: Option[String] = None)
                                   (f: Source[I, akka.NotUsed] => RunnableGraph[Mat])
                                   (m: Mat => Unit = (_: Mat) => ())
                                   (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext, materializer: Materializer): Stream[Task, Unit] =
  {
    val (processWithAdapterSink, publisherSink) = publisher(process, strategyFactory, name)
    m(f(Source.fromPublisher(publisherSink)).run())
    processWithAdapterSink
  }

  /**
   * Creates a process and a publisher from which un-managed downstream flows can be constructed.
   */
  def publisher[O : ClassTag](process: Stream[Task, O],
                              strategyFactory: RequestStrategyFactory = maxInFlightStrategyFactory(10),
                              name: Option[String] = None)
                             (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): (Stream[Task, Unit], Publisher[O]) = {

    val adapterProps = AdapterPublisher.props[O](strategyFactory)
    val adapter = name.fold(actorRefFactory.actorOf(adapterProps))(actorRefFactory.actorOf(adapterProps, _))
    val outStream =
      {
        val pusher = (i: O) => Stream.eval(Task.async[Unit](callback => adapter ! OnNext(i, callback))(strategy))
        process.flatMap(pusher).onError { ex =>
          adapter ! OnError(ex)
          Stream(())
        }.onComplete {
          adapter ! OnComplete
          Stream(())
        }
      }

    (outStream, ActorPublisher(adapter))
  }

  implicit class StreamSyntax[I : ClassTag](self: Stream[Task,I]) {
    def publish[O, Mat](strategyFactory: RequestStrategyFactory = maxInFlightStrategyFactory(10),
                        name: Option[String] = None)
                       (f: Source[I, akka.NotUsed] => RunnableGraph[Mat])
                       (m: Mat => Unit = (_: Mat) => ())
                       (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext, materializer: Materializer): Stream[Task, Unit] =
      outer.publish(self, strategyFactory)(f)(m)

    def publisher(strategyFactory: RequestStrategyFactory = maxInFlightStrategyFactory(10),
                  name: Option[String] = None)
                 (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): (Stream[Task, Unit], Publisher[I]) =
      outer.publisher(self, strategyFactory, name)
  }

  implicit class FlowSyntax[I, Mat, O : ClassTag](self: Source[O, Mat]) {
    def toStream(strategyFactory: RequestStrategyFactory = maxInFlightStrategyFactory(10),
                  name: Option[String] = None)
                (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext, flowMaterializer: Materializer): Stream[Task, O] =
      outer.subscribe(self, strategyFactory, name)
  }

  implicit private def strategy(implicit executionContext: ExecutionContext): Strategy = Strategy.fromExecutionContext(executionContext)
}
