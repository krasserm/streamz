/*
 * Copyright 2014 - 2019 the original author or authors.
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

package streamz.examples.converter

import akka.actor.{ ActorRefFactory, ActorSystem }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Keep, Flow => AkkaFlow, Sink => AkkaSink, Source => AkkaSource }
import akka.{ Done, NotUsed }
import cats.effect.{ ContextShift, IO }
import fs2.{ Pipe, Pure, Stream }
import streamz.converter._

import scala.collection.immutable.Seq
import scala.concurrent._
import scala.concurrent.duration._

object Example extends App {
  val system: ActorSystem = ActorSystem("example")
  val factory: ActorRefFactory = system

  implicit val executionContext: ExecutionContext = factory.dispatcher
  implicit val materializer: ActorMaterializer = ActorMaterializer()(factory)
  implicit val contextShift: ContextShift[IO] = IO.contextShift(materializer.executionContext)

  val numbers: Seq[Int] = 1 to 10

  // --------------------------------
  //  Akka Stream to FS2 conversions
  // --------------------------------

  def f(i: Int) = List(s"$i-1", s"$i-2")

  val aSink1: AkkaSink[Int, Future[Done]] = AkkaSink.foreach[Int](println)
  val fSink1: Pipe[IO, Int, Unit] = aSink1.toPipe[IO]

  val aSource1: AkkaSource[Int, NotUsed] = AkkaSource(numbers)
  val fStream1: Stream[IO, Int] = aSource1.toStream[IO]

  val aFlow1: AkkaFlow[Int, String, NotUsed] = AkkaFlow[Int].mapConcat(f)
  val fPipe1: Pipe[IO, Int, String] = aFlow1.toPipe[IO]

  fStream1.through(fSink1).compile.drain.unsafeRunSync() // prints numbers
  assert(fStream1.compile.toVector.unsafeRunSync() == numbers)
  assert(fStream1.through(fPipe1).compile.toVector.unsafeRunSync() == numbers.flatMap(f))

  // --------------------------------
  //  FS2 to Akka Stream conversions
  // --------------------------------

  def g(i: Int) = i + 10

  val fSink2: Pipe[IO, Int, Unit] = s => s.map(g).evalMap(i => IO(println(i)))
  val aSink2: AkkaSink[Int, Future[Done]] = AkkaSink.fromGraph(fSink2.toSink)

  val fStream2: Stream[Pure, Int] = Stream.emits(numbers)
  val aSource2: AkkaSource[Int, NotUsed] = AkkaSource.fromGraph(fStream2.covary[IO].toSource)

  val fpipe2: Pipe[IO, Int, Int] = s => s.map(g)
  val aFlow2: AkkaFlow[Int, Int, NotUsed] = AkkaFlow.fromGraph(fpipe2.toFlow)

  aSource2.toMat(aSink2)(Keep.right).run() // prints numbers
  assert(Await.result(aSource2.toMat(AkkaSink.seq)(Keep.right).run(), 5.seconds) == numbers)
  assert(Await.result(aSource2.via(aFlow2).toMat(AkkaSink.seq)(Keep.right).run(), 5.seconds) == numbers.map(g))

  materializer.shutdown()
  system.terminate()
}
