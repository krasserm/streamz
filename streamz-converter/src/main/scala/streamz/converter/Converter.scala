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

package streamz.converter

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }

import akka.stream._
import akka.stream.scaladsl.{ Flow => AkkaFlow, Sink => AkkaSink, Source => AkkaSource, _ }
import akka.{ Done, NotUsed }
import cats.effect._
import cats.effect.concurrent.Deferred
import cats.effect.implicits._
import cats.implicits._
import fs2._
import scala.annotation.implicitNotFound

trait Converter {

  /**
   * Converts an Akka Stream [[Graph]] of [[SourceShape]] to an FS2 [[Stream]].
   * If the materialized value needs be obtained, use [[akkaSourceToFs2StreamMat]].
   */
  def akkaSourceToFs2Stream[F[_]: Async: ContextShift, A](source: Graph[SourceShape[A], NotUsed])(implicit materializer: Materializer): Stream[F, A] =
    Stream.force {
      Async[F].delay {
        val subscriber = AkkaSource.fromGraph(source).toMat(AkkaSink.queue[A]())(Keep.right).run()
        subscriberStream[F, A](subscriber)
      }
    }

  /**
   * Converts an Akka Stream [[Graph]] of [[SourceShape]] to an FS2 [[Stream]]. This method returns the FS2 [[Stream]]
   * and the materialized value of the [[Graph]].
   */
  def akkaSourceToFs2StreamMat[F[_]: Async: ContextShift, A, M](source: Graph[SourceShape[A], M])(implicit materializer: Materializer): F[(Stream[F, A], M)] =
    Async[F].delay {
      val (mat, subscriber) = AkkaSource.fromGraph(source).toMat(AkkaSink.queue[A]())(Keep.both).run()
      (subscriberStream[F, A](subscriber), mat)
    }

  /**
   * Converts an Akka Stream [[Graph]] of [[SinkShape]] to an FS2 [[Pipe]].
   * If the materialized value needs be obtained, use [[akkaSinkToFs2PipeMat]].
   */
  def akkaSinkToFs2Pipe[F[_]: Concurrent: ContextShift, A](sink: Graph[SinkShape[A], NotUsed])(implicit materializer: Materializer): Pipe[F, A, Unit] =
    (s: Stream[F, A]) =>
      Stream.force {
        Async[F].delay {
          val publisher = AkkaSource.queue[A](0, OverflowStrategy.backpressure).toMat(sink)(Keep.left).run()
          publisherStream[F, A](publisher, s)
        }
      }

  /**
   * Converts an Akka Stream [[Graph]] of [[SinkShape]] to an FS2 [[Pipe]]. This method returns the FS2 [[Pipe]]
   * and the materialized value of the [[Graph]].
   */
  def akkaSinkToFs2PipeMat[F[_]: Concurrent: ContextShift, A, M](sink: Graph[SinkShape[A], M])(implicit materializer: Materializer): F[(Pipe[F, A, Unit], M)] =
    Concurrent[F].delay {
      val (publisher, mat) = AkkaSource.queue[A](0, OverflowStrategy.backpressure).toMat(sink)(Keep.both).run()
      ((s: Stream[F, A]) => publisherStream[F, A](publisher, s), mat)
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
    m: Materializer): F[Pipe[F, A, Either[Throwable, M]]] =
    for {
      promise <- Deferred[F, Either[Throwable, M]]
      fs2Sink <- akkaSinkToFs2PipeMat[F, A, Future[M]](akkaSink).flatMap {
        case (stream, mat) =>
          // This callback tells the akka materialized future to store its result status into the Promise
          val callback = ConcurrentEffect[F].delay(
            mat.onComplete {
              case Failure(ex) => promise.complete(ex.asLeft).toIO.unsafeRunSync()
              case Success(value) => promise.complete(value.asRight).toIO.unsafeRunSync()
            })
          callback.map(_ => stream)
      }
    } yield {
      in: Stream[F, A] =>
        {
          // Async wait on the promise to be completed
          val materializedResultStream = Stream.eval(promise.get)
          val fs2Stream: Stream[F, Unit] = fs2Sink.apply(in)

          // Run the akka sink for its effects and then run stream containing the effect of getting the Promise results
          fs2Stream.drain ++ materializedResultStream
        }
    }

