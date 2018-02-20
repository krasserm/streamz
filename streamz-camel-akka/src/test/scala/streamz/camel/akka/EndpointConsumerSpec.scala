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

package streamz.camel.akka

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Keep, Source }
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.testkit.TestSubscriber
import akka.testkit.TestKit
import org.apache.camel.ExchangePattern
import org.scalatest.concurrent.Eventually
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }
import streamz.camel.{ StreamContext, StreamMessage }

import scala.concurrent.duration._
import scala.reflect.ClassTag

class EndpointConsumerSpec extends TestKit(ActorSystem("test")) with WordSpecLike with Matchers with BeforeAndAfterAll with Eventually {
  implicit val materializer = ActorMaterializer()
  implicit val context = StreamContext()

  import context._

  override def afterAll(): Unit = {
    context.stop()
    materializer.shutdown()
    TestKit.shutdownActorSystem(system)
  }

  def awaitEndpointRegistration(uri: String): Unit = {
    eventually { camelContext.getEndpoint(uri) should not be null }
  }

  def testSink[A](uri: String)(implicit tag: ClassTag[A]): TestSubscriber.Probe[StreamMessage[A]] = {
    val sink = Source.fromGraph(new EndpointConsumer[A](uri)).toMat(TestSink.probe[StreamMessage[A]])(Keep.right).run()
    awaitEndpointRegistration(uri)
    sink
  }

  "An EndpointConsumer" when {
    "consume a message from an endpoint on demand and return to waiting state if there is no further demand" in {
      val uri = "seda:q1"
      val sink = testSink[String](uri)

      val message = StreamMessage("test", Map("k1" -> "v1", "k2" -> "v2"))
      val exchange = createExchange(message, ExchangePattern.InOnly)
      val resf = producerTemplate.asyncSend(uri, exchange)

      sink.request(2)

      val req = sink.expectNext()
      req.body should be("test")
      req.headers("k1") should be("v1")
      req.headers("k2") should be("v2")

      sink.expectNoMsg

      resf.get.getIn.getBody should be("test")
      resf.get.getIn.getHeader("k1") should be("v1")
      resf.get.getIn.getHeader("k2") should be("v2")
    }
    "consume a message from an endpoint on demand and consume the next message if there is further demand" in {
      val uri = "seda:q2"
      val sink = testSink[String](uri)

      val message = StreamMessage("test")
      val exchange = createExchange(message, ExchangePattern.InOnly)

      sink.request(2)

      val f1 = producerTemplate.asyncSend(uri, exchange)
      sink.expectNext().body should be("test")

      val f2 = producerTemplate.asyncSend(uri, exchange)
      sink.expectNext().body should be("test")

      f1.get.getIn.getBody should be("test")
      f2.get.getIn.getBody should be("test")
    }
    "error the stream if a message exchange with the endpoint failed" in {
      val uri = "seda:q3"
      val sink = testSink[String](uri)

      val exchange = createExchange(StreamMessage("test"), ExchangePattern.InOnly)
      exchange.setException(new Exception("boom"))
      producerTemplate.asyncSend(uri, exchange)

      sink.request(1)
      sink.expectError().getMessage should be("boom")
    }
    "error the stream if a message exchange is not in-only" in {
      val uri = "seda:q4"
      val sink = testSink[String](uri)

      val exchange = createExchange(StreamMessage("test"), ExchangePattern.InOut)
      producerTemplate.asyncSend(uri, exchange)

      sink.request(1)
      sink.expectError().getMessage should be("Exchange pattern InOnly expected but was InOut")
    }
  }
}
