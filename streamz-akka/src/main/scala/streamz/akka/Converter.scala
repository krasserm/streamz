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

package streamz.akka

import akka.{ Done, NotUsed }
import akka.actor._
import akka.stream._
import akka.stream.scaladsl.{ Source => AkkaSource, Flow => AkkaFlow, Sink => AkkaSink, _ }

import fs2._

import streamz.akka.AkkaStreamPublisher._
import streamz.akka.AkkaStreamSubscriber._

import scala.concurrent.{ Future, ExecutionContext }

object Converter {
  type Callback[A] = Either[Throwable, A] => Unit
}

trait Converter {
  import Converter._

  /**
   * Converts an Akka Stream [[Graph]] of [[SourceShape]] to an FS2 [[Stream]]. The [[Graph]] is materialized when
   * the [[Stream]]'s [[Task]] in run. The materialized value can be obtained with the `onMaterialization` callback.
   */
  def akkaSourceToFs2Stream[O, M](source: Graph[SourceShape[O], M])(onMaterialization: M => Unit)(implicit executionContext: ExecutionContext, materializer: Materializer): Stream[Task, O] =
    Stream.bracket(
      Task.delay {
        val (mat, subscriber) = AkkaSource.fromGraph(source).toMat(AkkaSink.actorSubscriber[O](AkkaStreamSubscriber.props[O]))(Keep.both).run()
        onMaterialization(mat)
        subscriber
      }
    )(subscriberStream[O], _ => Task.now(()))

  /**
   * Converts an Akka Stream [[Graph]] of [[SinkShape]] to an FS2 [[Sink]]. The [[Graph]] is materialized when
   * the [[Sink]]'s [[Task]] in run. The materialized value can be obtained with the `onMaterialization` callback.
   */
  def akkaSinkToFs2Sink[I, M](sink: Graph[SinkShape[I], M])(onMaterialization: M => Unit)(implicit executionContext: ExecutionContext, materializer: Materializer): Sink[Task, I] = { s =>
    Stream.bracket(
      Task.delay {
        val (publisher, mat) = AkkaSource.actorPublisher[I](AkkaStreamPublisher.props[I]).toMat(sink)(Keep.both).run()
        onMaterialization(mat)
        publisher
      }
    )(publisherStream[I](_, s), _ => Task.now(()))
  }

  /**
   * Converts an Akka Stream [[Graph]] of [[FlowShape]] to an FS2 [[Pipe]]. The [[Graph]] is materialized when
   * the [[Pipe]]'s [[Task]] in run. The materialized value can be obtained with the `onMaterialization` callback.
   */
  def akkaFlowToFs2Pipe[I, O, M](flow: Graph[FlowShape[I, O], M])(onMaterialization: M => Unit)(implicit executionContext: ExecutionContext, materializer: Materializer): Pipe[Task, I, O] = { s =>
    Stream.bracket(
      Task.delay {
        val src = AkkaSource.actorPublisher[I](AkkaStreamPublisher.props[I])
        val snk = AkkaSink.actorSubscriber[O](AkkaStreamSubscriber.props[O])
        val ((publisher, mat), subscriber) = src.viaMat(flow)(Keep.both).toMat(snk)(Keep.both).run()
        onMaterialization(mat)
        (publisher, subscriber)
      }
    )(ps => transformerStream[I, O](ps._2, ps._1, s), _ => Task.now(()))
  }