  /**
   * Converts an Akka Stream [[Graph]] of [[FlowShape]] to an FS2 [[Pipe]].
   * If the materialized value needs be obtained, use [[akkaSinkToFs2PipeMat]].
   */
  def akkaFlowToFs2Pipe[F[_]: Concurrent: ContextShift, A, B](flow: Graph[FlowShape[A, B], NotUsed])(implicit materializer: Materializer): Pipe[F, A, B] =
    (s: Stream[F, A]) =>
      Stream.force {
        Concurrent[F].delay {
          val src = AkkaSource.queue[A](0, OverflowStrategy.backpressure)
          val snk = AkkaSink.queue[B]()
          val (publisher, subscriber) = src.viaMat(flow)(Keep.left).toMat(snk)(Keep.both).run()
          transformerStream[F, A, B](subscriber, publisher, s)
        }
      }

  /**
   * Converts an Akka Stream [[Graph]] of [[FlowShape]] to an FS2 [[Pipe]]. This method returns the FS2 [[Pipe]]
   * and the materialized value of the [[Graph]].
   */
  def akkaFlowToFs2PipeMat[F[_]: Concurrent: ContextShift, A, B, M](flow: Graph[FlowShape[A, B], M])(implicit materializer: Materializer): F[(Pipe[F, A, B], M)] =
    Concurrent[F].delay {
      val src = AkkaSource.queue[A](0, OverflowStrategy.backpressure)
      val snk = AkkaSink.queue[B]()
      val ((publisher, mat), subscriber) = src.viaMat(flow)(Keep.both).toMat(snk)(Keep.both).run()
      ((s: Stream[F, A]) => transformerStream[F, A, B](subscriber, publisher, s), mat)
    }

