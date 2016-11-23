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

package streamz.camel.akkadsl

import akka.actor.{ Props, ActorRef, ActorSystem }
import akka.stream.ActorMaterializer
import akka.stream.actor.ActorPublisherMessage.Request
import akka.stream.scaladsl._
import akka.stream.testkit.TestSubscriber
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.{ TestProbe, TestKit }

import org.apache.camel.{ Producer, ExchangePattern }
import org.scalatest._

import streamz.camel.{ StreamMessage, StreamContext }
import streamz.camel.akkadsl.EndpointConsumer.ConsumeSuccess

import scala.reflect.ClassTag

object EndpointConsumerSpec {
  class TestEndpointConsumer[O](uri: String, probe: ActorRef)(implicit streamContext: StreamContext, tag: ClassTag[O]) extends EndpointConsumer[O](uri) {
    override def waiting: Receive = {
      case r: Request =>
        probe ! r
        super.waiting(r)
    }

    override def consuming: Receive = {
      case s: ConsumeSuccess =>
        super.consuming(s)
        if (totalDemand > 0) probe ! "continue" else probe ! "return"
      case m if super.consuming.isDefinedAt(m) =>
        super.consuming(m)
    }
  }
}

class EndpointConsumerSpec extends TestKit(ActorSystem("test")) with WordSpecLike with Matchers with BeforeAndAfterAll {
  import EndpointConsumerSpec._

  implicit val materializer = ActorMaterializer()
  implicit val context = StreamContext()

  import context._

  override def afterAll(): Unit = {
    context.stop()
    materializer.shutdown()
    TestKit.shutdownActorSystem(system)
  }

  def endpointProducer(uri: String): Producer = {
    val endpoint = camelContext.getEndpoint(uri)
    val producer = endpoint.createProducer()

    endpoint.start()
    producer.start()

    producer
  }

  def actorPublisherAndTestSink[A](uri: String, probe: TestProbe = TestProbe())(implicit tag: ClassTag[A]): (ActorRef, TestSubscriber.Probe[StreamMessage[A]]) =
    Source.actorPublisher(Props(new TestEndpointConsumer[A](uri, probe.ref))).toMat(TestSink.probe[StreamMessage[A]])(Keep.both).run()

  "An EndpointConsumer" when {
    "consume a message from an endpoint on demand and return to waiting state if there is no further demand" in {
      val uri = "seda:q1"

      val probe = TestProbe()
      val producer = endpointProducer(uri)
      val (publisher, sink) = actorPublisherAndTestSink[String](uri, probe)

      val message = StreamMessage(body = "test", headers = Map("k1" -> "v1", "k2" -> "v2"))
      val exchange = createExchange(message, ExchangePattern.InOnly)

      producer.process(exchange)

      sink.request(1)
      probe.expectMsg(Request(1))
      sink.expectNext(message)
      probe.expectMsg("return")
    }
    "consume a message from an endpoint on demand and consume the next message if there is further demand" in {
      val uri = "seda:q2"

      val probe = TestProbe()
      val producer = endpointProducer(uri)
      val (publisher, sink) = actorPublisherAndTestSink[String](uri, probe)

      val message = StreamMessage(body = "test", headers = Map("k1" -> "v1", "k2" -> "v2"))
      val exchange = createExchange(message, ExchangePattern.InOnly)

      producer.process(exchange)
      producer.process(exchange)

      sink.request(2)
      probe.expectMsg(Request(2))
      sink.expectNext(message)
      probe.expectMsg("continue")
      sink.expectNext(message)
      probe.expectMsg("return")
    }
    "error the stream if a message exchange with the endpoint failed" in {
      val uri = "seda:q3"

      val producer = endpointProducer(uri)
      val (publisher, sink) = actorPublisherAndTestSink[String](uri)

      val message = StreamMessage(body = "test")
      val exchange = createExchange(message, ExchangePattern.InOnly)

      exchange.setException(new Exception("boom"))

      producer.process(exchange)
      sink.request(1)
      sink.expectError().getMessage should be("boom")
    }
  }
}
