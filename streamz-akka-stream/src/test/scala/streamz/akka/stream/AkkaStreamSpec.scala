package streamz.akka.stream

import scala.util.{Random, Try}
import scala.concurrent.{ExecutionContext, Future, Await}
import scala.concurrent.duration._

import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.stream.scaladsl2._
import akka.stream.MaterializerSettings
import akka.testkit._

import scalaz.concurrent.Task
import scalaz.stream.Process

import streamz.akka.stream.TestAdapterPublisher.{GetState, State}

class AkkaStreamSpec extends TestKit(ActorSystem(classOf[AkkaStreamSpec].getSimpleName)) with ImplicitSender with DefaultTimeout with WordSpecLike with Matchers with BeforeAndAfterAll {
  implicit val materializer = FlowMaterializer(MaterializerSettings(system))

  import system.dispatcher

  "stream.publisher" when {
    "invoked with a normal input-Process" must {
      "return a Publisher and a Process that publishes the elements of the input-Process in a normal Flow" in {
        val input: Process[Task, Int] = Process(1 to 1000: _*)

        val (process, publisher) = input.publisher()
        val published = toList(FlowFrom(publisher))
        process.run.run

        result(published) should be (input.runLog.run)
      }
    } 
    "invoked with a slow input-Process" must {
      "return a Publisher and a Process that  publishes the elements of the input-Process in a normal Flow" in {
        val input: Process[Task, Int] = Process(1 to 50: _*)

        val (process, publisher) = input.map(sleep()).publisher()
        val published = toList(FlowFrom(publisher))
        process.run.run

        result(published) should be (input.runLog.run)
      }
    }
    "invoked with a normal input-Process" must {
      "return a Publisher and a Process that  publishes the elements of the input-Process in a slow Flow" in {
        val input: Process[Task, Int] = Process(1 to 50: _*)

        val (process, publisher) = input.publisher()
        val published = toList(FlowFrom(publisher).map(sleep()))
        process.run.run

        result(published) should be (input.runLog.run)
      }
    }
    "invoked with a normal input-Process and a MaxInFlightRequestStrategy" must {
      "return a Publisher and a Process that  publishes the elements of the input-Process in a slow Flow with max elements in flight" in {
        val input: Process[Task, Int] = Process(1 to 50: _*)
        val maxInFlight = Random.nextInt(4) + 1
        val strategyFactory = maxInFlightStrategyFactory(maxInFlight)
        val mockActorFactory = new MockActorRefFactory(Map(classOf[AdapterPublisher[Int]] -> TestAdapterPublisher.props(strategyFactory)))

        val (process, publisher) = input.publisher(strategyFactory = maxInFlightStrategyFactory(maxInFlight))(mockActorFactory)
        val published = toList(FlowFrom(publisher)
          .map(sleep())
          .map(check(currentInFlight(mockActorFactory) should be <= maxInFlight)))
        process.run.run

        result(published).foreach(_.get)
      }
    }
  }

  "stream.publish" must {
    "publish to a managed flow" in {
      val process: Process[Task, Unit] = Process.emitAll(1 to 3).publish()(_.withSink(ForeachSink(testActor ! _)))()
      process.run.run
      expectMsg(1)
      expectMsg(2)
      expectMsg(3)
    }
  }

  "stream.subscribe" must {
    "subscribe to a flow" in {
      val process = FlowFrom(1 to 3).toProcess()
      process.runLog.run should be(Seq(1, 2, 3))
    }
  }

  private def toList[I, O](flow: FlowWithSource[I, O])(implicit executionContext: ExecutionContext): Future[List[O]] = {
    val sink = FoldSink[List[O], O](Nil)(_ :+ _)
    val materializedFlow = flow.withSink(sink).run()
    sink.future(materializedFlow)
  }

  private def currentInFlight(mockActorFactory: MockActorRefFactory): Int = {
    result((mockActorFactory.createdActor[AdapterPublisher[Int]] ? GetState).mapTo[State[_]]).elements.size
  }

  private def sleep[A](pause: FiniteDuration = 10.millis)(a: A): A = check(Thread.sleep(pause.toMillis))(a).get

  private def check[A](check: => Unit)(a: A): Try[A] = Try {
    check
    a
  }

  private def result[A](future: Future[A]): A = Await.result(future, timeout.duration)

  override protected def afterAll() = shutdown(system)
}
