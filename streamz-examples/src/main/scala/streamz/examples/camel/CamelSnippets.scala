/*
 * Copyright 2014 - 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package streamz.examples.camel

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._

import fs2.{ Strategy, Stream, Task }

import streamz.camel.{ StreamMessage, StreamContext }

import scala.concurrent.ExecutionContext.global

object CamelFs2DslSnippets {
  import streamz.camel.fs2.dsl._

  implicit val context = StreamContext()
  implicit val strategy = Strategy.fromExecutionContext(global)

  val s: Stream[Task, StreamMessage[Int]] =
    // receive stream message from endpoint
    receive[String]("seda:q1")
      // in-only message exchange with endpoint and continue stream with in-message
      .send("seda:q2")
      // in-out message exchange with endpoint and continue stream with out-message
      .request[Int]("bean:service?method=weight")

  // create task from stream
  val t: Task[Unit] = s.run

  // run task (side effects only here) ...
  t.unsafeRun

  val s1: Stream[Task, StreamMessage[String]] = receive[String]("seda:q1")
  val s2: Stream[Task, StreamMessage[String]] = s1.send("seda:q2")
  val s3: Stream[Task, StreamMessage[Int]] = s2.request[Int]("bean:service?method=weight")

  val s1b: Stream[Task, String] = receiveBody[String]("seda:q1")
  val s2b: Stream[Task, String] = s1b.send("seda:q2")
  val s3b: Stream[Task, Int] = s2b.request[Int]("bean:service?method=weight")
}

object CamelAkkaScalaDslSnippets extends App {
  import streamz.camel.akka.scaladsl._

  implicit val system = ActorSystem("example")
  implicit val materializer = ActorMaterializer()
  implicit val context = StreamContext()

  val s: Source[StreamMessage[Int], NotUsed] =
    // receive stream message from endpoint
    receive[String]("seda:q1")
      // in-only message exchange with endpoint and continue stream with in-message
      .send("seda:q2")
      // in-out message exchange with endpoint and continue stream with out-message
      .request[Int]("bean:service?method=weight")

  // run stream (side effects only here) ...
  s.runForeach(println)

  val s1: Source[StreamMessage[String], NotUsed] = receive[String]("seda:q1")
  val s2: Source[StreamMessage[String], NotUsed] = s1.send("seda:q2", parallelism = 3)
  val s3: Source[StreamMessage[Int], NotUsed] = s2.request[Int]("bean:service?method=weight", parallelism = 3)

  val s2v = s1.via(send("seda:q2"))
  val s3v = s2.via(request[String, Int]("bean:service?method=weight"))

  val s1b: Source[String, NotUsed] = receiveBody[String]("seda:q1")
  val s2b: Source[String, NotUsed] = s1b.send("seda:q2")
  val s3b: Source[Int, NotUsed] = s2b.request[Int]("bean:service?method=weight")

  val f1: Flow[String, StreamMessage[String], NotUsed] = ???
  val f2: Flow[String, StreamMessage[String], NotUsed] = f1.send("seda:q2")
  val f3: Flow[String, StreamMessage[Int], NotUsed] = f2.request[Int]("bean:service?method=weight")

  val f1b: Flow[String, String, NotUsed] = ???
  val f2b: Flow[String, String, NotUsed] = f1b.send("seda:q2")
  val f3b: Flow[String, Int, NotUsed] = f2b.request[Int]("bean:service?method=weight")
}
