package streamz.akka.camel

import akka.actor._
import akka.camel._
import akka.testkit.TestKit

import scalaz.{-\/, \/-}
import scalaz.stream.Process

import org.apache.camel.impl.SimpleRegistry
import org.scalatest._

class CamelSpec extends TestKit(ActorSystem("test")) with WordSpecLike with Matchers with BeforeAndAfterAll {
  val registry = new SimpleRegistry
  val extension = CamelExtension(system)

  val producerTemplate = extension.template
  val consumerTemplate = extension.context.createConsumerTemplate()

  override protected def beforeAll(): Unit = {
    extension.context.setRegistry(registry)
    consumerTemplate.start()
  }

  override protected def afterAll(): Unit = {
    consumerTemplate.stop()
    system.terminate()
  }

  class Service {
    def plusOne(i: Int): Int = i + 1
  }

  "A receiver" must {
    "produce a discrete stream" in {
      1 to 3 foreach { i => producerTemplate.sendBody("seda:q0", i) }
      receive[Int]("seda:q0").take(3).runLog.unsafePerformSync should be(Seq(1, 2, 3))
    }
  }

  "A terminal sender" must {
    "send to an endpoint" in {
      receive[Int]("seda:q1").send("seda:q2").take(3).run.unsafePerformAsync {
        case \/-(r) => testActor ! "done"
        case -\/(e) =>
      }
      1 to 2 foreach { i =>
        producerTemplate.sendBody("seda:q1", i)
        consumerTemplate.receiveBody("seda:q2") should be(i)
      }
      producerTemplate.sendBody("seda:q1", 3)
      expectMsg("done")
    }
  }

  "An intermediate sender" must {
    "send to an endpoint and continue with the sent message" in {
      receive[Int]("seda:q3").sendW("seda:q4").take(3).runLog.unsafePerformAsync {
        case \/-(r) => testActor ! r
        case -\/(e) =>
      }
      1 to 2 foreach { i =>
        producerTemplate.sendBody("seda:q3", i)
        consumerTemplate.receiveBody("seda:q4") should be(i)
      }
      producerTemplate.sendBody("seda:q3", 3)
      expectMsg(Seq(1, 2, 3))
    }
  }

  "A requestor" must {
    "request from an endpoint and continue with the response message" in {
      registry.put("service", new Service)
      Process(1, 2, 3).request[Int]("bean:service?method=plusOne").runLog.unsafePerformSync should be(Seq(2, 3, 4))
    }
    "convert response message types using a Camel type converter" in {
      registry.put("service", new Service)
      Process("1", "2", "3").request[Int]("bean:service?method=plusOne").runLog.unsafePerformSync should be(Seq(2, 3, 4))
    }
  }
}
