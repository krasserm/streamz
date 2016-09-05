package streamz.akka.stream.example

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._

import fs2.Stream
import fs2.Task

import scala.concurrent.ExecutionContext

import streamz.akka.stream._

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

object SourceToStream extends App {
  import Context._

  // Create source
  val f1: Source[Int, NotUsed] = Source(1 to 20)
  // Create stream that subscribes to the source
  val s1: Stream[Task, Int] = f1.subscribe()
  // Run stream
  s1.map(println).run.unsafeRun
  // When s1 is done, f1 must be done as well
  system.terminate()
}

object SourceToStreamToSink extends App {
  import Context._

  val f1: Source[Int, akka.NotUsed] = Source(1 to 20)
  val s1: Stream[Task, Int] = f1.subscribe()
  val s2: Stream[Task, Unit] = s1.publish() {
    Flow[Int].map(println).to(Sink.onComplete(_ => system.terminate()))
  }()
  s2.run.unsafeRun
}
