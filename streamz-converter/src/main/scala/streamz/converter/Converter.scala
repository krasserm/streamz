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

package streamz.converter

import akka.actor._
import akka.stream._
import akka.stream.scaladsl.{ Flow => AkkaFlow, Sink => AkkaSink, Source => AkkaSource, _ }
import akka.{ Done, NotUsed }
import cats.effect.IO
import cats.implicits._
import fs2._
import streamz.converter.AkkaStreamPublisher._
import streamz.converter.AkkaStreamSubscriber._

import scala.concurrent.{ ExecutionContext, Future }

object Converter {
  type Callback[A] = Either[Throwable, A] => Unit
}

trait Converter {
  import Converter._

  /**
   * Converts an Akka Stream [[Graph]] of [[SourceShape]] to an FS2 [[Stream]]. The [[Graph]] is materialized when
   * the [[Stream]]'s [[IO]] in run. The materialized value can be obtained with the `onMaterialization` callback.
   */
  def akkaSourceToFs2Stream[A, M](source: Graph[SourceShape[A], M])(onMaterialization: M => Unit)(implicit executionContext: ExecutionContext, materializer: Materializer): Stream[IO, A] =
    Stream.bracket(
      IO {
        val (mat, subscriber) = AkkaSource.fromGraph(source).toMat(AkkaSink.actorSubscriber[A](AkkaStreamSubscriber.props[A]))(Keep.both).run()
        onMaterialization(mat)
        subscriber
      }
    )(subscriberStream[A], _ => IO.pure(()))

  /**
   * Converts an Akka Stream [[Graph]] of [[SinkShape]] to an FS2 [[Sink]]. The [[Graph]] is materialized when
   * the [[Sink]]'s [[IO]] in run. The materialized value can be obtained with the `onMaterialization` callback.
   */
  def akkaSinkToFs2Sink[A, M](sink: Graph[SinkShape[A], M])(onMaterialization: M => Unit)(implicit executionContext: ExecutionContext, materializer: Materializer): Sink[IO, A] = { s =>
    Stream.bracket(
      IO {
        val (publisher, mat) = AkkaSource.actorPublisher[A](AkkaStreamPublisher.props[A]).toMat(sink)(Keep.both).run()
        onMaterialization(mat)
        publisher
      }
    )(publisherStream[A](_, s), _ => IO.pure(()))
  }

  /**
   * Converts an Akka Stream [[Graph]] of [[FlowShape]] to an FS2 [[Pipe]]. The [[Graph]] is materialized when
   * the [[Pipe]]'s [[IO]] in run. The materialized value can be obtained with the `onMaterialization` callback.
   */
  def akkaFlowToFs2Pipe[A, B, M](flow: Graph[FlowShape[A, B], M])(onMaterialization: M => Unit)(implicit executionContext: ExecutionContext, materializer: Materializer): Pipe[IO, A, B] = { s =>
    Stream.bracket(
      IO {
        val src = AkkaSource.actorPublisher[A](AkkaStreamPublisher.props[A])
        val snk = AkkaSink.actorSubscriber[B](AkkaStreamSubscriber.props[B])
        val ((publisher, mat), subscriber) = src.viaMat(flow)(Keep.both).toMat(snk)(Keep.both).run()
        onMaterialization(mat)
        (publisher, subscriber)
      }
    )(ps => transformerStream[A, B](ps._2, ps._1, s), _ => IO.pure(()))
  }

