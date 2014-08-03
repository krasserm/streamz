package streamz.akka.stream

import scala.util.{Random, Try}
import scala.concurrent.{ExecutionContext, Future, Await}
import scala.concurrent.duration._

import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.testkit.{DefaultTimeout, ImplicitSender, TestKit}
import akka.stream.scaladsl.Flow
import akka.stream.{FlowMaterializer, MaterializerSettings}

import scalaz.concurrent.Task
import scalaz.stream.Process

import streamz.akka.stream.TestAdapterProducer.{GetState, State}


class AkkaStreamSpec extends TestKit(ActorSystem(classOf[AkkaStreamSpec].getSimpleName)) with ImplicitSender with DefaultTimeout
    with WordSpecLike with Matchers with BeforeAndAfterAll {

  import system.dispatcher

  val materializer = FlowMaterializer(MaterializerSettings())

  "stream.asProducer" when {
    "invoked with a normal input-Process" must {
      "return a Producer and a Process that produces the elements of the input-Process in a normal Flow" in {
        val input: Process[Task, Int] = Process(1 to 1000: _*)

        val (process, producer) = asProducer(input)
        val produced = toList(Flow(producer))
        process.run.run

        result(produced) should be (input.runLog.run)
      }
    } 
    "invoked with a slow input-Process" must {
      "return a Producer and a Process that produces the elements of the input-Process in a normal Flow" in {
        val input: Process[Task, Int] = Process(1 to 50: _*)

        val (process, producer) = asProducer(input.map(sleep()))
        val produced = toList(Flow(producer))
        process.run.run

        result(produced) should be (input.runLog.run)
      }
    }
    "invoked with a normal input-Process" must {
      "return a Producer and a Process that produces the elements of the input-Process in a slow Flow" in {
        val input: Process[Task, Int] = Process(1 to 50: _*)

        val (process, producer) = asProducer(input)
        val produced = toList(Flow(producer).map(sleep()))
        process.run.run

        result(produced) should be (input.runLog.run)
      }
    }
    "invoked with a normal input-Process and a MaxInFlightRequestStrategy" must {
      "return a Producer and a Process that produces the elements of the input-Process in a slow Flow with max elements in flight" in {
        val input: Process[Task, Int] = Process(1 to 50: _*)
        val maxInFlight = Random.nextInt(4) + 1
        val strategyFactory = maxInFlightStrategyFactory(maxInFlight)
        val mockActorFactory = new MockActorRefFactory(Map(classOf[AdapterProducer[Int]] -> TestAdapterProducer.props(strategyFactory)))

        val (process, producer) = asProducer(input, strategyFactory = maxInFlightStrategyFactory(maxInFlight))(mockActorFactory)
        val produced = toList(Flow(producer)
          .map(sleep())
          .map(check(currentInFlight(mockActorFactory) should be <= maxInFlight)))
        process.run.run

        result(produced).foreach(_.get)
      }
    }
  }

  private def toList[A](flow: Flow[A])(implicit executionContext: ExecutionContext): Future[List[A]] =
    flow.fold[List[A]](Nil)(_:+_).toFuture(materializer)

  private def currentInFlight(mockActorFactory: MockActorRefFactory): Int = {
    result((mockActorFactory.createdActor[AdapterProducer[Int]] ? GetState).mapTo[State[_]]).elements.size
  }

  private def sleep[A](pause: FiniteDuration = 10.millis)(a: A): A = check(Thread.sleep(pause.toMillis))(a).get

  private def check[A](check: => Unit)(a: A): Try[A] = Try {
    check
    a
  }

  private def result[A](future: Future[A]): A = Await.result(future, timeout.duration)

  override protected def afterAll() = shutdown(system)
}
