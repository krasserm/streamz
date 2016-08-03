package streamz.akka

import akka.actor._
import akka.persistence._
import fs2.{Sink, Strategy, Stream}
import fs2.Task

import scalaz._

package object persistence {
  /**
   * Produces a discrete stream of events that are written by a [[PersistentActor]] identified
   * by `pid`.
   *
   * @param pid processor id.
   * @param from start sequence number.
   */
  def replay(pid: String, from: Long = 1L)(implicit system: ActorSystem): Stream[Task, Event[Any]] = {
    Stream.bracket[Task, ActorRef, Event[Any]] {
     Task.delay(system.actorOf(Props(new EventReader(pid, from))))
    }(
      { r =>
        val reader = Task.async[Event[Any]](cb => r ! EventReader.Read(cb))
        Stream.repeatEval(reader)
      },
      r => Task.delay(system.stop(r))
    )
  }

  /**
    * Produces the most recent [[Snapshot]] that has been taken by a [[PersistentActor]] identified by `pid`. If
    * the processor hasn't taken any snapshot yet or the loaded snapshot is not of type `O` then the produced
   * [[Snapshot.data]] value is `Monoid[O].zero`.
   *
   * @param pid processor id.
   */
  def snapshot[O](pid:String)(implicit system: ActorSystem, M: Monoid[O]): Stream[Task, Snapshot[O]] = {
    Stream.bracket[Task, ActorRef, Snapshot[O]](
      Task.delay(system.actorOf(Props(new akka.persistence.SnapshotReader)))
    )(
      { r =>
        val reader = Task.async[Option[SelectedSnapshot]](cb => r ! SnapshotReader.Read(pid,cb)).map {
          case Some(ss) => Snapshot(ss.metadata, ss.snapshot.asInstanceOf[O]) // FIXME: check type
          case None     => Snapshot(SnapshotMetadata(pid, 0L, 0L), M.zero)
        }
        Stream.eval(reader)
      },
      r => Task.delay(r ! akka.actor.PoisonPill)
    )
  }

  /**
   * A sink that writes to `system`'s journal using specified `persistenceId`.
   */
  def journaler[I](persistenceId: String)(implicit system: ActorSystem): Sink[Task, I] = { s =>
    Stream.bracket[Task, ActorRef, Unit](
      Task.delay(system.actorOf(Props(new JournalWriter(persistenceId))))
    )(
      w => s.flatMap(i => Stream.eval(Task.delay(w ! i))),
      w => Task.async[Unit](cb => w ! JournalWriter.Stop(cb))
    )
  }

  case class Snapshot[A](metadata: SnapshotMetadata, data: A) {
    def nextSequenceNr: Long = metadata.sequenceNr + 1L
  }

  implicit class PersistenceSyntax[O](p: Stream[Task,O]) {
    def journal(pid: String)(implicit system: ActorSystem): Stream[Task,Unit] =
      p.through(journaler(pid))
  }

  implicit private def strategy(implicit system: ActorSystem): Strategy = Strategy.fromExecutionContext(system.dispatcher)

  private class JournalWriter(val persistenceId: String) extends PersistentActor {
    import JournalWriter._

    def receiveCommand = {
      case Stop(cb) => deferAsync(cb) { cb =>
        // ensures that actor is stopped after Stop has
        // been looped through journal i.e. all previous
        // messages have been persisted
        context.stop(self)
        cb(Right(()))
      }
      case p => persistAsync(p) { _ =>
        // written
      }
    }

    def receiveRecover = {
      case _ =>
    }

    // recover last sequence number
    // but do not replay messages
    override def recovery = Recovery(toSequenceNr = 0L)
  }

  private object JournalWriter {
    case class Stop(cb: Either[Throwable, Unit] => Unit)
  }
}
