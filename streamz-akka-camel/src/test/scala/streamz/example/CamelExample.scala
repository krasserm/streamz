package streamz.example

import akka.actor.ActorSystem

import scalaz.concurrent.Task
import scalaz.stream.Process

import streamz.akka.camel._

object CamelExample {
  implicit val system = ActorSystem("example")

  val p1: Process[Task,String] = receive[String]("seda:q1")
  val p2: Process[Task,Unit] = p1.send("seda:q2")
  val p3: Process[Task,String] = p1.sendW("seda:q3")
  val p4: Process[Task,Int] = p1.request[Int]("bean:service?method=length")
}
