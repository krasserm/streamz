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

import akka.stream._
import akka.stream.scaladsl.{ Flow => AkkaFlow, Sink => AkkaSink, Source => AkkaSource, _ }
import akka.{ Done, NotUsed }
import cats.effect._
import cats.effect.concurrent.Deferred
import cats.effect.implicits._
import cats.implicits._
import fs2._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

trait Converter {

  /**
   * Converts an Akka Stream [[Graph]] of [[SourceShape]] to an FS2 [[Stream]]. The [[Graph]] is materialized when
   * the [[Stream]]'s [[F]] in run. The materialized value can be obtained with the `onMaterialization` callback.
   */
  def akkaSourceToFs2Stream[F[_]: ContextShift, A, M](source: Graph[SourceShape[A], M])(onMaterialization: M => Unit)(implicit materializer: Materializer, F: Async[F]): Stream[F, A] =
    Stream.force {
      F.delay {
        val (mat, subscriber) = AkkaSource.fromGraph(source).toMat(AkkaSink.queue[A]())(Keep.both).run()
        onMaterialization(mat)
        subscriberStream[F, A](subscriber)
      }
    }

  /**
   * Converts an Akka Stream [[Graph]] of [[SinkShape]] to an FS2 [[Sink]]. The [[Graph]] is materialized when
   * the [[Sink]]'s [[F]] in run. The materialized value can be obtained with the `onMaterialization` callback.
   */
  def akkaSinkToFs2Pipe[F[_]: ContextShift, A, M](sink: Graph[SinkShape[A], M])(onMaterialization: M => Unit)(implicit materializer: Materializer, F: Concurrent[F]): Pipe[F, A, Unit] = { s =>
    Stream.force {
      F.delay {
        val (publisher, mat) = AkkaSource.queue[A](0, OverflowStrategy.backpressure).toMat(sink)(Keep.both).run()
        onMaterialization(mat)
        publisherStream[F, A](publisher, s)
      }
    }
  }

  /**
   * Converts an akka sink with a success-status-indicating Future[M]
   * materialized result into an fs2 Pipe which will fail if the Future fails.
   * The stream returned by this will emit the Future's value one time at the end,
   * then terminate.
   */
  def akkaSinkToFs2PipeMat[F[_]: ConcurrentEffect: ContextShift, A, M](akkaSink: Graph[SinkShape[A], Future[M]])(
    implicit
    ec: ExecutionContext,
    m: Materializer): Pipe[F, A, Either[Throwable, M]] = {
    val mkPromise = Deferred[F, Either[Throwable, M]]
    // `Pipe` is just a function of Stream[F, A] => Stream[F, B], so we take a stream as input.
    in =>
      Stream.eval(mkPromise).flatMap { p =>
        // Akka streams produce a materialized value as a side effect of being run.
        // streamz-converters allows us to have a `Future[Done] => Unit` callback when that materialized value is created.
        // This callback tells the akka materialized future to store its result status into the Promise
        val captureMaterializedResult: Future[M] => Unit = _.onComplete {
          case Failure(ex) => p.complete(Left(ex)).toIO.unsafeRunSync()
          case Success(value) => p.complete(Right(value)).toIO.unsafeRunSync()
        }
        // toSink is from streamz-converters; convert an akka sink to fs2 sink with a callback for the materialized values
        val fs2Sink: Pipe[F, A, Unit] =
          akkaSink.toPipe[F](captureMaterializedResult)

        val fs2Stream: Stream[F, Unit] = fs2Sink.apply(in)
        val materializedResultStream: Stream[F, Either[Throwable, M]] =
          // Async wait on the promise to be completed
          Stream.eval(p.get)
        // Run the akka sink for its effects and then run stream containing the effect of getting the Promise results
        fs2Stream.drain ++ materializedResultStream
      }
  }

