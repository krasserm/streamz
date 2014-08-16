package streamz.example

import akka.actor.ActorSystem

import scalaz.concurrent.Task
import scalaz.std.string._
import scalaz.stream.Process

import streamz.akka.persistence._

object PersistenceExample {
  implicit val system = ActorSystem("example")
  val p1: Process[Task, Event[Any]] = replay("processor-1")
  val p2: Process[Task, Event[Any]] = replay("processor-1", from = 3L)
  val p3: Process[Task, String] = p1.scan("")((acc, evt) => acc + evt.data)
  val p4: Process[Task, String] = for {
    s @ Snapshot(md, data) <- snapshot[String]("processor-1")
    currentState           <- replay(md.persistenceId, s.nextSequenceNr).scan(data)((acc, evt) => acc + evt.data)
  } yield currentState
  val p5: Process[Task, Unit] = Process("a", "b", "c").journal("processor-2")
}
