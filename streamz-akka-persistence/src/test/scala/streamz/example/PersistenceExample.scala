package streamz.example

import akka.actor.ActorSystem
import akka.persistence.Persistent

import scalaz.concurrent.Task
import scalaz.std.string._
import scalaz.stream.Process

import streamz.akka.persistence._

object PersistenceExample {
  implicit val system = ActorSystem("example")
  val p1: Process[Task, Persistent] = replay("processor-1")
  val p2: Process[Task, Persistent] = replay("processor-1", from = 3L)
  val p3: Process[Task, String] = p1.scan("")((acc, p) => acc + p.payload)
  val p4: Process[Task,String] = for {
    s @ Snapshot(meta, data) <- snapshot[String]("processor-1")
    state <- replay(meta.processorId, s.nextSequenceNr).scan(data)((acc, p) => acc + p.payload)
  } yield state
  val p5: Process[Task,Unit] = Process("a", "b", "c").journal("processor-2")
}
