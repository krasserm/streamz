/*
 * Copyright 2014 - 2020 the original author or authors.
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

import cats.effect.IO
import fs2.Stream
import streamz.camel.{ StreamContext, StreamMessage }
import streamz.camel.fs2.dsl._

object Snippets {
  implicit val context = StreamContext()

  val s: Stream[IO, StreamMessage[Int]] =
    // receive stream message from endpoint
    receive[IO, String]("seda:q1")
      // in-only message exchange with endpoint and continue stream with in-message
      .send("seda:q2")
      // in-out message exchange with endpoint and continue stream with out-message
      .sendRequest[Int]("bean:service?method=weight")

  // create IO from stream
  val t: IO[Unit] = s.compile.drain

  // run IO (side effects only here) ...
  import cats.effect.unsafe.implicits.global // Normally taken as implicit param
  t.unsafeRunSync()

  val s1: Stream[IO, StreamMessage[String]] = receive[IO, String]("seda:q1")
  val s2: Stream[IO, StreamMessage[String]] = s1.send("seda:q2")
  val s3: Stream[IO, StreamMessage[Int]] = s2.sendRequest[Int]("bean:service?method=weight")

  val s1b: Stream[IO, String] = receiveBody[IO, String]("seda:q1")
  val s2b: Stream[IO, String] = s1b.send("seda:q2")
  val s3b: Stream[IO, Int] = s2b.sendRequest[Int]("bean:service?method=weight")
}
