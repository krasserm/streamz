package streamz.example

import akka.actor.ActorSystem

import fs2.Task
import fs2.Stream

import streamz.akka.camel._

object CamelExample {
  implicit val system = ActorSystem("example")

  val s: Stream[Task, Unit] = // receive from endpoint
  receive[String]("seda:q1")
    // in-only message exchange with endpoint and continue stream with in-message
    .sendW("seda:q3")
    // in-only message exchange with endpoint and continue stream with out-message
    .request[Int]("bean:service?method=length")
    // in-only message exchange with endpoint
    .send("seda:q2")

  // create concurrent task from process
  val t: Task[Unit] = s.run

  // run task (side effects only here) ...
  t.unsafeRun

  val s1: Stream[Task, String] = receive[String]("seda:q1")
  val s2: Stream[Task, Unit] = s1.send("seda:q2")
  val s3: Stream[Task, String] = s1.sendW("seda:q3")
  val s4: Stream[Task, Int] = s1.request[Int]("bean:service?method=length")
}
