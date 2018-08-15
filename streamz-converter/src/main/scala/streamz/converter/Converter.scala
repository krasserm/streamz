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

import akka.actor._
import akka.stream._
import akka.stream.scaladsl.{ Flow => AkkaFlow, Sink => AkkaSink, Source => AkkaSource, _ }
import akka.{ Done, NotUsed }
import cats.effect._
import cats.implicits._
import fs2._
import streamz.converter.AkkaStreamPublisher._
import streamz.converter.AkkaStreamSubscriber._

import scala.concurrent.{ Future, Promise }

object Converter {
  type Callback[A] = Either[Throwable, A] => Unit
}

trait Converter {
  import Converter._

  /**
   * Converts an Akka Stream [[Graph]] of [[SourceShape]] to an FS2 [[Stream]]. The [[Graph]] is materialized when
   * the [[Stream]]'s [[F]] in run. The materialized value can be obtained with the `onMaterialization` callback.
   */
  def akkaSourceToFs2Stream[F[_], A, M](source: Graph[SourceShape[A], M])(
    onMaterialization: M => Unit)(implicit
    timer: Timer[F],
    F: Async[F],
    materializer: Materializer): Stream[F, A] =
    Stream.bracket(F.delay {
      val (mat, subscriber) = AkkaSource
        .fromGraph(source)
        .toMat(AkkaSink.actorSubscriber[A](AkkaStreamSubscriber.props[A]))(
          Keep.both)
        .run()
      onMaterialization(mat)
      subscriber
    })(_ => F.pure(())).flatMap(subscriberStream[F, A])

  /**
   * Converts an Akka Stream [[Graph]] of [[SinkShape]] to an FS2 [[Sink]]. The [[Graph]] is materialized when
   * the [[Sink]]'s [[F]] in run. The materialized value can be obtained with the `onMaterialization` callback.
   */
  def akkaSinkToFs2Sink[F[_], A, M](sink: Graph[SinkShape[A], M])(
    onMaterialization: M => Unit)(implicit
    timer: Timer[F],
    F: Async[F],
    materializer: Materializer): Sink[F, A] = {
    s =>
      Stream.bracket(F.delay {
        val (publisher, mat) = AkkaSource
          .actorPublisher[A](AkkaStreamPublisher.props[A])
          .toMat(sink)(Keep.both)
          .run()
        onMaterialization(mat)
        publisher
      })(_ => F.pure(())).flatMap(publisherStream[F, A](_, s))
  }

  /**
   * Converts an Akka Stream [[Graph]] of [[FlowShape]] to an FS2 [[Pipe]]. The [[Graph]] is materialized when
   * the [[Pipe]]'s [[F]] in run. The materialized value can be obtained with the `onMaterialization` callback.
   */
  def akkaFlowToFs2Pipe[F[_], A, B, M](flow: Graph[FlowShape[A, B], M])(
    onMaterialization: M => Unit)(
    implicit
    timer: Timer[F], F: ConcurrentEffect[F],
    materializer: Materializer): Pipe[F, A, B] = { s =>
    val acquire = F.delay {
      val src = AkkaSource.actorPublisher[A](AkkaStreamPublisher.props[A])
      val snk = AkkaSink.actorSubscriber[B](AkkaStreamSubscriber.props[B])
      val ((publisher, mat), subscriber) =
        src.viaMat(flow)(Keep.both).toMat(snk)(Keep.both).run()
      onMaterialization(mat)
      (publisher, subscriber)
    }
    Stream.bracket(acquire)(_ => F.pure(())).flatMap { case (pub, sub) => transformerStream[F, A, B](pub, sub, s) }
  }

  /**
   * Converts an FS2 [[Stream]] to an Akka Stream [[Graph]] of [[SourceShape]]. The [[Stream]] is run when the
   * [[Graph]] is materialized.
   */
  def fs2StreamToAkkaSource[F[_]: Timer, A](stream: Stream[F, A])(
    implicit
    F: Effect[F]): Graph[SourceShape[A], NotUsed] = {
    val source = AkkaSource.actorPublisher(AkkaStreamPublisher.props[A])
    // A sink that runs an FS2 publisherStream when consuming the publisher actor (= materialized value) of source
    val sink = AkkaSink.foreach[ActorRef] { publisher =>
      val publish = publisherStream[F, A](publisher, stream).compile.drain
      F.runAsync(publish)(_ => IO.unit).unsafeToFuture()
    }

    AkkaSource
      .fromGraph(GraphDSL.create(source) { implicit builder => source =>
        import GraphDSL.Implicits._
        builder.materializedValue ~> sink
        SourceShape(source.out)
      })
      .mapMaterializedValue(_ => NotUsed)
  }