  /**
   * Converts an FS2 [[Stream]] to an Akka Stream [[Graph]] of [[SourceShape]]. The [[Stream]] is run when the
   * [[Graph]] is materialized.
   */
  def fs2StreamToAkkaSource[O](stream: Stream[Task, O])(implicit executionContext: ExecutionContext): Graph[SourceShape[O], NotUsed] = {
    val source = AkkaSource.actorPublisher(AkkaStreamPublisher.props[O])
    // A sink that runs an FS2 publisherStream when consuming the publisher actor (= materialized value) of source
    val sink = AkkaSink.foreach[ActorRef](publisherStream[O](_, stream).run.unsafeRunAsyncFuture())

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
  def fs2SinkToAkkaSink[I](sink: Sink[Task, I])(implicit executionContext: ExecutionContext): Graph[SinkShape[I], Future[Done]] = {
    val sink1: AkkaSink[I, ActorRef] = AkkaSink.actorSubscriber(AkkaStreamSubscriber.props[I])
    // A sink that runs an FS2 subscriberStream when consuming the subscriber actor (= materialized value) of sink1.
    // The future returned from unsafeRunAsyncFuture() completes when the subscriber stream completes and is made
    // available as materialized value of this sink.
    val sink2: AkkaSink[ActorRef, Future[Done]] = AkkaFlow[ActorRef]
      .map(subscriberStream[I](_).to(sink).run.unsafeRunAsyncFuture())
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
  def fs2PipeToAkkaFlow[I, O](pipe: Pipe[Task, I, O])(implicit executionContext: ExecutionContext): Graph[FlowShape[I, O], NotUsed] = {
    val source = AkkaSource.actorPublisher(AkkaStreamPublisher.props[O])
    val sink1: AkkaSink[I, ActorRef] = AkkaSink.actorSubscriber(AkkaStreamSubscriber.props[I])
    // A sink that runs an FS2 transformerStream when consuming the publisher actor (= materialized value) of source
    // and the subscriber actor (= materialized value) of sink1
    val sink2 = AkkaSink.foreach[(ActorRef, ActorRef)](ps => transformerStream(ps._2, ps._1, pipe).run.unsafeRunAsyncFuture())

    AkkaFlow.fromGraph(GraphDSL.create(source, sink1)(Keep.both) { implicit builder => (source, sink1) =>
      import GraphDSL.Implicits._
      builder.materializedValue ~> sink2
      FlowShape(sink1.in, source.out)
    }).mapMaterializedValue(_ => NotUsed)
  }

  private def subscriberStream[O](subscriber: ActorRef)(implicit executionContext: ExecutionContext): Stream[Task, O] = {
    val subscriberTask = Task.async((callback: Callback[Option[O]]) => subscriber ! Request(callback))
    Stream.repeatEval(subscriberTask).through(pipe.unNoneTerminate)
      .onFinalize {
        Task.delay {
          subscriber ! PoisonPill
        }
      }
  }

  private def publisherStream[I](publisher: ActorRef, stream: Stream[Task, I])(implicit executionContext: ExecutionContext): Stream[Task, Unit] = {
    def publisherTask(i: I): Task[Option[Unit]] = Task.async((callback: Callback[Option[Unit]]) => publisher ! Next(i, callback))(strategy)
    stream.flatMap(i => Stream.eval(publisherTask(i))).through(pipe.unNoneTerminate)
      .onError { ex =>
        publisher ! Error(ex)
        Stream.fail(ex)
      }
      .onFinalize {
        Task.delay {
          publisher ! Complete
          publisher ! PoisonPill
        }
      }
  }

  private def transformerStream[I, O](subscriber: ActorRef, publisher: ActorRef, stream: Stream[Task, I])(implicit executionContext: ExecutionContext): Stream[Task, O] =
    publisherStream[I](publisher, stream).either(subscriberStream[O](subscriber)).collect { case Right(elem) => elem }

  private def transformerStream[I, O](subscriber: ActorRef, publisher: ActorRef, pipe: Pipe[Task, I, O])(implicit executionContext: ExecutionContext): Stream[Task, Unit] =
    subscriberStream[I](subscriber).through(pipe).to(s => publisherStream(publisher, s))

  private implicit def strategy(implicit executionContext: ExecutionContext): Strategy =
    Strategy.fromExecutionContext(executionContext)
}

trait ConverterDsl extends Converter {
  implicit class AkkaSourceDsl[O, M](source: Graph[SourceShape[O], M]) {
    /** @see [[Converter#akkaSourceToFs2Stream]] */
    def toStream(onMaterialization: M => Unit = _ => ())(implicit executionContext: ExecutionContext, materializer: Materializer): Stream[Task, O] =
      akkaSourceToFs2Stream(source)(onMaterialization)
  }

  implicit class AkkaSinkDsl[I, M](sink: Graph[SinkShape[I], M]) {
    /** @see [[Converter#akkaSinkToFs2Sink]] */
    def toSink(onMaterialization: M => Unit = _ => ())(implicit executionContext: ExecutionContext, materializer: Materializer): Sink[Task, I] =
      akkaSinkToFs2Sink(sink)(onMaterialization)
  }

  implicit class AkkaFlowDsl[I, O, M](flow: Graph[FlowShape[I, O], M]) {
    /** @see [[Converter#akkaFlowToFs2Pipe]] */
    def toPipe(onMaterialization: M => Unit = _ => ())(implicit executionContext: ExecutionContext, materializer: Materializer): Pipe[Task, I, O] =
      akkaFlowToFs2Pipe(flow)(onMaterialization)
  }

  implicit class FS2StreamNothingDsl[O](stream: Stream[Nothing, O]) {
    /** @see [[Converter#fs2StreamToAkkaSource]] */
    def toSource(implicit executionContext: ExecutionContext): Graph[SourceShape[O], NotUsed] =
      fs2StreamToAkkaSource(stream)
  }

  implicit class FS2StreamPureDsl[O](stream: Stream[Pure, O]) {
    /** @see [[Converter#fs2StreamToAkkaSource]] */
    def toSource(implicit executionContext: ExecutionContext): Graph[SourceShape[O], NotUsed] =
      fs2StreamToAkkaSource(stream)
  }

  implicit class FS2StreamTaskDsl[O](stream: Stream[Task, O])(implicit executionContext: ExecutionContext) {
    /** @see [[Converter#fs2StreamToAkkaSource]] */
    def toSource(implicit executionContext: ExecutionContext): Graph[SourceShape[O], NotUsed] =
      fs2StreamToAkkaSource(stream)
  }

  implicit class FS2SinkPureDsl[I](sink: Sink[Pure, I])(implicit executionContext: ExecutionContext) {
    /** @see [[Converter#fs2SinkToAkkaSink]] */
    def toSink(implicit executionContext: ExecutionContext): Graph[SinkShape[I], Future[Done]] =
      fs2SinkToAkkaSink(sink)
  }

  implicit class FS2SinkTaskDsl[I](sink: Sink[Task, I])(implicit executionContext: ExecutionContext) {
    /** @see [[Converter#fs2SinkToAkkaSink]] */
    def toSink(implicit executionContext: ExecutionContext): Graph[SinkShape[I], Future[Done]] =
      fs2SinkToAkkaSink(sink)
  }

  implicit class FS2PipePureDsl[I, O](pipe: Pipe[Pure, I, O])(implicit executionContext: ExecutionContext) {
    /** @see [[Converter#fs2PipeToAkkaFlow]] */
    def toFlow(implicit executionContext: ExecutionContext): Graph[FlowShape[I, O], NotUsed] =
      fs2PipeToAkkaFlow(pipe)
  }

  implicit class FS2PipeTaskDsl[I, O](pipe: Pipe[Task, I, O])(implicit executionContext: ExecutionContext) {
    /** @see [[Converter#fs2PipeToAkkaFlow]] */
    def toFlow(implicit executionContext: ExecutionContext): Graph[FlowShape[I, O], NotUsed] =
      fs2PipeToAkkaFlow(pipe)
  }
}
