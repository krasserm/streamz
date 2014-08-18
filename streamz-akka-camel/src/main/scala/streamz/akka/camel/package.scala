package streamz.akka

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.reflect.ClassTag

import akka.actor._
import akka.camel._
import akka.pattern.ask
import akka.util.Timeout

import scalaz._
import Scalaz._

import scalaz.concurrent._
import scalaz.stream._

package object camel {
  /**
   * Produces a discrete stream of message bodies received at the Camel endpoint identified by `uri`.
   * If needed, received message bodies are converted to type `O` using a Camel type converter.
   *
   * @param uri Camel endpoint URI.
   */
  def receive[O](uri: String)(implicit system: ActorSystem, CT: ClassTag[O]): Process[Task,O] = {
    class ConsumerEndpoint(val endpointUri: String, queue: scalaz.stream.async.mutable.Queue[O]) extends Consumer {
      def receive = {
        case msg: CamelMessage => queue.enqueueOne(msg.bodyAs(CT, camelContext)).run
      }
    }

    io.resource
    { Task.delay {
        val queue = async.unboundedQueue[O] // TODO: re-use system.dispatcher
        val endpoint = system.actorOf(Props(new ConsumerEndpoint(uri, queue)))
        (queue, queue.dequeue, endpoint)
    }}
    { case (q, p, e) => Task.delay { e ! PoisonPill; q.close }}
    { case (_, p, _) => p.toTask }
  }

  /**
   * A sink that initiates an in-only message exchange with the Camel endpoint identified by `uri`.
   *
   * @param uri Camel endpoint URI.
   */
  def sender[I](uri: String)(implicit system: ActorSystem): Sink[Task,I] = {
    io.resource
    { Task.delay(system.actorOf(Props(new ProducerEndpoint(uri) with Oneway))) }
    { p => Task.delay(p ! PoisonPill) }
    { p => Task.delay(i => Task.delay(p ! i)) }
  }

  /**
   * A channel that initiates an in-out message exchange with the Camel endpoint identified by `uri`.
   * If needed, received out message bodies are converted to type `O` using a Camel type converter.
   *
   * @param uri Camel endpoint URI.
   */
  def requestor[I,O](uri: String, timeout: FiniteDuration = 10.seconds)(implicit system: ActorSystem, CT: ClassTag[O]): Channel[Task,I,O] = {
    import system.dispatcher

    implicit val t = Timeout(timeout)
    implicit val c = CamelExtension(system).context

    io.resource
    { Task.delay(system.actorOf(Props(new ProducerEndpoint(uri)))) }
    { p => Task.delay(p ! PoisonPill) }
    { p => Task.delay(i => p.ask(i).mapTo[CamelMessage].map(_.bodyAs[O])) }
  }

  implicit class CamelSyntax[O](self: Process[Task,O]) {
    def request[O2](uri: String, timeout: FiniteDuration = 10.seconds)(implicit system: ActorSystem, CT: ClassTag[O2]): Process[Task,O2] =
      self.through(requestor[O,O2](uri, timeout))

    def send(uri:String)(implicit system: ActorSystem): Process[Task,Unit] =
      self.to(sender[O](uri))

    def sendW(uri: String)(implicit system: ActorSystem): Process[Task,O] = {
      self.flatMap(o => Process.tell(o) ++ Process.emitO(o)).drainW(sender[O](uri))
    }
  }

  private implicit def scalaFuture2scalazTask[T](sf: scala.concurrent.Future[T])(implicit ec: ExecutionContext): Task[T] = {
    Task.async { cb =>
      sf.onComplete {
        case scala.util.Success(v) => cb(v.right)
        case scala.util.Failure(e) => cb(e.left)
      }
    }
  }

  private class ProducerEndpoint(val endpointUri: String) extends Producer
}
