package streamz.akka.stream.example

import akka.actor.ActorSystem
import akka.stream.scaladsl2._
import akka.stream.MaterializerSettings

import scala.concurrent.ExecutionContext
import scalaz.concurrent.Task
import scalaz.stream._

import streamz.akka.stream._

object Context {
  implicit val system = ActorSystem("example")
  implicit val executionContext: ExecutionContext = system.dispatcher
  implicit val materializer = FlowMaterializer(MaterializerSettings(system))
}

object ProcessToManagedFlow extends App {
  import Context._

  // Create process
  val p1: Process[Task, Int] = Process.emitAll(1 to 20)
  // Compose process with (managed) flow
  val sink = ForeachSink(println)
  val p2: Process[Task, Unit] = p1.publish()
    // Customize flow (done when running process)
    { flow => flow.withSink(sink) }
    // get result from sink (here used for cleanup)
    { materialized => sink.future(materialized).onComplete(_ => system.shutdown()) }

  // Run process
  p2.run.run
}

object ProcessToUnManagedFlow extends App {
  import Context._

  // Create process
  val p1: Process[Task, Int] = Process.emitAll(1 to 20)
  // Create publisher (= process adapter)
  val (p2, publisher) = p1.publisher()
  // Create (un-managed) flow from publisher
  private val sink = ForeachSink[Int](println)
  val m = FlowFrom(publisher).withSink(sink).run()
  // Run process
  p2.run.run
  // use sink's result for cleanup
  sink.future(m).onComplete(_ => system.shutdown())
}

object FlowToProcess extends App {
  import Context._

  // Create flow
  val f1: FlowWithSource[Int, Int] = FlowFrom(1 to 20)
  // Create process that subscribes to the flow
  val p1: Process[Task, Int] = f1.toProcess()
  // Run process
  p1.map(println).run.run
  // When p1 is done, f1 must be done as well
  system.shutdown()
}

object FlowToProcessToManagedFlow extends App {
  import Context._

  val f1: FlowWithSource[Int, Int] = FlowFrom(1 to 20)
  val p1: Process[Task, Int] = subscribe(f1)
  val p2: Process[Task, Unit] = p1.publish() { flow =>
    flow.map(println).withSink(OnCompleteSink(_ => system.shutdown())) // use sink for cleanup
  }() // and ignore sink's "result"
  p2.run.run
}
