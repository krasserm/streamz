package streamz.akka.stream.example

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import fs2.Stream
import fs2.Task
import streamz.akka.stream._

import scala.concurrent.ExecutionContext

object Context {
  implicit val system = ActorSystem("example")
  implicit val executionContext: ExecutionContext = system.dispatcher
  implicit val materializer = ActorMaterializer()
}

object StreamToManagedFlow extends App {
  import Context._

  // Create stream
  val s1: Stream[Task, Int] = Stream.emits(1 to 20)
  // Compose stream with (managed) flow
  val sink = Sink.foreach(println)
  val s2: Stream[Task, Unit] = s1.publish()
    // Customize Source (done when running stream)
    { source => source.toMat(sink)(Keep.right) }
    // get result from sink (here used for cleanup)
    { materialized => materialized.onComplete(_ => system.terminate()) }

  // Run stream
  s2.run.unsafeRun
}

object StreamToUnManagedFlow extends App {
  import Context._

  // Create stream
  val s1: Stream[Task, Int] = Stream.emits(1 to 20)
  // Create publisher (= stream adapter)
  val (s2, publisher) = s1.publisher()
  // Create (un-managed) flow from publisher
  private val sink = Sink.foreach(println)
  val m = Source.fromPublisher(publisher).runWith(sink)
  // Run stream
  s2.run.unsafeRun
  // use materialized result for cleanup
  m.onComplete(_ => system.terminate())
}

object FlowToStream extends App {
  import Context._

  // Create flow
  val f1: Source[Int, akka.NotUsed] = Source(1 to 20)
  // Create stream that subscribes to the flow
  val s1: Stream[Task, Int] = f1.toStream()
  // Run stream
  s1.map(println).run.unsafeRun
  // When p1 is done, f1 must be done as well
  system.terminate()
}

object FlowToStreamToManagedFlow extends App {
  import Context._

  val f1: Source[Int, akka.NotUsed] = Source(1 to 20)
  val s1: Stream[Task, Int] = subscribe(f1)
  val s2: Stream[Task, Unit] = s1.publish() { flow =>
    flow.map(println).to(Sink.onComplete(_ => system.terminate())) // use sink for cleanup
  }() // and ignore sink's "result"
  s2.run.unsafeRun
}
