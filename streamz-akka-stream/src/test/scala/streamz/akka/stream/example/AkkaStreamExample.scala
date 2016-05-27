package streamz.akka.stream.example

import akka.actor.ActorSystem
import akka.stream.scaladsl._
import akka.stream.ActorMaterializer

import scala.concurrent.ExecutionContext
import scalaz.concurrent.Task
import scalaz.stream._

import streamz.akka.stream._

object Context {
  implicit val system = ActorSystem("example")
  implicit val executionContext: ExecutionContext = system.dispatcher
  implicit val materializer = ActorMaterializer()
}

object ProcessToManagedFlow extends App {
  import Context._

  // Create process
  val p1: Process[Task, Int] = Process.emitAll(1 to 20)
  // Compose process with (managed) flow
  val sink = Sink.foreach(println)
  val p2: Process[Task, Unit] = p1.publish()
    // Customize Source (done when running process)
    { source => source.toMat(sink)(Keep.right) }
    // get result from sink (here used for cleanup)
    { materialized => materialized.onComplete(_ => system.terminate()) }

  // Run process
  p2.run.unsafePerformSync
}

object ProcessToUnManagedFlow extends App {
  import Context._

  // Create process
  val p1: Process[Task, Int] = Process.emitAll(1 to 20)
  // Create publisher (= process adapter)
  val (p2, publisher) = p1.publisher()
  // Create (un-managed) flow from publisher
  private val sink = Sink.foreach(println)
  val m = Source.fromPublisher(publisher).runWith(sink)
  // Run process
  p2.run.unsafePerformSync
  // use materialized result for cleanup
  m.onComplete(_ => system.terminate())
}

object FlowToProcess extends App {
  import Context._

  // Create flow
  val f1: Source[Int, akka.NotUsed] = Source(1 to 20)
  // Create process that subscribes to the flow
  val p1: Process[Task, Int] = f1.toProcess()
  // Run process
  p1.map(println).run.unsafePerformSync
  // When p1 is done, f1 must be done as well
  system.terminate()
}

object FlowToProcessToManagedFlow extends App {
  import Context._

  val f1: Source[Int, akka.NotUsed] = Source(1 to 20)
  val p1: Process[Task, Int] = subscribe(f1)
  val p2: Process[Task, Unit] = p1.publish() { flow =>
    flow.map(println).to(Sink.onComplete(_ => system.terminate())) // use sink for cleanup
  }() // and ignore sink's "result"
  p2.run.unsafePerformSync
}
