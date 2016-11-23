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

package streamz.examples.akka

import akka.{ Done, NotUsed }
import akka.actor.{ ActorRefFactory, ActorSystem }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Flow => AkkaFlow, Keep, Sink => AkkaSink, Source => AkkaSource }

import fs2.{ Pipe, Pure, Sink, Stream, Task, pipe }

import streamz.akka._

import scala.collection.immutable.Seq
import scala.concurrent._
import scala.concurrent.duration._

object ConverterExample extends App {
  val system: ActorSystem = ActorSystem("example")
  val factory: ActorRefFactory = system

  implicit val executionContext: ExecutionContext = factory.dispatcher
  implicit val materializer: ActorMaterializer = ActorMaterializer()(factory)

  val numbers: Seq[Int] = 1 to 10

  // --------------------------------
  //  Akka Stream to FS2 conversions
  // --------------------------------

  def f(i: Int) = List(s"$i-1", s"$i-2")

  val aSink1: AkkaSink[Int, Future[Done]] = AkkaSink.foreach[Int](println)
  val fSink1: Sink[Task, Int] = aSink1.toSink()

  val aSource1: AkkaSource[Int, NotUsed] = AkkaSource(numbers)
  val fStream1: Stream[Task, Int] = aSource1.toStream()

  val aFlow1: AkkaFlow[Int, String, NotUsed] = AkkaFlow[Int].mapConcat(f)
  val fPipe1: Pipe[Task, Int, String] = aFlow1.toPipe()

  fStream1.to(fSink1).run.unsafeRun() // prints numbers
  assert(fStream1.runLog.unsafeRun() == numbers)
  assert(fStream1.through(fPipe1).runLog.unsafeRun() == numbers.flatMap(f))

  // --------------------------------
  //  FS2 to Akka Stream conversions
  // --------------------------------

  def g(i: Int) = i + 10

  val fSink2: Sink[Pure, Int] = s => pipe.lift(g)(s).map(println)
  val aSink2: AkkaSink[Int, Future[Done]] = AkkaSink.fromGraph(fSink2.toSink)

  val fStream2: Stream[Pure, Int] = Stream.emits(numbers)
  val aSource2: AkkaSource[Int, NotUsed] = AkkaSource.fromGraph(fStream2.toSource)

  val fpipe2: Pipe[Pure, Int, Int] = pipe.lift[Pure, Int, Int](g)
  val aFlow2: AkkaFlow[Int, Int, NotUsed] = AkkaFlow.fromGraph(fpipe2.toFlow)

  aSource2.toMat(aSink2)(Keep.right).run() // prints numbers
  assert(Await.result(aSource2.toMat(AkkaSink.seq)(Keep.right).run(), 5.seconds) == numbers)
  assert(Await.result(aSource2.via(aFlow2).toMat(AkkaSink.seq)(Keep.right).run(), 5.seconds) == numbers.map(g))

  system.terminate()
}
