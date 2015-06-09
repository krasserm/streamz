package streamz.akka

import akka.actor._
import akka.persistence._

import scalaz._
import Scalaz._

import scalaz.concurrent._
import scalaz.stream._

package object persistence {
  /**
   * Produces a discrete stream of [[Persistent]] messages that are written by a [[PersistentActor]] identified
   * by `pid`.
   *
   * @param pid processor id.
   * @param from start sequence number.
   */
  def replay(pid: String, from: Long = 1L)(implicit system: ActorSystem): Process[Task, Event[Any]] =
    io.resource[Task, ActorRef, Event[Any]]
    { Task.delay(system.actorOf(Props(new EventReader(pid, from)))) }
    { r => Task.delay(system.stop(r)) }
    { r => Task.async(cb => r ! EventReader.Read(cb)) }

  /**
   * Produces the most recent [[Snapshot]] that has been taken by a [[PersistentActor]] identified by `pid`. If
   * the processor hasn't taken any snapshot yet or the loaded snapshot is not of type `O` then the produced
   * [[Snapshot.data]] value is `Monoid[O].zero`.
   *
   * @param pid processor id.
   */
  def snapshot[O](pid:String)(implicit system: ActorSystem, M: Monoid[O]): Process[Task, Snapshot[O]] = {
    io.resource[Task, ActorRef, Snapshot[O]]
    { Task.delay(system.actorOf(Props(new akka.persistence.SnapshotReader))) }
    { r => Task.delay(r ! akka.actor.PoisonPill) }
    { r => Task.async[Option[SelectedSnapshot]](cb => r ! SnapshotReader.Read(pid,cb)).map {
      case Some(ss) => Snapshot(ss.metadata, ss.snapshot.asInstanceOf[O]) // FIXME: check type
      case None     => Snapshot(SnapshotMetadata(pid, 0L, 0L), M.zero)
    }}.once
  }

  /**
   * A sink that writes to `system`'s journal using specified `persistenceId`.
   */
  def journaler[I](persistenceId: String)(implicit system: ActorSystem): Sink[Task,I] = {
    io.resource
    { Task.delay(system.actorOf(Props(new JournalWriter(persistenceId)))) }
    { r => Task.async(cb => r ! JournalWriter.Stop(cb)) }
    { r => Task.delay(m => Task.delay(r ! m)) }
  }

  case class Snapshot[A](metadata: SnapshotMetadata, data: A) {
    def nextSequenceNr: Long = metadata.sequenceNr + 1L
  }

  implicit class PersistenceSyntax[O](p: Process[Task,O]) {
    def journal(pid: String)(implicit system: ActorSystem):Process[Task,Unit] =
      p.to(journaler(pid))
  }

  private class JournalWriter(val persistenceId: String) extends PersistentActor {
    import JournalWriter._

    def receiveCommand = {
      case Stop(cb) => defer(cb) { cb =>
        // ensures that actor is stopped after Stop has
        // been looped through journal i.e. all previous
        // messages have been persisted
        context.stop(self)
        cb(().right)
      }
      case p => persistAsync(p) { _ =>
        // written
      }
    }

    def receiveRecover = {
      case _ =>
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
