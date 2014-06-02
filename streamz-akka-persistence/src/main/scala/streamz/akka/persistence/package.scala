package streamz.akka

import scala.reflect.ClassTag

import akka.actor._
import akka.persistence._

import scalaz._
import Scalaz._

import scalaz.concurrent._
import scalaz.stream._

package object persistence {
  def replay(pid: String, from: Long = 1L)(implicit system: ActorSystem): Process[Task, Persistent] =
    io.resource[ActorRef, Persistent]
    { Task.delay(system.actorOf(Props(new PersistentReader(pid, from)))) }
    { r => Task.delay(system.stop(r)) }
    { r => Task.async(cb => r ! PersistentReader.Read(cb)) }

  def snapshot[O](pid:String)(implicit system: ActorSystem, M: Monoid[O]): Process[Task,Snapshot[O]] = {
    io.resource[ActorRef,Snapshot[O]]
    { Task.delay(system.actorOf(Props(new akka.persistence.SnapshotReader))) }
    { r => Task.delay(r ! akka.actor.PoisonPill) }
    { r => Task.async[Option[SelectedSnapshot]](cb => r ! SnapshotReader.Read(pid,cb)).map {
      case Some(ss) => Snapshot(ss.metadata, ss.snapshot.asInstanceOf[O]) // FIXME: do not use asInstanceOf
      case None     => Snapshot(SnapshotMetadata(pid, 0L, 0L), M.zero)
    }}.once
  }

  def journaler[I](pid: String)(implicit system: ActorSystem): Sink[Task,I] = {
    io.resource
    { Task.delay(system.actorOf(Props(new JournalWriter(pid)))) }
    { r => Task.async(cb => r ! JournalWriter.Stop(cb)) }
    { r => Task.delay { m => Task.delay {
      m match {
        case p: Persistent => r ! p
        case o => r ! Persistent(o)
      }}
    }}
  }

  case class Snapshot[A](metadata: SnapshotMetadata, data: A) {
    def nextSequenceNr: Long = metadata.sequenceNr + 1L
  }

  implicit class PersistenceSyntax[O](p: Process[Task,O]) {
    def journal(uri: String)(implicit system: ActorSystem):Process[Task,Unit] =
      p.to(journaler(uri))
  }

  private class JournalWriter(override val processorId: String) extends Processor {
    import JournalWriter._

    def receive = {
      case p: Persistent =>
        // written
      case Stop(cb) =>
        // ensures that actor is stopped after Stop has
        // been looped through journal i.e. all previous
        // messages have been persisted
        context.stop(self)
        cb(().right)
    }
    override def preStart(): Unit =
      // recover last sequence number
      // but do not replay messages
      self ! Recover(replayMax = 0L)
  }

  private object JournalWriter {
    case class Stop(cb: Throwable \/ Unit => Unit)
  }
}