  /**
   * Converts an Akka Stream [[Graph]] of [[FlowShape]] to an FS2 [[Pipe]]. The [[Graph]] is materialized when
   * the [[Pipe]]'s [[F]] in run. The materialized value can be obtained with the `onMaterialization` callback.
   */
  def akkaFlowToFs2Pipe[F[_]: ContextShift, A, B, M](flow: Graph[FlowShape[A, B], M])(onMaterialization: M => Unit)(implicit materializer: Materializer, F: Concurrent[F]): Pipe[F, A, B] = { s =>
    Stream.force {
      F.delay {
        val src = AkkaSource.queue[A](0, OverflowStrategy.backpressure)
        val snk = AkkaSink.queue[B]()
        val ((publisher, mat), subscriber) = src.viaMat(flow)(Keep.both).toMat(snk)(Keep.both).run()
        onMaterialization(mat)
        transformerStream[F, A, B](subscriber, publisher, s)
      }
    }
  }

  /**
   * Converts an FS2 [[Stream]] to an Akka Stream [[Graph]] of [[SourceShape]]. The [[Stream]] is run when the
   * [[Graph]] is materialized.
   */
  def fs2StreamToAkkaSource[F[_]: ContextShift, A](stream: Stream[F, A])(implicit F: ConcurrentEffect[F]): Graph[SourceShape[A], NotUsed] = {
    val source = AkkaSource.queue[A](0, OverflowStrategy.backpressure)
    // A sink that runs an FS2 publisherStream when consuming the publisher actor (= materialized value) of source
    val sink = AkkaSink.foreach[SourceQueueWithComplete[A]] { p =>
      // Fire and forget Future so it runs in the background
      publisherStream[F, A](p, stream).compile.drain.toIO.unsafeToFuture()
      ()
    }

    AkkaSource.fromGraph(GraphDSL.create(source) { implicit builder => source =>
      import GraphDSL.Implicits._
      builder.materializedValue ~> sink
      SourceShape(source.out)
    }).mapMaterializedValue(_ => NotUsed)
  }

  /**
   * Converts an FS2 [[Pipe]] to an Akka Stream [[Graph]] of [[SinkShape]]. The [[Sink]] is run when the
   * [[Graph]] is materialized.
   */
  def fs2PipeToAkkaSink[F[_]: ContextShift, A](sink: Pipe[F, A, Unit])(implicit F: Effect[F]): Graph[SinkShape[A], Future[Done]] = {
    val sink1: AkkaSink[A, SinkQueueWithCancel[A]] = AkkaSink.queue[A]()
    // A sink that runs an FS2 subscriberStream when consuming the subscriber actor (= materialized value) of sink1.
    // The future returned from unsafeToFuture() completes when the subscriber stream completes and is made
    // available as materialized value of this sink.
    val sink2: AkkaSink[SinkQueueWithCancel[A], Future[Done]] = AkkaFlow[SinkQueueWithCancel[A]]
      .map(s => subscriberStream[F, A](s).through(sink).compile.drain.toIO.as(Done: Done).unsafeToFuture())
      .toMat(AkkaSink.head)(Keep.right)
      .mapMaterializedValue(ffd => Async.fromFuture(Async.fromFuture(F.pure(ffd))).toIO.unsafeToFuture())
    // fromFuture dance above is because scala 2.11 lacks Future#flatten. `pure` instead of `delay`
    // because the future value is already strict by the time we get it.

    AkkaSink.fromGraph(GraphDSL.create(sink1, sink2)(Keep.both) { implicit builder => (sink1, sink2) =>
      import GraphDSL.Implicits._
      builder.materializedValue ~> AkkaFlow[(SinkQueueWithCancel[A], _)].map(_._1) ~> sink2
      SinkShape(sink1.in)
    }).mapMaterializedValue(_._2)
  }