  /**
   * Converts an FS2 [[Stream]] to an Akka Stream [[Graph]] of [[SourceShape]]. The [[Stream]] is run when the
   * [[Graph]] is materialized.
   */
  def fs2StreamToAkkaSource[A](stream: Stream[IO, A])(implicit executionContext: ExecutionContext): Graph[SourceShape[A], NotUsed] = {
    val source = AkkaSource.actorPublisher(AkkaStreamPublisher.props[A])
    // A sink that runs an FS2 publisherStream when consuming the publisher actor (= materialized value) of source
    val sink = AkkaSink.foreach[ActorRef](publisherStream[A](_, stream).run.unsafeToFuture())

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
  def fs2SinkToAkkaSink[A](sink: Sink[IO, A])(implicit executionContext: ExecutionContext): Graph[SinkShape[A], Future[Done]] = {
    val sink1: AkkaSink[A, ActorRef] = AkkaSink.actorSubscriber(AkkaStreamSubscriber.props[A])
    // A sink that runs an FS2 subscriberStream when consuming the subscriber actor (= materialized value) of sink1.
    // The future returned from unsafeToFuture() completes when the subscriber stream completes and is made
    // available as materialized value of this sink.
    val sink2: AkkaSink[ActorRef, Future[Done]] = AkkaFlow[ActorRef]
      .map(subscriberStream[A](_).to(sink).run.unsafeToFuture())
      .toMat(AkkaSink.head)(Keep.right).mapMaterializedValue(_.flatMap(_.map(_ => Done)))

    AkkaSink.fromGraph(GraphDSL.create(sink1, sink2)(Keep.both) { implicit builder => (sink1, sink2) =>
      import GraphDSL.Implicits._
      builder.materializedValue ~> AkkaFlow[(ActorRef, _)].map(_._1) ~> sink2
      SinkShape(sink1.in)
    }).mapMaterializedValue(_._2)
  }

  /**
   * Converts an FS2 [[Pipe]] to an Akka Stream [[Graph]] of [[FlowShape]]. The [[Pipe]] is run when the
   * [[Graph]] is materialized.
   */
  def fs2PipeToAkkaFlow[A, B](pipe: Pipe[IO, A, B])(implicit executionContext: ExecutionContext): Graph[FlowShape[A, B], NotUsed] = {
    val source = AkkaSource.actorPublisher(AkkaStreamPublisher.props[B])
    val sink1: AkkaSink[A, ActorRef] = AkkaSink.actorSubscriber(AkkaStreamSubscriber.props[A])
    // A sink that runs an FS2 transformerStream when consuming the publisher actor (= materialized value) of source
    // and the subscriber actor (= materialized value) of sink1
    val sink2 = AkkaSink.foreach[(ActorRef, ActorRef)](ps => transformerStream(ps._2, ps._1, pipe).run.unsafeToFuture())

    AkkaFlow.fromGraph(GraphDSL.create(source, sink1)(Keep.both) { implicit builder => (source, sink1) =>
      import GraphDSL.Implicits._
      builder.materializedValue ~> sink2
      FlowShape(sink1.in, source.out)
    }).mapMaterializedValue(_ => NotUsed)
  }

  private def subscriberStream[A](subscriber: ActorRef)(implicit executionContext: ExecutionContext): Stream[IO, A] = {
    val subscriberIO = IO.shift >> IO.async((callback: Callback[Option[A]]) => subscriber ! Request(callback))
    Stream.repeatEval(subscriberIO).unNoneTerminate
      .onFinalize {
        IO {
          subscriber ! PoisonPill
        }
      }
  }

  private def publisherStream[A](publisher: ActorRef, stream: Stream[IO, A])(implicit executionContext: ExecutionContext): Stream[IO, Unit] = {
    def publisherIO(i: A): IO[Option[Unit]] = IO.shift >> IO.async((callback: Callback[Option[Unit]]) => publisher ! Next(i, callback))
    stream.flatMap(i => Stream.eval(publisherIO(i))).unNoneTerminate
      .handleErrorWith { ex =>
        publisher ! Error(ex)
        Stream.raiseError(ex)
      }
      .onFinalize {
        IO {
          publisher ! Complete
          publisher ! PoisonPill
        }
      }
  }

  private def transformerStream[A, B](subscriber: ActorRef, publisher: ActorRef, stream: Stream[IO, A])(implicit executionContext: ExecutionContext): Stream[IO, B] =
    publisherStream[A](publisher, stream).either(subscriberStream[B](subscriber)).collect { case Right(elem) => elem }

  private def transformerStream[A, B](subscriber: ActorRef, publisher: ActorRef, pipe: Pipe[IO, A, B])(implicit executionContext: ExecutionContext): Stream[IO, Unit] =
    subscriberStream[A](subscriber).through(pipe).to(s => publisherStream(publisher, s))
}

trait ConverterDsl extends Converter {
  implicit class AkkaSourceDsl[A, M](source: Graph[SourceShape[A], M]) {
    /** @see [[Converter#akkaSourceToFs2Stream]] */
    def toStream(onMaterialization: M => Unit = _ => ())(implicit executionContext: ExecutionContext, materializer: Materializer): Stream[IO, A] =
      akkaSourceToFs2Stream(source)(onMaterialization)
  }

