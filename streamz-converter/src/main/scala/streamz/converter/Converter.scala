/*
 * Copyright 2014 - 2018 the original author or authors.
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
import cats.implicits._
import fs2._

import scala.concurrent.Future

object Converter {
  type Callback[A] = Either[Throwable, A] => Unit
}

trait Converter {
  import Converter._

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
  def akkaSinkToFs2Sink[F[_]: ContextShift, A, M](sink: Graph[SinkShape[A], M])(onMaterialization: M => Unit)(implicit materializer: Materializer, F: Concurrent[F]): Sink[F, A] = { s =>
    Stream.force {
      F.delay {
        val (publisher, mat) = AkkaSource.queue[A](0, OverflowStrategy.backpressure).toMat(sink)(Keep.both).run()
        onMaterialization(mat)
        publisherStream[F, A](publisher, s)
      }
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
    val sink = AkkaSink.foreach[SourceQueueWithComplete[A]](p => F.toIO(publisherStream[F, A](p, stream).compile.drain).unsafeToFuture())

    AkkaSource.fromGraph(GraphDSL.create(source) { implicit builder => source =>
      import GraphDSL.Implicits._
      builder.materializedValue ~> sink
      SourceShape(source.out)
    }).mapMaterializedValue(_ => NotUsed)
  }

  /**
   * Converts an FS2 [[Sink]] to an Akka Stream [[Graph]] of [[SinkShape]]. The [[Sink]] is run when the
   * [[Graph]] is materialized.
   */
  def fs2SinkToAkkaSink[F[_]: ContextShift, A](sink: Sink[F, A])(implicit F: Effect[F]): Graph[SinkShape[A], Future[Done]] = {
    val sink1: AkkaSink[A, SinkQueueWithCancel[A]] = AkkaSink.queue[A]()
    // A sink that runs an FS2 subscriberStream when consuming the subscriber actor (= materialized value) of sink1.
    // The future returned from unsafeToFuture() completes when the subscriber stream completes and is made
    // available as materialized value of this sink.
    val sink2: AkkaSink[SinkQueueWithCancel[A], Future[Done]] = AkkaFlow[SinkQueueWithCancel[A]]
      .map(s => F.toIO(subscriberStream[F, A](s).to(sink).compile.drain).as(Done: Done).unsafeToFuture())
      .toMat(AkkaSink.head)(Keep.right)
      .mapMaterializedValue(ffd => IO.fromFuture(IO.pure(ffd)).flatMap(fd => IO.fromFuture(IO.pure(fd))).unsafeToFuture())

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
    val sink2 = AkkaSink.foreach[(SourceQueueWithComplete[B], SinkQueueWithCancel[A])](ps => F.toIO(transformerStream(ps._2, ps._1, pipe).compile.drain).unsafeToFuture())

    AkkaFlow.fromGraph(GraphDSL.create(source, sink1)(Keep.both) { implicit builder => (source, sink1) =>
      import GraphDSL.Implicits._
      builder.materializedValue ~> sink2
      FlowShape(sink1.in, source.out)
    }).mapMaterializedValue(_ => NotUsed)
  }

  private def subscriberStream[F[_], A](subscriber: SinkQueueWithCancel[A])(implicit context: ContextShift[F], F: Async[F]): Stream[F, A] = {
    val pull = context.shift >> F.liftIO(IO.fromFuture(IO(subscriber.pull())))
    Stream.repeatEval(pull).unNoneTerminate
  }

  private def publisherStream[F[_], A](publisher: SourceQueueWithComplete[A], stream: Stream[F, A])(implicit context: ContextShift[F], F: Concurrent[F]): Stream[F, Unit] = {
    def publish(a: A): F[Option[Unit]] = F.liftIO(IO.fromFuture(IO(publisher.offer(a)))).flatMap {
      case QueueOfferResult.Enqueued => ().some.pure[F]
      case QueueOfferResult.Failure(cause) => F.raiseError[Option[Unit]](cause)
      case QueueOfferResult.QueueClosed => none[Unit].pure[F]
      case QueueOfferResult.Dropped => F.raiseError[Option[Unit]](new IllegalArgumentException("Should never happen"))
    }
    def watchCompletion: F[Unit] = F.liftIO(IO.fromFuture(IO(publisher.watchCompletion())).void)
    def fail(e: Throwable): F[Unit] = F.delay(publisher.fail(e)) >> watchCompletion
    def complete: F[Unit] = F.delay(publisher.complete()) >> watchCompletion
    stream.interruptWhen(watchCompletion.attempt).evalMap(publish).unNoneTerminate
      .handleErrorWith { ex =>
        Stream.eval(fail(ex)) >> Stream.raiseError[F](ex)
      } ++ Stream.eval_(complete)
  }

  private def transformerStream[F[_]: ContextShift: Concurrent, A, B](subscriber: SinkQueueWithCancel[B], publisher: SourceQueueWithComplete[A], stream: Stream[F, A]): Stream[F, B] =
    publisherStream[F, A](publisher, stream).either(subscriberStream[F, B](subscriber)).collect { case Right(elem) => elem }

  private def transformerStream[F[_]: ContextShift: Concurrent, A, B](subscriber: SinkQueueWithCancel[A], publisher: SourceQueueWithComplete[B], pipe: Pipe[F, A, B]): Stream[F, Unit] =
    subscriberStream[F, A](subscriber).through(pipe).to(s => publisherStream(publisher, s))
}