  /**
   * Converts an FS2 [[Pipe]] to an Akka Stream [[Graph]] of [[FlowShape]]. The [[Pipe]] is run when the
   * [[Graph]] is materialized.
   */
  def fs2PipeToAkkaFlow[F[_]: ContextShift, A, B](pipe: Pipe[F, A, B])(implicit F: ConcurrentEffect[F]): Graph[FlowShape[A, B], NotUsed] = {
    val source = AkkaSource.queue[B](0, OverflowStrategy.backpressure)
    val sink1: AkkaSink[A, SinkQueueWithCancel[A]] = AkkaSink.queue[A]()
    // A sink that runs an FS2 transformerStream when consuming the publisher actor (= materialized value) of source
    // and the subscriber actor (= materialized value) of sink1
    val sink2 = AkkaSink.foreach[(SourceQueueWithComplete[B], SinkQueueWithCancel[A])] { ps =>
      // Fire and forget Future so it runs in the background
      F.toIO(transformerStream(ps._2, ps._1, pipe).compile.drain).unsafeToFuture()
      ()
    }

    AkkaFlow.fromGraph(GraphDSL.create(source, sink1)(Keep.both) { implicit builder => (source, sink1) =>
      import GraphDSL.Implicits._
      builder.materializedValue ~> sink2
      FlowShape(sink1.in, source.out)
    }).mapMaterializedValue(_ => NotUsed)
  }

  private def subscriberStream[F[_]: ContextShift, A](subscriber: SinkQueueWithCancel[A])(implicit F: Async[F]): Stream[F, A] = {
    val pull = Async.fromFuture(F.delay(subscriber.pull()))
    val cancel = F.delay(subscriber.cancel())
    Stream.repeatEval(pull).unNoneTerminate.onFinalize(cancel)
  }

  private def publisherStream[F[_]: ContextShift, A](publisher: SourceQueueWithComplete[A], stream: Stream[F, A])(implicit F: Concurrent[F]): Stream[F, Unit] = {
    def publish(a: A): F[Option[Unit]] = Async.fromFuture(F.delay(publisher.offer(a))).flatMap {
      case QueueOfferResult.Enqueued => ().some.pure[F]
      case QueueOfferResult.Failure(cause) => F.raiseError[Option[Unit]](cause)
      case QueueOfferResult.QueueClosed => none[Unit].pure[F]
      case QueueOfferResult.Dropped => F.raiseError[Option[Unit]](new IllegalStateException("This should never happen because we use OverflowStrategy.backpressure"))
    }.recover {
      // This handles a race condition between `interruptWhen` and `publish`.
      // There's no guarantee that, when the akka sink is terminated, we will observe the
      // `interruptWhen` termination before calling publish one last time.
      // Such a call fails with StreamDetachedException
      case _: StreamDetachedException => none[Unit]
    }

    def watchCompletion: F[Unit] = Async.fromFuture(F.delay(publisher.watchCompletion())).void
    def fail(e: Throwable): F[Unit] = F.delay(publisher.fail(e)) >> watchCompletion
    def complete: F[Unit] = F.delay(publisher.complete()) >> watchCompletion

    stream.interruptWhen(watchCompletion.attempt).evalMap(publish).unNoneTerminate
      .onFinalizeCase {
        case ExitCase.Completed | ExitCase.Canceled => complete
        case ExitCase.Error(e) => fail(e)
      }
  }

  private def transformerStream[F[_]: ContextShift: Concurrent, A, B](subscriber: SinkQueueWithCancel[B], publisher: SourceQueueWithComplete[A], stream: Stream[F, A]): Stream[F, B] =
    subscriberStream[F, B](subscriber).concurrently(publisherStream[F, A](publisher, stream))

  private def transformerStream[F[_]: ContextShift: Concurrent, A, B](subscriber: SinkQueueWithCancel[A], publisher: SourceQueueWithComplete[B], pipe: Pipe[F, A, B]): Stream[F, Unit] =
    subscriberStream[F, A](subscriber).through(pipe).through(s => publisherStream(publisher, s))
}

trait ConverterDsl extends Converter {

  implicit class AkkaSourceDsl[A, M](source: Graph[SourceShape[A], M]) {

    /** @see [[Converter#akkaSourceToFs2Stream]] */
    def toStream[F[_]: ContextShift: Async](onMaterialization: M => Unit = _ => ())(implicit materializer: Materializer): Stream[F, A] =
      akkaSourceToFs2Stream(source)(onMaterialization)
  }

