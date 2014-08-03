package streamz.akka.stream.example

import akka.actor.ActorSystem
import akka.stream.scaladsl.Flow
import akka.stream.{FlowMaterializer, MaterializerSettings}

import scalaz.concurrent.Task
import scalaz.stream._

import streamz.akka.stream._

object AkkaStreamExample extends App {
  implicit val system = ActorSystem(getClass.getSimpleName.filter(Character.isLetter))
  val materializer = FlowMaterializer(MaterializerSettings())

  // 1. Create Process
  val p: Process[Task, Int] = Process(1 to 20: _*)
  // 2. Create Adapter
  val (process, producer) = asProducer(p)
  // 3. Create Flow from returned Producer & materialize (to create demand)
  val produced = Flow(producer).foreach(println).onComplete(materializer)(_ => system.shutdown())
  // 4. run returned process
  process.run.run
}