trait ConverterDsl extends Converter {
  implicit class AkkaSourceDsl[A, M](source: Graph[SourceShape[A], M]) {

    /** @see [[Converter#akkaSourceToFs2StreamF]] */
    def toStream[F[_]: ContextShift: Async](onMaterialization: M => Unit = _ => ())(implicit materializer: Materializer): Stream[F, A] =
      akkaSourceToFs2Stream(source)(onMaterialization)
  }

  implicit class AkkaSinkDsl[A, M](sink: Graph[SinkShape[A], M]) {

    /** @see [[Converter#akkaSinkToFs2Sink]] */
    def toSink[F[_]: ContextShift: Concurrent](onMaterialization: M => Unit = _ => ())(implicit materializer: Materializer): Sink[F, A] =
      akkaSinkToFs2Sink(sink)(onMaterialization)
  }

  implicit class AkkaFlowDsl[A, B, M](flow: Graph[FlowShape[A, B], M]) {

    /** @see [[Converter#akkaFlowToFs2Pipe]] */
    def toPipe[F[_]: ContextShift: ConcurrentEffect](onMaterialization: M => Unit = _ => ())(implicit materializer: Materializer): Pipe[F, A, B] =
      akkaFlowToFs2Pipe(flow)(onMaterialization)
  }

  implicit class FS2StreamNothingDsl[A](stream: Stream[Nothing, A]) {

    /** @see [[Converter#fs2StreamToAkkaSourceF]] */
    def toSource(implicit contextShift: ContextShift[IO]): Graph[SourceShape[A], NotUsed] =
      fs2StreamToAkkaSource(stream: Stream[IO, A])
  }

  implicit class FS2StreamPureDsl[A](stream: Stream[Pure, A]) {

    /** @see [[Converter#fs2StreamToAkkaSourceF]] */
    def toSource(implicit contextShift: ContextShift[IO]): Graph[SourceShape[A], NotUsed] =
      fs2StreamToAkkaSource(stream: Stream[IO, A])
  }

  implicit class FS2StreamIODsl[F[_]: ContextShift: ConcurrentEffect, A](stream: Stream[F, A]) {

    /** @see [[Converter#fs2StreamToAkkaSourceF]] */
    def toSource: Graph[SourceShape[A], NotUsed] =
      fs2StreamToAkkaSource(stream)
  }

  implicit class FS2SinkPureDsl[A](sink: Sink[Pure, A]) {

    /** @see [[Converter#fs2SinkToAkkaSink]] */
    def toSink(implicit contextShift: ContextShift[IO]): Graph[SinkShape[A], Future[Done]] =
      fs2SinkToAkkaSink(sink: Sink[IO, A])
  }

  implicit class FS2SinkIODsl[F[_]: ContextShift: Effect, A](sink: Sink[F, A]) {
    /** @see [[Converter#fs2SinkToAkkaSink]] */
    def toSink: Graph[SinkShape[A], Future[Done]] =
      fs2SinkToAkkaSink(sink)
  }

  implicit class FS2PipePureDsl[A, B](pipe: Pipe[Pure, A, B]) {

    /** @see [[Converter#fs2PipeToAkkaFlow]] */
    def toFlow(implicit contextShift: ContextShift[IO]): Graph[FlowShape[A, B], NotUsed] =
      fs2PipeToAkkaFlow(pipe: Pipe[IO, A, B])
  }

  implicit class FS2PipeIODsl[F[_]: ContextShift: ConcurrentEffect, A, B](pipe: Pipe[F, A, B]) {

    /** @see [[Converter#fs2PipeToAkkaFlow]] */
    def toFlow: Graph[FlowShape[A, B], NotUsed] =
      fs2PipeToAkkaFlow(pipe)
  }
}
