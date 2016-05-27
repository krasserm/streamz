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

import scalaz.concurrent.Task
import scalaz.stream.Process
import scalaz.syntax.either._

import streamz.akka.stream.TestAdapter.GetInFlight

class AkkaStreamSpec
    extends TestKit(ActorSystem(classOf[AkkaStreamSpec].getSimpleName)) with ImplicitSender with DefaultTimeout
    with WordSpecLike with Matchers with Eventually with BeforeAndAfterAll {

  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  "Process.publisher" when {
    "invoked on a normal input-Process" must {
      "return a Publisher and a Process that publishes the elements of the input-Process in a normal Flow" in {
        val input: Process[Task, Int] = Process(1 to 50: _*)

        val (process, publisher) = input.publisher()
        val published = toList(Source.fromPublisher(publisher))
        process.run.unsafePerformSync

        result(published) should be (input.runLog.unsafePerformSync)
      }
    } 
    "invoked with an actor-name" must {
      "create an actor under this name that is stopped after the stream is finished" in {
        val actorName = "publisher"
        val input: Process[Task, Int] = Process(1 to 50: _*)

        val (process, publisher) = input.publisher(name = Some(actorName))

        identify(actorName) should not be None

        val published = toList(Source.fromPublisher(publisher))
        process.run.unsafePerformSync
        result(published)

        identify(actorName) should be (None)
      }
    }
    "invoked on an erroneous input-Process" must {
      "return a Publisher and a Process that publishes the valid elements of the input-Process in a normal Flow" in {
        val failAfter = 20
        val input: Process[Task, Int] = Process(1 to 50: _*).map(exceptionOn(expectedException)(_ > failAfter))

        val (process, publisher) = input.publisher()
        val published = toList(Source.fromPublisher(publisher).take(failAfter))
        process.take(failAfter).run.unsafePerformSync

        result(published) should be (input.take(failAfter).runLog.unsafePerformSync)
      }
    }
    "invoked on an erroneous input-Process" must {
      "return a Publisher and a Process that publishes the exception of the input-Process in a normal Flow" in {
        val input: Process[Task, Int] = Process.fail(expectedException)

        val (process, publisher) = input.publisher()
        val published = toList(Source.fromPublisher(publisher))
        process.run.unsafePerformSyncAttempt

        the[Exception] thrownBy result(published) should be (expectedException)
      }
    }
    "invoked on a slow input-Process" must {
      "return a Publisher and a Process that  publishes the elements of the input-Process in a normal Flow" in {
        val input: Process[Task, Int] = Process(1 to 50: _*)

        val (process, publisher) = input.map(sleep()).publisher()
        val published = toList(Source.fromPublisher(publisher))
        process.run.unsafePerformSync

        result(published) should be (input.runLog.unsafePerformSync)
      }
    }
    "invoked on a normal input-Process" must {
      "return a Publisher and a Process that  publishes the elements of the input-Process in a slow Flow" in {
        val input: Process[Task, Int] = Process(1 to 50: _*)

        val (process, publisher) = input.publisher()
        val published = toList(Source.fromPublisher(publisher).map(sleep()))
        process.run.unsafePerformSync

        result(published) should be (input.runLog.unsafePerformSync)
      }
    }
    "invoked on a normal input-Process with a MaxInFlightRequestStrategy" must {
      "return a Publisher and a Process that publishes the elements of the input-Process in a slow Flow with the given max elements in flight" in {
        val input: Process[Task, Int] = Process(1 to 50: _*)
        val maxInFlight = Random.nextInt(4) + 1
        val mockActorFactory = new MockActorRefFactory(Map(classOf[AdapterPublisher[Int]] -> TestAdapterPublisher.props[Int]))

        val (process, publisher) = input.publisher(maxInFlightStrategyFactory(maxInFlight))(mockActorFactory)
        val published = toList(Source.fromPublisher(publisher)
          .map(sleep())
          .map(_ => currentInFlight[AdapterPublisher[Int]](mockActorFactory)))
        process.run.unsafePerformSync
        result(published).map(result).foreach(_ should be <= maxInFlight)
      }
    }
  }

  "stream.subscribe" when {
    "invoked on a normal Flow" must {
      "return a Process that produces elements of the Flow" in {
        val input = Source(1 to 50)

        val process = input.toProcess()

        process.runLog.unsafePerformSync should be(result(toList(input)))
      }
    }
    "invoked with an actor-name" must {
      "create an actor under this name that is stopped after the stream is finished" in {
        val actorName = "subscriber"
        val finished = new CountDownLatch(1)
        val input = Source(1 to 50)

        input.toProcess(name = Some(actorName)).runLog.unsafePerformAsync(_ => finished.countDown())

        eventually(identify(actorName) should not be None)
        waitFor(finished)
        eventually(identify(actorName) should be (None))
      }
    }
    "invoked on a erroneous Flow" must {
      "return a Process that fails with the same exception as the Flow" in {
        val input = Source.failed[Int](expectedException)

        val process = input.toProcess()

        process.run.unsafePerformSyncAttempt should be (expectedException.left)
      }
    }
    "invoked on a erroneous Flow" must {
      "return a Process that when slowed down produces all valid elements of the Flow and fails afterwards" in {
        val failAfter = 20
        val input = Source(1 to 50).map(exceptionOn(expectedException)(_ > failAfter))

        val process: Process[Task, Int] = input.toProcess().map(sleep()).map(effect(testActor ! _))

        process.run.unsafePerformSyncAttempt should be (expectedException.left)
        (1 to failAfter).foreach(i => expectMsg(i))
      }
    }
    "invoked with a normal Flow and a MaxInFlightRequestStrategy" must {
      "return a Process that when slowed has the given max elements in flight" in {
        val input = Source(1 to 50)
        val maxInFlight = Random.nextInt(4) + 1
        val mockActorFactory = new MockActorRefFactory(Map(classOf[AdapterSubscriber[Int]] -> TestAdapterSubscriber.props[Int]))

        val process = input.toProcess(maxInFlightStrategyFactory(maxInFlight))(mockActorFactory, materializer)
        val slowProcess = process
          .map(sleep())
          .map(_ => currentInFlight[AdapterSubscriber[Int]](mockActorFactory))

        slowProcess.runLog.unsafePerformSync.map(result).foreach(_ should be <= maxInFlight)
      }
    }
  }

  "stream.publish" must {
    "publish to a managed flow" in {
      val process: Process[Task, Unit] = Process.emitAll(1 to 3).publish()(_.to(Sink.foreach(testActor ! _)))()
      process.run.unsafePerformSync
      expectMsg(1)
      expectMsg(2)
      expectMsg(3)
    }
  }

  "stream.subscribe" must {
    "subscribe to a flow" in {
      val process = Source(1 to 3).toProcess()
      process.runLog.unsafePerformSync should be(Seq(1, 2, 3))
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
