package streamz.akka.stream

import java.util.concurrent.{TimeUnit, CountDownLatch}

import org.scalatest.concurrent.Eventually

import scala.reflect._
import scala.util.Random
import scala.util.control.NoStackTrace
import scala.concurrent.{ExecutionContext, Future, Await}
import scala.concurrent.duration._

import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import akka.actor._
import akka.pattern.{AskTimeoutException, ask}
import akka.stream.scaladsl._
import akka.stream.ActorMaterializer
import akka.testkit._

import fs2.Task
import fs2.Stream

import streamz.akka.stream.TestAdapter.GetInFlight

class AkkaStreamSpec
    extends TestKit(ActorSystem(classOf[AkkaStreamSpec].getSimpleName)) with ImplicitSender with DefaultTimeout
    with WordSpecLike with Matchers with Eventually with BeforeAndAfterAll {

  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  "Stream.publisher" when {
    "invoked on a normal input-Stream" must {
      "return a Publisher and a Stream that publishes the elements of the input-Stream in a normal Flow" in {
        val input: Stream[Task, Int] = Stream(1 to 50: _*)

        val (process, publisher) = input.publisher()
        val published = toList(Source.fromPublisher(publisher))
        process.run.unsafeRun

        result(published) should be (input.runLog.unsafeRun)
      }
    } 
    "invoked with an actor-name" must {
      "create an actor under this name that is stopped after the stream is finished" in {
        val actorName = "publisher"
        val input: Stream[Task, Int] = Stream(1 to 50: _*)

        val (process, publisher) = input.publisher(name = Some(actorName))

        identify(actorName) should not be None

        val published = toList(Source.fromPublisher(publisher))
        process.run.unsafeRun
        result(published)

        identify(actorName) should be (None)
      }
    }
    "invoked on an erroneous input-Stream" must {
      "return a Publisher and a Stream that publishes the valid elements of the input-Stream in a normal Flow" in {
        val failAfter = 50
        val input: Stream[Task, Int] = Stream(1 to failAfter: _*) ++ Stream.fail(expectedException)

        val (process, publisher) = input.publisher()
        val published = toList(Source.fromPublisher(publisher).take(failAfter))
        process.take(failAfter).run.unsafeRun

        result(published) should be (input.take(failAfter).runLog.unsafeRun)
      }
    }
    "invoked on an erroneous input-Stream" must {
      "return a Publisher and a Stream that publishes the exception of the input-Stream in a normal Flow" in {
        val input: Stream[Task, Int] = Stream.fail(expectedException)

        val (process, publisher) = input.publisher()
        val published = toList(Source.fromPublisher(publisher))
        process.run.unsafeRun

        the[Exception] thrownBy result(published) should be (expectedException)
      }
    }
    "invoked on a slow input-Stream" must {
      "return a Publisher and a Stream that publishes the elements of the input-Stream in a normal Flow" in {
        val input: Stream[Task, Int] = Stream(1 to 50: _*)

        val (process, publisher) = input.map(sleep()).publisher()
        val published = toList(Source.fromPublisher(publisher))
        process.run.unsafeRun

        result(published) should be (input.runLog.unsafeRun)
      }
    }
    "invoked on a normal input-Stream" must {
      "return a Publisher and a Stream that publishes the elements of the input-Stream in a slow Flow" in {
        val input: Stream[Task, Int] = Stream(1 to 50: _*)

        val (process, publisher) = input.publisher()
        val published = toList(Source.fromPublisher(publisher).map(sleep()))
        process.run.unsafeRun

        result(published) should be (input.runLog.unsafeRun)
      }
    }
    "invoked on a normal input-Stream with a MaxInFlightRequestStrategy" must {
      "return a Publisher and a Stream that publishes the elements of the input-Stream in a slow Flow with the given max elements in flight" in {
        val input: Stream[Task, Int] = Stream(1 to 50: _*)
        val maxInFlight = Random.nextInt(4) + 1
        val mockActorFactory = new MockActorRefFactory(Map(classOf[AdapterPublisher[Int]] -> TestAdapterPublisher.props[Int]))

        val (process, publisher) = input.publisher(maxInFlightStrategyFactory(maxInFlight))(mockActorFactory, system.dispatcher)
        val published = toList(Source.fromPublisher(publisher)
          .map(sleep())
          .map(_ => currentInFlight[AdapterPublisher[Int]](mockActorFactory)))
        process.run.unsafeRun
        result(published).map(result).foreach(_ should be <= maxInFlight)
      }
    }
  }

  "stream.subscribe" when {
    "invoked on a normal Flow" must {
      "return a Stream that produces elements of the Flow" in {
        val input = Source(1 to 50)

        val process = input.toStream()

        process.runLog.unsafeRun should be(result(toList(input)))
      }
    }
    "invoked with an actor-name" must {
      "create an actor under this name that is stopped after the stream is finished" in {
        val actorName = "subscriber"
        val finished = new CountDownLatch(1)
        val input = Source(1 to 50)

        input.toStream(name = Some(actorName)).runLog.unsafeRunAsync(_ => finished.countDown())

        eventually(identify(actorName) should not be None)
        waitFor(finished)
        eventually(identify(actorName) should be (None))
      }
    }
    "invoked on a erroneous Flow" must {
      "return a Stream that fails with the same exception as the Flow" in {
        val input = Source.failed[Int](expectedException)

        val process = input.toStream()

        process.run.unsafeAttemptRun should be (Left(expectedException))
      }
    }
    "invoked on a erroneous Flow" must {
      "return a Stream that when slowed down produces all valid elements of the Flow and fails afterwards" in {
        val failAfter = 20
        val input = Source(1 to 50).map(exceptionOn(expectedException)(_ > failAfter))

        val process: Stream[Task, Int] = input.toStream().map(sleep()).map(effect(testActor ! _))

        process.run.unsafeAttemptRun should be (Left(expectedException))
        (1 to failAfter).foreach(i => expectMsg(i))
      }
    }
    "invoked with a normal Flow and a MaxInFlightRequestStrategy" must {
      "return a Stream that when slowed has the given max elements in flight" in {
        val input = Source(1 to 50)
        val maxInFlight = Random.nextInt(4) + 1
        val mockActorFactory = new MockActorRefFactory(Map(classOf[AdapterSubscriber[Int]] -> TestAdapterSubscriber.props[Int]))

        val process = input.toStream(maxInFlightStrategyFactory(maxInFlight))(mockActorFactory, system.dispatcher, materializer)
        val slowStream = process
          .map(sleep())
          .map(_ => currentInFlight[AdapterSubscriber[Int]](mockActorFactory))

        all(slowStream.runLog.unsafeRun.map(result)) should be <= maxInFlight
      }
    }
  }

  "stream.publish" must {
    "publish to a managed flow" in {
      val process: Stream[Task, Unit] = Stream.emits(1 to 3).publish()(Sink.foreach(testActor ! _))()
      process.run.unsafeRun
      expectMsg(1)
      expectMsg(2)
      expectMsg(3)
    }
  }

  "stream.subscribe" must {
    "subscribe to a flow" in {
      val process = Source(1 to 3).toStream()
      process.runLog.unsafeRun should be(Seq(1, 2, 3))
    }
  }

  private def toList[O, Mat](publisher: Source[O, Mat])(implicit executionContext: ExecutionContext): Future[List[O]] =
    publisher.runWith(Sink.fold[List[O], O](Nil)(_ :+ _))

  private def currentInFlight[A : ClassTag](mockActorFactory: MockActorRefFactory): Future[Int] =
    (mockActorFactory.createdActor[A] ? GetInFlight).mapTo[Int]

  private def exceptionOn[A](ex: Exception)(condition: A => Boolean): A => A = effect[A](a => if(condition(a)) throw ex)

  private def sleep[A](pause: FiniteDuration = 10.millis): A => A = effect(_ => Thread.sleep(pause.toMillis))

  private def effect[A](eff: A => Unit): A => A = { a =>
    eff(a)
    a
  }

  private def result[A](future: Future[A]): A = Await.result(future, timeout.duration * 0.9)

  private def waitFor(latch: CountDownLatch) = latch.await(timeout.duration.toMillis, TimeUnit.MILLISECONDS)

  private def identify(name: String): Option[ActorRef] = result {
    (system.actorSelection(s"/user/$name") ? Identify(None)).mapTo[ActorIdentity]
      .map(id => Option(id.getRef))
      .recover { case ex: AskTimeoutException => None}
  }

  private val expectedException = new Exception with NoStackTrace

  override protected def afterAll() = shutdown(system)
}