  implicit class AkkaSinkDsl[A, M](sink: Graph[SinkShape[A], M]) {

    /** @see [[Converter#akkaSinkToFs2Pipe]] */
    def toPipe[F[_]: Concurrent: ContextShift](onMaterialization: M => Unit = _ => ())(implicit materializer: Materializer): Pipe[F, A, Unit] =
      akkaSinkToFs2Pipe(sink)(onMaterialization)

  }

  implicit class AkkaSinkMatDsl[A, M](sink: Graph[SinkShape[A], Future[M]]) {
    def toPipeMat[F[_]: ConcurrentEffect: ContextShift](implicit materializer: Materializer, ec: ExecutionContext): Pipe[F, A, Either[Throwable, M]] =
      akkaSinkToFs2PipeMat[F, A, M](sink)

  }

  implicit class AkkaFlowDsl[A, B, M](flow: Graph[FlowShape[A, B], M]) {

    /** @see [[Converter#akkaFlowToFs2Pipe]] */
    def toPipe[F[_]: ContextShift: ConcurrentEffect](onMaterialization: M => Unit = _ => ())(implicit materializer: Materializer): Pipe[F, A, B] =
      akkaFlowToFs2Pipe(flow)(onMaterialization)
  }

  implicit class FS2StreamNothingDsl[A](stream: Stream[Nothing, A]) {

    /** @see [[Converter#fs2StreamToAkkaSource]] */
    @deprecated("Use `stream.covary[F].toSource` instead", "0.10")
    def toSource(implicit contextShift: ContextShift[IO]): Graph[SourceShape[A], NotUsed] =
      fs2StreamToAkkaSource(stream: Stream[IO, A])
  }

  implicit class FS2StreamPureDsl[A](stream: Stream[Pure, A]) {

    /** @see [[Converter#fs2StreamToAkkaSource]] */
    @deprecated("Use `stream.covary[F].toSource` instead", "0.10")
    def toSource(implicit contextShift: ContextShift[IO]): Graph[SourceShape[A], NotUsed] =
      fs2StreamToAkkaSource(stream: Stream[IO, A])
  }

  implicit class FS2StreamIODsl[F[_]: ContextShift: ConcurrentEffect, A](stream: Stream[F, A]) {

    /** @see [[Converter#fs2StreamToAkkaSource]] */
    def toSource: Graph[SourceShape[A], NotUsed] =
      fs2StreamToAkkaSource(stream)
  }

  implicit class FS2SinkPureDsl[A](sink: Pipe[Pure, A, Unit]) {

    /** @see [[Converter#fs2PipeToAkkaSink]] */
    @deprecated("Use `pipe.covary[F].toSink` instead", "0.10")
    def toSink(implicit contextShift: ContextShift[IO]): Graph[SinkShape[A], Future[Done]] =
      fs2PipeToAkkaSink(sink.covary[IO])
  }

  implicit class FS2SinkIODsl[F[_]: Effect: ContextShift, A](sink: Pipe[F, A, Unit]) {
    /** @see [[Converter#fs2PipeToAkkaSink]] */
    def toSink: Graph[SinkShape[A], Future[Done]] =
      fs2PipeToAkkaSink(sink)
  }

  implicit class FS2PipePureDsl[A, B](pipe: Pipe[Pure, A, B]) {

    /** @see [[Converter#fs2PipeToAkkaFlow]] */
    @deprecated("Use `(pipe: Pipe[F, A, B]).toFlow` instead", "0.10")
    def toFlow(implicit contextShift: ContextShift[IO]): Graph[FlowShape[A, B], NotUsed] =
      fs2PipeToAkkaFlow(pipe: Pipe[IO, A, B])
  }

  implicit class FS2PipeIODsl[F[_]: ContextShift: ConcurrentEffect, A, B](pipe: Pipe[F, A, B]) {

    /** @see [[Converter#fs2PipeToAkkaFlow]] */
    def toFlow: Graph[FlowShape[A, B], NotUsed] =
      fs2PipeToAkkaFlow(pipe)
  }
}
