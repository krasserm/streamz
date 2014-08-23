package streamz.akka.stream.example

import akka.actor.ActorSystem
import akka.stream.scaladsl.Flow
import akka.stream.{FlowMaterializer, MaterializerSettings}

import scalaz.concurrent.Task
import scalaz.stream._

import streamz.akka.stream._

object Context {
  implicit val system = ActorSystem("example")
  implicit val materializer = FlowMaterializer(MaterializerSettings())
}

object ProcessToManagedFlow extends App {
  import Context._
  import system.dispatcher

  // Create process
  val p1: Process[Task, Int] = Process.emitAll(1 to 20)
  // Compose process with (managed) flow
  val p2: Process[Task, Unit] = p1.publish() { flow: Flow[Int] =>
    // Customize flow (done when running process)
    flow.foreach(println).onComplete(_ => system.shutdown())
  }
  // Run process
  p2.run.run
}

object ProcessToUnmanagedFlow extends App {
  import Context._
  import system.dispatcher

  // Create process
  val p1: Process[Task, Int] = Process.emitAll(1 to 20)
  // Create publisher (= process adapter)
  val (p2, publisher) = p1.publisher()
  // Create (un-managed) flow from publisher
  Flow(publisher).foreach(println).onComplete(_ => system.shutdown())
  // Run process
  p2.run.run
}

object FlowToProcess extends App {
  import Context._

  // Create flow
  val f1: Flow[Int] = Flow(1 to 20)
  // Create process that subscribes to the flow
  val p1: Process[Task, Int] = subscribe(f1)
  // Run process
  p1.runLog.run.foreach(println)
  system.shutdown()
}

object FlowToProcessToManagedFlow extends App {
  import Context._
  import system.dispatcher

  val f1: Flow[Int] = Flow(1 to 20)
  val p1: Process[Task, Int] = subscribe(f1)
  val p2: Process[Task, Unit] = p1.publish() { flow: Flow[Int] =>
    flow.foreach(println).onComplete(_ => system.shutdown())
  }
  p2.run.run
}