  /**
   * Converts an FS2 [[Stream]] to an Akka Stream [[Graph]] of [[SourceShape]]. The [[Stream]] is run when the
   * [[Graph]] is materialized.
   */
  def fs2StreamToAkkaSource[F[_]: ConcurrentEffect: ContextShift, A](stream: Stream[F, A]): Graph[SourceShape[A], NotUsed] = {
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
  def fs2PipeToAkkaSink[F[_]: ContextShift: Effect, A](sink: Pipe[F, A, Unit]): Graph[SinkShape[A], Future[Done]] = {
    val sink1: AkkaSink[A, SinkQueueWithCancel[A]] = AkkaSink.queue[A]()
    // A sink that runs an FS2 subscriberStream when consuming the subscriber actor (= materialized value) of sink1.
    // The future returned from unsafeToFuture() completes when the subscriber stream completes and is made
    // available as materialized value of this sink.
    val sink2: AkkaSink[SinkQueueWithCancel[A], Future[Done]] = AkkaFlow[SinkQueueWithCancel[A]]
      .map(s => subscriberStream[F, A](s).through(sink).compile.drain.toIO.as(Done: Done).unsafeToFuture())
      .toMat(AkkaSink.head)(Keep.right)
      .mapMaterializedValue(ffd => Async.fromFuture(Async.fromFuture(Effect[F].pure(ffd))).toIO.unsafeToFuture())
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
  def fs2PipeToAkkaFlow[F[_]: ConcurrentEffect: ContextShift, A, B](pipe: Pipe[F, A, B]): Graph[FlowShape[A, B], NotUsed] = {
    val source = AkkaSource.queue[B](0, OverflowStrategy.backpressure)
    val sink1: AkkaSink[A, SinkQueueWithCancel[A]] = AkkaSink.queue[A]()
    // A sink that runs an FS2 transformerStream when consuming the publisher actor (= materialized value) of source
    // and the subscriber actor (= materialized value) of sink1
    val sink2 = AkkaSink.foreach[(SourceQueueWithComplete[B], SinkQueueWithCancel[A])] { ps =>
      // Fire and forget Future so it runs in the background
      ConcurrentEffect[F].toIO(transformerStream(ps._2, ps._1, pipe).compile.drain).unsafeToFuture()
      ()
    }

    AkkaFlow.fromGraph(GraphDSL.create(source, sink1)(Keep.both) { implicit builder => (source, sink1) =>
      import GraphDSL.Implicits._
      builder.materializedValue ~> sink2
      FlowShape(sink1.in, source.out)
    }).mapMaterializedValue(_ => NotUsed)
  }

  private def subscriberStream[F[_]: Async: ContextShift, A](subscriber: SinkQueueWithCancel[A]): Stream[F, A] = {
    val pull = Async.fromFuture(Async[F].delay(subscriber.pull()))
    val cancel = Async[F].delay(subscriber.cancel())
    Stream.repeatEval(pull).unNoneTerminate.onFinalize(cancel)
  }

  private def publisherStream[F[_]: Concurrent: ContextShift, A](publisher: SourceQueueWithComplete[A], stream: Stream[F, A]): Stream[F, Unit] = {
    def publish(a: A): F[Option[Unit]] = Async.fromFuture(Concurrent[F].delay(publisher.offer(a))).flatMap {
      case QueueOfferResult.Enqueued => ().some.pure[F]
      case QueueOfferResult.Failure(cause) => Concurrent[F].raiseError[Option[Unit]](cause)
      case QueueOfferResult.QueueClosed => none[Unit].pure[F]
      case QueueOfferResult.Dropped => Concurrent[F].raiseError[Option[Unit]](new IllegalStateException("This should never happen because we use OverflowStrategy.backpressure"))
    }.recover {
      // This handles a race condition between `interruptWhen` and `publish`.
      // There's no guarantee that, when the akka sink is terminated, we will observe the
      // `interruptWhen` termination before calling publish one last time.
      // Such a call fails with StreamDetachedException
      case _: StreamDetachedException => none[Unit]
    }

    def watchCompletion: F[Unit] = Async.fromFuture(Concurrent[F].delay(publisher.watchCompletion())).void
    def fail(e: Throwable): F[Unit] = Concurrent[F].delay(publisher.fail(e)) >> watchCompletion
    def complete: F[Unit] = Concurrent[F].delay(publisher.complete()) >> watchCompletion

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
    def toStream[F[_]: ContextShift: Async](implicit materializer: Materializer, @implicitNotFound(
      "Cannot convert `Source[A, M]` to `Stream[F, A]` - `M` value would be discarded.\nIf that is intended, first convert the `Source` to `Source[A, NotUsed]`.\nIf `M` should not be discarded, then use `source.toStreamMat[F]` instead.") ev: M =:= NotUsed): Stream[F, A] = {
      val _ = ev // to suppress 'never used' warning. The warning fires on 2.12 but not on 2.13, so I can't use `nowarn`
      akkaSourceToFs2Stream(source.asInstanceOf[Graph[SourceShape[A], NotUsed]])
    }

    /** @see [[Converter#akkaSourceToFs2StreamMat]] */
    def toStreamMat[F[_]: ContextShift: Async](implicit materializer: Materializer): F[(Stream[F, A], M)] =
      akkaSourceToFs2StreamMat(source)

    @deprecated(message = "Use `.toStream[F]` for M=NotUsed; use `.toStreamMat[F]` for other M. This version relies on side effects.", since = "0.11")
    def toStream[F[_]: ContextShift: Async](onMaterialization: M => Unit = _ => ())(implicit materializer: Materializer): Stream[F, A] =
      Stream.force(
        akkaSourceToFs2StreamMat(source).map {
          case (akkaStream, mat) =>
            onMaterialization(mat)
            akkaStream
        })
  }

  implicit class AkkaSinkFutureDsl[A, M](sink: Graph[SinkShape[A], Future[M]]) {

    /** @see [[Converter#akkaSinkToFs2SinkMat]] */
    def toPipeMatWithResult[F[_]: ConcurrentEffect: ContextShift](
      implicit
      ec: ExecutionContext,
      m: Materializer): F[Pipe[F, A, Either[Throwable, M]]] =
      akkaSinkToFs2PipeMat[F, A, M](sink)

  }

  implicit class AkkaSinkDsl[A, M](sink: Graph[SinkShape[A], M]) {

    /** @see [[Converter#akkaSinkToFs2Sink]] */
    def toPipe[F[_]: ContextShift: Concurrent](implicit
      materializer: Materializer,
      @implicitNotFound(
        "Cannot convert `Sink[A, M]` to `Pipe[F, A, Unit]` - `M` value would be discarded.\nIf that is intended, first convert the `Sink` to `Sink[A, NotUsed]`.\nIf `M` should not be discarded, then use `sink.toPipeMat[F]` instead.") ev: M =:= NotUsed): Pipe[F, A, Unit] = {
      val _ = ev // to suppress 'never used' warning. The warning fires on 2.12 but not on 2.13, so I can't use `nowarn`
      akkaSinkToFs2Pipe(sink.asInstanceOf[Graph[SinkShape[A], NotUsed]])
    }

    /** @see [[Converter#akkaSinkToFs2SinkMat]] */
    def toPipeMat[F[_]: ContextShift: Concurrent](implicit materializer: Materializer): F[(Pipe[F, A, Unit], M)] =
      akkaSinkToFs2PipeMat(sink)

    @deprecated(message = "Use `.toSink[F]` for M=NotUsed; use `.toSinkMat[F]` for other M. This version relies on side effects.", since = "0.11")
    def toSink[F[_]: ContextShift: Concurrent](onMaterialization: M => Unit)(implicit materializer: Materializer): Pipe[F, A, Unit] =
      (s: Stream[F, A]) =>
        Stream.force {
          akkaSinkToFs2PipeMat(sink).map {
            case (fs2Sink, mat) =>
              onMaterialization(mat)
              s.through(fs2Sink)
          }
        }

  }

  implicit class AkkaFlowDsl[A, B, M](flow: Graph[FlowShape[A, B], M]) {

    /** @see [[Converter#akkaFlowToFs2Pipe]] */
    def toPipe[F[_]: ContextShift: ConcurrentEffect](
      implicit
      materializer: Materializer,
      @implicitNotFound(
        "Cannot convert `Flow[A, B, M]` to `Pipe[F, A, B]` - `M` value would be discarded.\nIf that is intended, first convert the `Flow` to `Flow[A, B, NotUsed]`.\nIf `M` should not be discarded, then use `flow.toPipeMat[F]` instead.") ev: M =:= NotUsed): Pipe[F, A, B] = {
      val _ = ev // to suppress 'never used' warning. The warning fires on 2.12 but not on 2.13, so I can't use `nowarn`
      akkaFlowToFs2Pipe(flow.asInstanceOf[Graph[FlowShape[A, B], NotUsed]])
    }

    /** @see [[Converter#akkaFlowToFs2PipeMat]] */
    def toPipeMat[F[_]: ContextShift: ConcurrentEffect](implicit materializer: Materializer): F[(Pipe[F, A, B], M)] =
      akkaFlowToFs2PipeMat(flow)

    @deprecated(message = "Use `.toPipe[F]` for M=NotUsed; use `.toPipeMat[F]` for other M. This version relies on side effects.", since = "0.11")
    def toPipe[F[_]: ContextShift: ConcurrentEffect](onMaterialization: M => Unit = _ => ())(implicit materializer: Materializer): Pipe[F, A, B] =
      (s: Stream[F, A]) => Stream.force {
        akkaFlowToFs2PipeMat(flow).map {
          case (fs2Pipe, mat) =>
            onMaterialization(mat)
            s.through(fs2Pipe)
        }
      }
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

  implicit class FS2SinkIODsl[F[_]: Effect: ContextShift, A](sink: Pipe[F, A, Unit]) {
    /** @see [[Converter#fs2PipeToAkkaSink]] */
    def toSink: Graph[SinkShape[A], Future[Done]] =
      fs2PipeToAkkaSink(sink)
  }

  implicit class FS2PipeIODsl[F[_]: ContextShift: ConcurrentEffect, A, B](pipe: Pipe[F, A, B]) {

    /** @see [[Converter#fs2PipeToAkkaFlow]] */
    def toFlow: Graph[FlowShape[A, B], NotUsed] =
      fs2PipeToAkkaFlow(pipe)
  }
}