  /**
   * Converts an FS2 [[Sink]] to an Akka Stream [[Graph]] of [[SinkShape]]. The [[Sink]] is run when the
   * [[Graph]] is materialized.
   */
  def fs2SinkToAkkaSink[F[_]: Timer, A](sink: Sink[F, A])(implicit F: Effect[F]): Graph[SinkShape[A], Future[Done]] = {
    val sink1: AkkaSink[A, ActorRef] = AkkaSink.actorSubscriber(AkkaStreamSubscriber.props[A])
    // A sink that runs an FS2 subscriberStream when consuming the subscriber actor (= materialized value) of sink1.
    // The future returned from unsafeToFuture() completes when the subscriber stream completes and is made
    // available as materialized value of this sink.
    val sink2: AkkaSink[ActorRef, Future[Done]] = AkkaFlow[ActorRef]
      .map { subscriber =>
        val runStream = subscriberStream[F, A](subscriber).to(sink).compile.drain
        val p = Promise[Done]
        F.runAsync(runStream)(r => IO(p.complete(r.fold(scala.util.Failure(_), _ => scala.util.Success(Done))))).unsafeToFuture()
        p.future
      }
      .toMat(AkkaSink.head)(Keep.right)
      .mapMaterializedValue { x => IO.fromFuture(IO.pure(x)).flatMap(i => IO.fromFuture(IO.pure(i))).unsafeToFuture() }

    AkkaSink.fromGraph(GraphDSL.create(sink1, sink2)(Keep.both) { implicit builder => (sink1, sink2) =>
      import GraphDSL.Implicits._
      builder.materializedValue ~> AkkaFlow[(ActorRef, _)]
        .map(_._1) ~> sink2
      SinkShape(sink1.in)
    })
      .mapMaterializedValue(_._2)
  }

  /**
   * Converts an FS2 [[Pipe]] to an Akka Stream [[Graph]] of [[FlowShape]]. The [[Pipe]] is run when the
   * [[Graph]] is materialized.
   */
  def fs2PipeToAkkaFlow[F[_]: Timer, A, B](pipe: Pipe[F, A, B])(implicit F: Effect[F]): Graph[FlowShape[A, B], NotUsed] = {
    val source = AkkaSource.actorPublisher(AkkaStreamPublisher.props[B])
    val sink1: AkkaSink[A, ActorRef] = AkkaSink.actorSubscriber(AkkaStreamSubscriber.props[A])
    // A sink that runs an FS2 transformerStream when consuming the publisher actor (= materialized value) of source
    // and the subscriber actor (= materialized value) of sink1
    val sink2 = AkkaSink.foreach[(ActorRef, ActorRef)](ps => F.runAsync(transformerStream(ps._2, ps._1, pipe).compile.drain)(_ => IO.unit).unsafeToFuture())

    AkkaFlow.fromGraph(GraphDSL.create(source, sink1)(Keep.both) { implicit builder => (source, sink1) =>
      import GraphDSL.Implicits._
      builder.materializedValue ~> sink2
      FlowShape(sink1.in, source.out)
    }).mapMaterializedValue(_ => NotUsed)
  }

  private def subscriberStream[F[_], A](subscriber: ActorRef)(implicit timer: Timer[F], F: Async[F]): Stream[F, A] = {
    val subscriberIO = timer.shift >> F.async((callback: Callback[Option[A]]) => subscriber ! Request(callback))
    Stream
      .repeatEval(subscriberIO)
      .unNoneTerminate
      .onFinalize {
        F.delay {
          subscriber ! PoisonPill
        }
      }
  }

  private def publisherStream[F[_], A](publisher: ActorRef, stream: Stream[F, A])(implicit timer: Timer[F], F: Async[F]): Stream[F, Unit] = {
    def publisherF(i: A): F[Option[Unit]] = timer.shift >> F.async[Option[Unit]](callback => publisher ! Next(i, callback))
    stream.flatMap(i => Stream.eval(publisherF(i))).unNoneTerminate
      .handleErrorWith { ex =>
        publisher ! Error(ex)
        Stream.raiseError(ex)
      }
      .onFinalize {
        F.delay {
          publisher ! Complete
          publisher ! PoisonPill
        }
      }
  }

