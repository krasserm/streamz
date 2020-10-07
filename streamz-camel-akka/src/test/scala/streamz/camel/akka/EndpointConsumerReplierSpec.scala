/*
 * Copyright 2014 - 2020 the original author or authors.
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
import akka.stream.Materializer
import akka.stream.scaladsl.Keep
import akka.stream.testkit.scaladsl.{ TestSink, TestSource }
import akka.stream.testkit.{ TestPublisher, TestSubscriber }
import akka.testkit.TestKit
import org.apache.camel.ExchangePattern
import org.scalatest.concurrent.Eventually
import org.scalatest.{ Assertion, BeforeAndAfterAll }
import streamz.camel.{ StreamContext, StreamMessage }
import scala.concurrent.duration._
import scala.reflect.ClassTag
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class EndpointConsumerReplierSpec extends TestKit(ActorSystem("test")) with AnyWordSpecLike with Matchers with BeforeAndAfterAll with Eventually {
  implicit val materializer = Materializer.createMaterializer(system)
  implicit val context = StreamContext()

  import context._

  override def afterAll(): Unit = {
    context.stop()
    materializer.shutdown()
    TestKit.shutdownActorSystem(system)
  }

  def awaitEndpointRegistration(uri: String): Assertion = {
    eventually { camelContext.getEndpoint(uri) should not be null }
  }

  def publisherAndSubscriber[A, B](uri: String, capacity: Int)(implicit tag: ClassTag[B]): (TestPublisher.Probe[StreamMessage[A]], TestSubscriber.Probe[StreamMessage[B]]) = {
    val pubSub = TestSource.probe[StreamMessage[A]].viaMat(new EndpointConsumerReplier[A, B](uri, capacity))(Keep.left).toMat(TestSink.probe[StreamMessage[B]])(Keep.both).run()
    awaitEndpointRegistration(uri)
    pubSub
  }

  "An EndpointConsumerReplier" must {
    // Note: `ignore` because this test is flaky.
    // See https://github.com/krasserm/streamz/issues/70 for details
    "consume a message from an endpoint and reply to that endpoint" ignore {
      val uri = "direct:d1"
      val (pub, sub) = publisherAndSubscriber[String, String](uri, 3)

      val exchange = context.createExchange(StreamMessage("a", Map("foo" -> "bar")), ExchangePattern.InOut)
      val resf = producerTemplate.asyncSend(uri, exchange)

      val req = sub.requestNext()
      req.body should be("a")
      req.headers("foo") should be("bar")

      pub.sendNext(StreamMessage("re-a", Map("foo" -> "baz")))
      resf.get.getOut.getBody should be("re-a")
      resf.get.getOut.getHeader("foo") should be("baz")
    }
    "limit the number of active requests to given capacity" in {
      val uri = "direct:d2"
      val (pub, sub) = publisherAndSubscriber[String, String](uri, capacity = 2)

      val req = Seq("a", "b", "c")
      val res = Seq("re-a", "re-b", "re-c")

      sub.request(3)

      val f1 = producerTemplate.asyncRequestBody(uri, req.head)
      sub.expectNext().body should be(req.head)

      val f2 = producerTemplate.asyncRequestBody(uri, req(1))
      sub.expectNext().body should be(req(1))

      val f3 = producerTemplate.asyncRequestBody(uri, req(2))
      sub.expectNoMessage(100.millis)

      pub.sendNext(StreamMessage(res.head))
      sub.expectNext().body should be(req(2))

      pub.sendNext(StreamMessage(res(1)))
      pub.sendNext(StreamMessage(res(2)))

      Seq(f1, f2, f3).map(_.get) should be(res)
    }
    "error the stream if a message exchange with the endpoint failed" in {
      val uri = "direct:d3"
      val (_, sub) = publisherAndSubscriber[String, String](uri, 3)

      val exchange = createExchange(StreamMessage("a"), ExchangePattern.InOut)
      exchange.setException(new Exception("boom"))
      producerTemplate.asyncSend(uri, exchange)

      sub.request(1)
      sub.expectError().getMessage should be("boom")
    }
    "error the stream if a message exchange is not in-out" in {
      val uri = "direct:d4"
      val (_, sub) = publisherAndSubscriber[String, String](uri, 3)

      val exchange = createExchange(StreamMessage("a"), ExchangePattern.InOnly)
      producerTemplate.asyncSend(uri, exchange)

      sub.request(1)
      sub.expectError().getMessage should be("Exchange pattern InOut expected but was InOnly")
    }
  }
}
