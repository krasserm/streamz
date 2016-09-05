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

object StreamToSink extends App {
  import Context._

  // Create stream
  val s1: Stream[Task, Int] = Stream.emits(1 to 20)
  // Compose stream with managed sink
  val s2: Stream[Task, Unit] = s1.publish()
    // Managed sink
    { Sink.foreach(println) }
    // Get materialized value from sink (here used for cleanup)
    { materialized => materialized.onComplete(_ => system.terminate()) }

  // Run stream
  s2.run.unsafeRun
}

object StreamToUnmanagedPublisher extends App {
  import Context._

  // Create stream
  val s1: Stream[Task, Int] = Stream.emits(1 to 20)
  // Create publisher (= stream adapter)
  val (s2, publisher) = s1.publisher()
  // Create (un-managed) graph from publisher
  private val sink = Sink.foreach(println)
  val m = Source.fromPublisher(publisher).runWith(sink)
  // Run stream
  s2.run.unsafeRun
  // use materialized result for cleanup
  m.onComplete(_ => system.terminate())
}

object SourceToStream extends App {
  import Context._

  // Create source
  val f1: Source[Int, akka.NotUsed] = Source(1 to 20)
  // Create stream that subscribes to the source
  val s1: Stream[Task, Int] = f1.toStream()
  // Run stream
  s1.map(println).run.unsafeRun
  // When s1 is done, f1 must be done as well
  system.terminate()
}

object SourceToStreamToSink extends App {
  import Context._

  val f1: Source[Int, akka.NotUsed] = Source(1 to 20)
  val s1: Stream[Task, Int] = subscribe(f1)
  val s2: Stream[Task, Unit] = s1.publish() {
    Flow[Int].map(println).to(Sink.onComplete(_ => system.terminate()))
  }()
  s2.run.unsafeRun
}
