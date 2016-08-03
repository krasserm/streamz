package streamz.akka

import akka.pattern.ask
import akka.actor._
import akka.camel._
import akka.util.Timeout
import fs2._
import fs2.async.mutable
import fs2.Task

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.reflect.ClassTag


package object camel {
  /**
   * Produces a discrete stream of message bodies received at the Camel endpoint identified by `uri`.
   * If needed, received message bodies are converted to type `O` using a Camel type converter.
   *
   * @param uri Camel endpoint URI.
   */
  def receive[O](uri: String)(implicit system: ActorSystem, CT: ClassTag[O]): Stream[Task,O] = {
    import system.dispatcher

    class ConsumerEndpoint(val endpointUri: String, queue: mutable.Queue[Task, O]) extends Consumer {
      def receive = {
        case msg: CamelMessage =>
          queue.enqueue1(msg.bodyAs(CT, camelContext)).unsafeRun
      }
    }
    Stream.bracket[Task,(mutable.Queue[Task, O], ActorRef), O] {
      async.unboundedQueue[Task, O].map { queue =>
        val endpoint = system.actorOf(Props(new ConsumerEndpoint(uri, queue)))
        (queue, endpoint)
      }
    }(
      { case (q, _) => q.dequeue },
      { case (_, e) => Task.delay(e ! PoisonPill) }
      // TODO close queue? (https://github.com/functional-streams-for-scala/fs2/issues/433#issuecomment-193445929)
    )
  }

  /**
   * A sink that initiates an in-only message exchange with the Camel endpoint identified by `uri`.
   *
   * @param uri Camel endpoint URI.
   */
  def sender[I](uri: String)(implicit system: ActorSystem): Sink[Task, I] = { s =>
    Stream.bracket(
      Task.delay(system.actorOf(Props(new ProducerEndpoint(uri) with Oneway)))
    )(
      endpoint => s.map(i => endpoint ! i),
      endpoint => Task.delay(endpoint ! PoisonPill)
    )
  }

  /**
   * A channel that initiates an in-out message exchange with the Camel endpoint identified by `uri`.
   * If needed, received out message bodies are converted to type `O` using a Camel type converter.
   *
   * @param uri Camel endpoint URI.
   */
  def requestor[I,O](uri: String, timeout: FiniteDuration = 10.seconds)(implicit system: ActorSystem, CT: ClassTag[O]): Pipe[Task,I,O] = { s =>

    implicit val t = Timeout(timeout)
    implicit val c = CamelExtension(system).context
    implicit val ec = system.dispatcher

    Stream.bracket(
      Task.delay(system.actorOf(Props(new ProducerEndpoint(uri))))
    )(
      p => s.flatMap(i => Stream.eval(scalaFuture2scalazTask(p.ask(i).mapTo[CamelMessage].map(_.bodyAs[O])))),
      p => Task.delay(p ! PoisonPill)
    )
  }

  implicit class CamelSyntax[O](self: Stream[Task,O]) {
    def request[O2](uri: String, timeout: FiniteDuration = 10.seconds)(implicit system: ActorSystem, CT: ClassTag[O2]): Stream[Task,O2] =
      self.through(requestor[O,O2](uri, timeout))

    def send(uri:String)(implicit system: ActorSystem): Stream[Task,Unit] =
      self.through(sender[O](uri))

    def sendW(uri: String)(implicit system: ActorSystem): Stream[Task, O] = {
      implicit val strategy = Strategy.fromExecutionContext(system.dispatcher)
      self.observe(sender[O](uri))
    }
  }

  private implicit def scalaFuture2scalazTask[T](sf: scala.concurrent.Future[T])(implicit ec: ExecutionContext): Task[T] = {
    Task.async { cb =>
      sf.onComplete {
        case scala.util.Success(v) => cb(Right(v))
        case scala.util.Failure(e) => cb(Left(e))
      }
    }
  }

  private implicit def strategy(implicit ec: ExecutionContext): Strategy = Strategy.fromExecutionContext(ec)

  private class ProducerEndpoint(val endpointUri: String) extends Producer
}
