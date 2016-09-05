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
   * Creates a stream that subscribes to the specified source.
   */
  def subscribe[I, O : ClassTag, Mat](source: Source[O, Mat],
                                      strategyFactory: RequestStrategyFactory = maxInFlightStrategyFactory(10),
                                      name: Option[String] = None)
                                     (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext, materializer: Materializer): Stream[Task, O] = {
    Stream.bracket[Task, ActorRef, O] {
      val adapterProps = AdapterSubscriber.props[O](strategyFactory)
      val adapterActor = name.fold(actorRefFactory.actorOf(adapterProps))(actorRefFactory.actorOf(adapterProps, _))
      source.toMat(Sink.fromSubscriber(ActorSubscriber(adapterActor)))(Keep.left).run()
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
   * Creates a stream that publishes to the managed sink.
   */
  def publish[O : ClassTag, Mat](stream: Stream[Task, O],
                                 strategyFactory: RequestStrategyFactory = maxInFlightStrategyFactory(10),
                                 name: Option[String] = None)
                                (sink: Sink[O, Mat])
                                (m: Mat => Unit = (_: Mat) => ())
                                (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext, materializer: Materializer): Stream[Task, Unit] =
  {
    val (streamWithAdapterSink, publisherSink) = publisher(stream, strategyFactory, name)
    m(Source.fromPublisher(publisherSink).toMat(sink)(Keep.right).run())
    streamWithAdapterSink
  }

  /**
   * Creates a stream and an un-managed publisher.
   */
  private[stream] def publisher[O : ClassTag](stream: Stream[Task, O],
                              strategyFactory: RequestStrategyFactory = maxInFlightStrategyFactory(10),
                              name: Option[String] = None)
                             (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): (Stream[Task, Unit], Publisher[O]) = {

    val adapterProps = AdapterPublisher.props[O](strategyFactory)
    val adapter = name.fold(actorRefFactory.actorOf(adapterProps))(actorRefFactory.actorOf(adapterProps, _))
    val outStream =
      {
        val pusher = (i: O) => Stream.eval(Task.async[Unit](callback => adapter ! OnNext(i, callback))(strategy))
        stream.flatMap(pusher).onError { ex =>
          adapter ! OnError(ex)
          Stream(())
        }.onFinalize[Task] {
          Task.delay(adapter ! OnComplete)
        }
      }

    (outStream, ActorPublisher(adapter))
  }

  implicit class StreamSyntax[I : ClassTag](self: Stream[Task,I]) {
    def publish[O, Mat](strategyFactory: RequestStrategyFactory = maxInFlightStrategyFactory(10),
                        name: Option[String] = None)
                       (sink: Sink[I, Mat])
                       (m: Mat => Unit = (_: Mat) => ())
                       (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext, materializer: Materializer): Stream[Task, Unit] =
      outer.publish(self, strategyFactory)(sink)(m)

    private[stream] def publisher(strategyFactory: RequestStrategyFactory = maxInFlightStrategyFactory(10),
                  name: Option[String] = None)
                 (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext): (Stream[Task, Unit], Publisher[I]) =
      outer.publisher(self, strategyFactory, name)
  }

  implicit class SourceSyntax[I, Mat, O : ClassTag](self: Source[O, Mat]) {
    def subscribe(strategyFactory: RequestStrategyFactory = maxInFlightStrategyFactory(10),
                  name: Option[String] = None)
                 (implicit actorRefFactory: ActorRefFactory, executionContext: ExecutionContext, materializer: Materializer): Stream[Task, O] =
      outer.subscribe(self, strategyFactory, name)
  }

  implicit private def strategy(implicit executionContext: ExecutionContext): Strategy = Strategy.fromExecutionContext(executionContext)
}
