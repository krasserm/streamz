package streamz.example

import akka.actor.ActorSystem

import scalaz.std.string._
import fs2.Stream
import fs2.Task
import streamz.akka.persistence._

object PersistenceExample {
  implicit val system = ActorSystem("example")
  val s1: Stream[Task, Event[Any]] = replay("processor-1")
  val s2: Stream[Task, Event[Any]] = replay("processor-1", from = 3L)
  val s3: Stream[Task, String] = s1.scan("")((acc, evt) => acc + evt.data)
  val s4: Stream[Task, String] = for {
    s @ Snapshot(md, data) <- snapshot[String]("processor-1")
    currentState           <- replay(md.persistenceId, s.nextSequenceNr).scan(data)((acc, evt) => acc + evt.data)
  } yield currentState
  val s5: Stream[Task, Unit] = Stream("a", "b", "c").journal("processor-2")
}
