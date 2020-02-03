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

package streamz.converter

import akka.Done
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.{ Flow => AkkaFlow, Sink => AkkaSink, Source => AkkaSource, _ }
import akka.testkit._
import cats.effect.IO
import fs2.{ CompositeFailure, _ }
import org.scalatest._
import scala.collection.immutable.Seq
import scala.concurrent._
import scala.concurrent.duration._
import scala.util._
import cats.instances.try_._

object ConverterSpec {
  implicit class AwaitHelper[A](f: Future[A]) {
    def await: A = Await.result(f, 3.seconds)
  }

  val numbers: Seq[Int] = 1 to 10
  val error = new Exception("test")
}

class ConverterSpec extends TestKit(ActorSystem("test")) with WordSpecLike with Matchers with BeforeAndAfterAll {
  import ConverterSpec._

  private implicit val materializer = Materializer.createMaterializer(system)
  private implicit val dispatcher = system.dispatcher
  private implicit val contextShift = IO.contextShift(scala.concurrent.ExecutionContext.global)

  override def afterAll(): Unit = {
    materializer.shutdown()
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  private def expectError(run: => Any): Assertion =
    intercept[Exception](run).getMessage should be(error.getMessage)

  "An AS Source to FS2 Stream converter" must {
    //
    // AS Source (intern) -> FS2 Stream (extern)
    //
    "propagate elements and completion from source to stream" in {
      val source = AkkaSource(numbers)
      val stream = source.toStream[IO]

      stream.compile.toVector.unsafeRunSync() should be(numbers)
    }
    "propagate errors from source to stream" in {
      val source = AkkaSource(numbers) ++ AkkaSource.failed(error)
      val stream = source.toStream[IO]

      expectError(stream.compile.drain.unsafeRunSync())
    }
    "propagate early termination from stream to source" in {
      val probe = TestProbe()
      val source = AkkaSource(numbers).watchTermination()(Keep.right)
      val stream = Stream.force(source.toStreamMat[IO].map {
        case (akkaStream, mat) =>
          mat.onComplete(probe.ref ! _)
          akkaStream.take(3)
      })

      stream.compile.toVector.unsafeRunSync() should be(numbers.take(3))
      probe.expectMsg(Success(Done))
    }
    "propagate errors from stream to source" in {
      val probe = TestProbe()
      val source = AkkaSource(numbers).watchTermination()(Keep.right)
      val stream = Stream.force(source.toStreamMat[IO].map {
        case (akkaStream, mat) =>
          mat.onComplete(probe.ref ! _)
          akkaStream
      }) ++ Stream.raiseError[IO](error)

      expectError(stream.compile.drain.unsafeRunSync())
      probe.expectMsg(Success(Done))
    }
  }

  "An AS Sink to FS2 Pipe converter" must {
    //
    // FS2 Sink (extern) -> AS Sink (intern)
    //
    "propagate elements and completion from FS2 sink to AS sink" in {
      val probe = TestProbe()
      val akkaSink = AkkaSink.seq[Int]
      val fs2Sink = akkaSink.toPipeMat[IO].map {
        case (akkaStream, mat) =>
          mat.onComplete(probe.ref ! _)
          akkaStream
      }.unsafeRunSync()

      Stream.emits(numbers).through(fs2Sink).compile.drain.unsafeRunSync()
      probe.expectMsg(Success(numbers))
    }
    "propagate errors from FS2 sink to AS sink" in {
      val probe = TestProbe()
      val akkaSink = AkkaSink.seq[Int]
      val fs2Sink = akkaSink.toPipeMat[IO].map {
        case (akkaStream, mat) =>
          mat.onComplete(probe.ref ! _)
          akkaStream
      }.unsafeRunSync()

      expectError(Stream.raiseError[IO](error).through(fs2Sink).compile.drain.unsafeRunSync())
      probe.expectMsg(Failure(error))
    }
    "propagate early termination from AS sink to FS2 sink (using Mat Future)" in {
      val akkaSink = AkkaFlow[Int].take(3).toMat(AkkaSink.seq)(Keep.right)
      val fs2Sink = akkaSink.toPipeMatWithResult[IO, Try].unsafeRunSync()

      val result = Stream.emits(numbers).through(fs2Sink).compile.lastOrError.unsafeRunSync()
      result shouldBe Success(numbers.take(3))

    }
    "propagate early termination from AS sink (due to errors) to FS2 sink" in {
      val akkaSink = AkkaSink.foreach[Int](_ => throw error)
      val fs2Sink = akkaSink.toPipeMatWithResult[IO, Try].unsafeRunSync()

      val result = Stream.emits(numbers).through(fs2Sink).compile.lastOrError.unsafeRunSync()
      result shouldBe Failure(error)
    }
  }

  "An AS flow to FS2 pipe converter" must {
    //
    // FS2 Pipe (extern) <-> AS Flow (intern)
    //
    "propagate processing from pipe to flow (1:1)" in {
      val flow = AkkaFlow[Int].map(_ + 1)
      val pipe = flow.toPipe[IO]

      Stream.emits(numbers).through(pipe).compile.toVector.unsafeRunSync() should be(numbers.map(_ + 1))
    }
    "propagate processing from pipe to flow (m:n)" in {
      def logic(i: Int): Seq[Int] = i match {
        case 3 => Seq(3, 3, 3)
        case 7 => Seq()
        case _ => Seq(i)
      }
      val flow = AkkaFlow[Int].mapConcat(logic)
      val pipe = flow.toPipe[IO]

      Stream.emits(numbers).through(pipe).compile.toVector.unsafeRunSync() should be(numbers.flatMap(logic))
    }
    "propagate errors from pipe to flow" in {
      val probe = TestProbe()
      val flow = AkkaFlow[Int].map(_ + 1).recover { case e: Exception if e.getMessage == error.getMessage => probe.ref ! Failure(error) }
      val pipe = flow.toPipe[IO]

      Stream.raiseError[IO](error).through(pipe).compile.drain.attempt.unsafeRunSync() match {
        case Left(`error`) => succeed
        case Left(e: CompositeFailure) => e.head match {
          case `error` => succeed
          case other => fail(other)
        }
        case Left(other) => fail(other)
        case Right(()) => succeed
      }
      probe.expectMsg(Failure(error))
    }
    "propagate errors from flow to pipe" in {
      def logic(i: Int): Seq[Int] = i match {
        case 7 => throw error
        case _ => Seq(i)
      }
      val flow = AkkaFlow[Int].mapConcat(logic)
      val pipe = flow.toPipe[IO]

      expectError(Stream.emits(numbers).through(pipe).compile.drain.unsafeRunSync())
    }
  }

  "An FS2 Stream to AS Source converter" must {
    //
    // FS2 Stream (intern) -> AS Source (extern)
    //
    "propagate elements and completion from stream to source" in {
      val probe = TestProbe()
      val stream = Stream.emits(numbers).onFinalize[IO](IO(probe.ref ! Success(Done)))
      val source = AkkaSource.fromGraph(stream.toSource)

      source.toMat(AkkaSink.seq)(Keep.right).run.await should be(numbers)
      probe.expectMsg(Success(Done))
    }
    "propagate errors from stream to source" in {
      val stream = Stream.raiseError[IO](error)
      val source = AkkaSource.fromGraph(stream.toSource)

      expectError(source.toMat(AkkaSink.seq)(Keep.right).run.await)
    }
    "propagate cancellation from source to stream (on source completion)" in {
      val probe = TestProbe()
      val stream = Stream.emits(numbers).onFinalize[IO](IO(probe.ref ! Success(Done)))
      val source = AkkaSource.fromGraph(stream.toSource)

      source.via(AkkaFlow[Int].take(3)).toMat(AkkaSink.seq)(Keep.right).run.await should be(numbers.take(3))
      probe.expectMsg(Success(Done))
    }
    "propagate cancellation from source to stream (on source error)" in {
      val probe = TestProbe()
      val stream = Stream.emits(numbers).onFinalize[IO](IO(probe.ref ! Success(Done)))
      val source = AkkaSource.fromGraph(stream.toSource)

      expectError(source.toMat(AkkaSink.foreach(_ => throw error))(Keep.right).run.await)
      probe.expectMsg(Success(Done))
    }
  }

  "An FS2 Sink to AS Sink converter" must {
    //
    // AS Sink (extern) -> FS2 Sink (intern)
    //

    def seqSink(probe: TestProbe): Pipe[IO, Int, Unit] =
      s => s.fold(Seq.empty[Int])(_ :+ _).map(probe.ref ! Success(_))
        .handleErrorWith(err => Stream.eval_(IO(probe.ref ! Failure(err))) ++ Stream.raiseError[IO](err))
        .onFinalize(IO(probe.ref ! Success(Done)))

    "propagate elements and completion from AS sink to FS2 sink" in {
      val probe = TestProbe()
      val fs2Sink = seqSink(probe)
      val akkaSink = AkkaSink.fromGraph(fs2Sink.toSink)

      AkkaSource(numbers).toMat(akkaSink)(Keep.right).run.await
      probe.expectMsg(Success(numbers))
      probe.expectMsg(Success(Done))
    }
    "propagate errors from AS sink to FS2 sink" in {
      val probe = TestProbe()
      val fs2Sink = seqSink(probe)
      val akkaSink = AkkaSink.fromGraph(fs2Sink.toSink)

      expectError(AkkaSource.failed(error).toMat(akkaSink)(Keep.right).run.await)
      probe.expectMsg(Failure(error))
    }
    "propagate cancellation from FS2 sink to AS sink (on FS2 sink completion)" in {
      val probe = TestProbe()
      val fs2Sink: Pipe[IO, Int, Unit] = s => seqSink(probe)(s.take(3))
      val akkaSink = AkkaSink.fromGraph(fs2Sink.toSink)

      AkkaSource(numbers).toMat(akkaSink)(Keep.right).run.await
      probe.expectMsg(Success(numbers.take(3)))
      probe.expectMsg(Success(Done))
    }
    "propagate cancellation from FS2 sink to AS sink (on FS2 sink error)" in {
      val probe = TestProbe()
      val fs2Sink: Pipe[IO, Int, Unit] = s => seqSink(probe)(s ++ Stream.raiseError[IO](error))
      val akkaSink = AkkaSink.fromGraph(fs2Sink.toSink)

      expectError(AkkaSource(numbers).toMat(akkaSink)(Keep.right).run.await)
      probe.expectMsg(Failure(error))
    }
  }

  "An FS2 pipe to AS flow to converter" must {
    //
    // AS Flow (intern) <-> FS2 Pipe (extern)
    //
    "propagate processing from flow to pipe (1:1)" in {
      val pip: Pipe[IO, Int, Int] = s => s.map(_ + 1)
      val flow = AkkaFlow.fromGraph(pip.toFlow)

      AkkaSource(numbers).via(flow).toMat(AkkaSink.seq[Int])(Keep.right).run.await should be(numbers.map(_ + 1))
    }
    "propagate processing from flow to pipe (m:n)" in {
      def logic(i: Int): Seq[Int] = i match {
        case 3 => Seq(3, 3, 3)
        case 7 => Seq()
        case _ => Seq(i)
      }
      val pip: Pipe[IO, Int, Int] = s => s.flatMap(i => Stream.emits(logic(i)))
      val flow = AkkaFlow.fromGraph(pip.toFlow)

      AkkaSource(numbers).via(flow).toMat(AkkaSink.seq[Int])(Keep.right).run.await should be(numbers.flatMap(logic))
    }
    "propagate errors from flow to pipe" in {
      val probe = TestProbe()
      val pip: Pipe[IO, Int, Int] = s => s.handleErrorWith(err => { probe.ref ! Failure(err); Stream.raiseError[IO](err) })
      val flow = AkkaFlow.fromGraph(pip.toFlow)

      expectError(AkkaSource.failed(error).via(flow).toMat(AkkaSink.seq[Int])(Keep.right).run.await)
      probe.expectMsg(Failure(error))
    }
    "propagate errors from pipe to flow" in {
      val pip: Pipe[IO, Int, Int] = _ => Stream.raiseError[IO](error)
      val flow = AkkaFlow.fromGraph(pip.toFlow)

      expectError(AkkaSource(numbers).via(flow).toMat(AkkaSink.seq[Int])(Keep.right).run.await)
    }
  }
}
