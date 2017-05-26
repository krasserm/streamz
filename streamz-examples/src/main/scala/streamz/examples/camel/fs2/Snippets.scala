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

package streamz.examples.camel.fs2

import fs2.{ Stream, Task }

import streamz.camel.{ StreamMessage, StreamContext }
import streamz.camel.fs2.dsl._

object Snippets {
  implicit val context = StreamContext()

  val s: Stream[Task, StreamMessage[Int]] =
    // receive stream message from endpoint
    receive[String]("seda:q1")
      // in-only message exchange with endpoint and continue stream with in-message
      .send("seda:q2")
      // in-out message exchange with endpoint and continue stream with out-message
      .sendRequest[Int]("bean:service?method=weight")

  // create task from stream
  val t: Task[Unit] = s.run

  // run task (side effects only here) ...
  t.unsafeRun

  val s1: Stream[Task, StreamMessage[String]] = receive[String]("seda:q1")
  val s2: Stream[Task, StreamMessage[String]] = s1.send("seda:q2")
  val s3: Stream[Task, StreamMessage[Int]] = s2.sendRequest[Int]("bean:service?method=weight")

  val s1b: Stream[Task, String] = receiveBody[String]("seda:q1")
  val s2b: Stream[Task, String] = s1b.send("seda:q2")
  val s3b: Stream[Task, Int] = s2b.sendRequest[Int]("bean:service?method=weight")
}
