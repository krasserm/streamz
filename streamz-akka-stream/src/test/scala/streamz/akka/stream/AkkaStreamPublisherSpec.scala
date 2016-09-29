package streamz.akka.stream

import akka.actor._
import akka.stream.ActorMaterializer
import akka.stream.actor.ActorPublisherMessage._
import akka.stream.scaladsl._
import akka.stream.testkit.TestSubscriber
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit._

import org.scalatest._

import scala.util._

object AkkaStreamPublisherSpec {
  class TestAkkaStreamPublisher(probe: TestProbe) extends AkkaStreamPublisher[Int] {
    val delegate: Receive =
      super.receive

    override def receive = {
      case m: Request =>
        delegate(m)
        probe.ref ! m
      case m @ Cancel =>
        probe.ref ! m
      case m if delegate.isDefinedAt(m) =>
        delegate(m)
    }
  }
}

class AkkaStreamPublisherSpec extends TestKit(ActorSystem("test")) with WordSpecLike with Matchers with BeforeAndAfterAll {
  import AkkaStreamPublisherSpec._
  import AkkaStreamPublisher._

  implicit val materializer = ActorMaterializer()
  implicit val dispatcher = system.dispatcher

  override def afterAll(): Unit = {
    materializer.shutdown()
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  def callback(probe: TestProbe): Converter.Callback[Option[Unit]] =
    probe.ref ! _

  def actorPublisherAndTestSink(probe: TestProbe = TestProbe()): (ActorRef, TestSubscriber.Probe[Int]) =
    Source.actorPublisher(Props(new TestAkkaStreamPublisher(probe))).toMat(TestSink.probe[Int])(Keep.both).run()

  "An AkkaStreamPublisher" must {
    "publish an upstream element if there is demand and call back with a defined value" in {
      val probe = TestProbe()
      val (src, snk) = actorPublisherAndTestSink(probe)

      snk.request(1)
      probe.expectMsg(Request(1))
      src ! Next(1, callback(probe))
      snk.expectNext() should be(1)
      probe.expectMsg(Right(Some(())))
    }
    "buffer an upstream element if there is no demand and call back with a defined value if demand is signaled" in {
      val probe = TestProbe()
      val (src, snk) = actorPublisherAndTestSink(probe)

      src ! Next(1, callback(probe))
      snk.request(1)
      snk.expectNext() should be(1)
      probe.expectMsg(Right(Some(())))
    }
    "call back with an undefined value if cancelled on receiving an upstream element" in {
      val probe = TestProbe()
      val (src, snk) = actorPublisherAndTestSink(probe)

      snk.cancel()
      probe.expectMsg(Cancel)
      src ! Next(1, callback(probe))
      probe.expectMsg(Right(None))
    }
    "error the stream on receiving an upstream error" in {
      val (src, snk) = actorPublisherAndTestSink()

      snk.ensureSubscription()
      src ! Error(ConverterSpec.error)
      snk.expectError(ConverterSpec.error)
    }
    "complete the stream on receiving an upstream completion" in {
      val (src, snk) = actorPublisherAndTestSink()

      snk.ensureSubscription()
      src ! Complete
      snk.expectComplete()
    }
  }
}
