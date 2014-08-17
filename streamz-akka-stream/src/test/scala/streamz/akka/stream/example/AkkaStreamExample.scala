package streamz.akka.stream.example

import akka.actor.ActorSystem
import akka.stream.scaladsl.Flow
import akka.stream.{FlowMaterializer, MaterializerSettings}

import scalaz.concurrent.Task
import scalaz.stream._

import streamz.akka.stream._

object AkkaStreamExample1 extends App {
  implicit val system = ActorSystem("example")
  val materializer = FlowMaterializer(MaterializerSettings())

  // Create process
  val p1: Process[Task, Int] = Process.emitAll(1 to 20)
  // Compose process with (managed) flow
  val p2: Process[Task, Unit] = p1.produce() { flow: Flow[Int] =>
    // Customize flow (done when running process)
    flow.foreach(println).onComplete(materializer)(_ => system.shutdown())
  }
  // Run process to feed flow
  p2.run.run
}

object AkkaStreamExample2 extends App {
  implicit val system = ActorSystem("example")
  val materializer = FlowMaterializer(MaterializerSettings())

  // Create process
  val p1: Process[Task, Int] = Process.emitAll(1 to 20)
  // Create producer (= process adapter)
  val (p2, producer) = p1.producer()
  // Create (un-managed) flow from producer & materialize (to create demand)
  val produced = Flow(producer).foreach(println).onComplete(materializer)(_ => system.shutdown())
  // Run process to feed flow
  p2.run.run
}