  implicit class AkkaSinkDsl[A, M](sink: Graph[SinkShape[A], M]) {
    /** @see [[Converter#akkaSinkToFs2Sink]] */
    def toSink(onMaterialization: M => Unit = _ => ())(implicit executionContext: ExecutionContext, materializer: Materializer): Sink[IO, A] =
      akkaSinkToFs2Sink(sink)(onMaterialization)
  }

  implicit class AkkaFlowDsl[A, B, M](flow: Graph[FlowShape[A, B], M]) {
    /** @see [[Converter#akkaFlowToFs2Pipe]] */
    def toPipe(onMaterialization: M => Unit = _ => ())(implicit executionContext: ExecutionContext, materializer: Materializer): Pipe[IO, A, B] =
      akkaFlowToFs2Pipe(flow)(onMaterialization)
  }

  implicit class FS2StreamNothingDsl[A](stream: Stream[Nothing, A]) {
    /** @see [[Converter#fs2StreamToAkkaSource]] */
    def toSource(implicit executionContext: ExecutionContext): Graph[SourceShape[A], NotUsed] =
      fs2StreamToAkkaSource(stream)
  }

  implicit class FS2StreamPureDsl[A](stream: Stream[Pure, A]) {
    /** @see [[Converter#fs2StreamToAkkaSource]] */
    def toSource(implicit executionContext: ExecutionContext): Graph[SourceShape[A], NotUsed] =
      fs2StreamToAkkaSource(stream)
  }

  implicit class FS2StreamIODsl[A](stream: Stream[IO, A])(implicit executionContext: ExecutionContext) {
    /** @see [[Converter#fs2StreamToAkkaSource]] */
    def toSource(implicit executionContext: ExecutionContext): Graph[SourceShape[A], NotUsed] =
      fs2StreamToAkkaSource(stream)
  }

  implicit class FS2SinkPureDsl[A](sink: Sink[Pure, A])(implicit executionContext: ExecutionContext) {
    /** @see [[Converter#fs2SinkToAkkaSink]] */
    def toSink(implicit executionContext: ExecutionContext): Graph[SinkShape[A], Future[Done]] =
      fs2SinkToAkkaSink(sink)
  }

  implicit class FS2SinkIODsl[A](sink: Sink[IO, A])(implicit executionContext: ExecutionContext) {
    /** @see [[Converter#fs2SinkToAkkaSink]] */
    def toSink(implicit executionContext: ExecutionContext): Graph[SinkShape[A], Future[Done]] =
      fs2SinkToAkkaSink(sink)
  }

  implicit class FS2PipePureDsl[A, B](pipe: Pipe[Pure, A, B])(implicit executionContext: ExecutionContext) {
    /** @see [[Converter#fs2PipeToAkkaFlow]] */
    def toFlow(implicit executionContext: ExecutionContext): Graph[FlowShape[A, B], NotUsed] =
      fs2PipeToAkkaFlow(pipe)
  }

  implicit class FS2PipeIODsl[A, B](pipe: Pipe[IO, A, B])(implicit executionContext: ExecutionContext) {
    /** @see [[Converter#fs2PipeToAkkaFlow]] */
    def toFlow(implicit executionContext: ExecutionContext): Graph[FlowShape[A, B], NotUsed] =
      fs2PipeToAkkaFlow(pipe)
  }
}