  private def transformerStream[F[_]: Timer: ConcurrentEffect, A, B](subscriber: ActorRef, publisher: ActorRef, stream: Stream[F, A]): Stream[F, B] =
    publisherStream[F, A](publisher, stream).either(subscriberStream[F, B](subscriber)).collect { case Right(elem) => elem }

  private def transformerStream[F[_]: Timer: Async, A, B](subscriber: ActorRef, publisher: ActorRef, pipe: Pipe[F, A, B]): Stream[F, Unit] =
    subscriberStream[F, A](subscriber).through(pipe).to(s => publisherStream(publisher, s))
}

trait ConverterDsl extends Converter {
  implicit class AkkaSourceDsl[A, M](source: Graph[SourceShape[A], M]) {

    /** @see [[Converter#akkaSourceToFs2StreamF]] */
    def toStream[F[_]: Timer: Async](onMaterialization: M => Unit = _ => ())(
      implicit
      materializer: Materializer): Stream[F, A] =
      akkaSourceToFs2Stream(source)(onMaterialization)
  }

  implicit class AkkaSinkDsl[A, M](sink: Graph[SinkShape[A], M]) {

    def toSink[F[_]: Timer: Async](onMaterialization: M => Unit = _ => ())(
      implicit
      materializer: Materializer): Sink[F, A] =
      akkaSinkToFs2Sink(sink)(onMaterialization)
  }

  implicit class AkkaFlowDsl[A, B, M](flow: Graph[FlowShape[A, B], M]) {

    def toPipe[F[_]: Timer: ConcurrentEffect](onMaterialization: M => Unit = _ => ())(
      implicit
      materializer: Materializer): Pipe[F, A, B] =
      akkaFlowToFs2Pipe(flow)(onMaterialization)
  }

  implicit class FS2StreamNothingDsl[A](stream: Stream[Nothing, A]) {

    /** @see [[Converter#fs2StreamToAkkaSourceF]] */
    def toSource(implicit executionContext: ExecutionContext): Graph[SourceShape[A], NotUsed] =
      fs2StreamToAkkaSource(stream: Stream[IO, A])
  }

  implicit class FS2StreamPureDsl[A](stream: Stream[Pure, A]) {

    /** @see [[Converter#fs2StreamToAkkaSourceF]] */
    def toSource(implicit executionContext: ExecutionContext): Graph[SourceShape[A], NotUsed] =
      fs2StreamToAkkaSource(stream: Stream[IO, A])
  }

  implicit class FS2StreamIODsl[F[_]: Timer: Effect, A](stream: Stream[F, A]) {

    /** @see [[Converter#fs2StreamToAkkaSourceF]] */
    def toSource: Graph[SourceShape[A], NotUsed] =
      fs2StreamToAkkaSource(stream)
  }

  implicit class FS2SinkPureDsl[A](sink: Sink[Pure, A])(implicit executionContext: ExecutionContext) {

    /** @see [[Converter#fs2SinkToAkkaSink]] */
    def toSink: Graph[SinkShape[A], Future[Done]] =
      fs2SinkToAkkaSink(sink: Sink[IO, A])
  }

  implicit class FS2SinkIODsl[F[_], A](sink: Sink[F, A]) {
    /** @see [[Converter#fs2SinkToAkkaSink]] */
    def toSink(implicit timer: Timer[F], F: Effect[F]): Graph[SinkShape[A], Future[Done]] =
      fs2SinkToAkkaSink(sink)
  }

  implicit class FS2PipePureDsl[A, B](pipe: Pipe[Pure, A, B])(implicit executionContext: ExecutionContext) {

    /** @see [[Converter#fs2PipeToAkkaFlow]] */
    def toFlow(implicit executionContext: ExecutionContext): Graph[FlowShape[A, B], NotUsed] =
      fs2PipeToAkkaFlow(pipe: Pipe[IO, A, B])
  }

  implicit class FS2PipeIODsl[F[_], A, B](pipe: Pipe[F, A, B]) {

    /** @see [[Converter#fs2PipeToAkkaFlow]] */
    def toFlow(implicit timer: Timer[F], F: Effect[F]): Graph[FlowShape[A, B], NotUsed] =
      fs2PipeToAkkaFlow(pipe)
  }
}
