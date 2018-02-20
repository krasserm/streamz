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

package streamz.camel.akka.scaladsl

import akka.actor._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Keep, Sink, Source }
import org.apache.camel.impl.{ DefaultCamelContext, SimpleRegistry }
import org.apache.camel.{ CamelExecutionException, TypeConversionException }
import org.scalatest._
import streamz.camel.StreamContext

import scala.collection.immutable.Seq
import scala.concurrent._
import scala.concurrent.duration._

object ScalaDslSpec {
  implicit class AwaitHelper[A](f: Future[A]) {
    def await: A = Await.result(f, 3.seconds)
  }
}

class ScalaDslSpec extends WordSpec with Matchers with BeforeAndAfterAll {
  import ScalaDslSpec._

  val camelRegistry = new SimpleRegistry
  val camelContext = new DefaultCamelContext()

  camelContext.setRegistry(camelRegistry)
  camelRegistry.put("service", new Service)

  implicit val streamContext = new StreamContext(camelContext)
  implicit val actorSystem = ActorSystem("test")
  implicit val actorMaterializer = ActorMaterializer()

  import streamContext._

  override protected def beforeAll(): Unit = {
    camelContext.start()
    streamContext.start()
  }

  override protected def afterAll(): Unit = {
    streamContext.stop()
    camelContext.stop()
    actorSystem.terminate()
  }

  class Service {
    def plusOne(i: Int): Int =
      if (i == -1) throw new Exception("test") else i + 1
  }

  def awaitEndpointRegistration(uri: String): Unit =
    Thread.sleep(200)

  "receive" must {
    "consume from an endpoint" in {
      1 to 3 foreach { i => producerTemplate.sendBody("seda:q1", i) }
      receiveBody[Int]("seda:q1").take(3).runWith(Sink.seq[Int]).await should be(Seq(1, 2, 3))
    }
    "complete with an error if type conversion fails" in {
      producerTemplate.sendBody("seda:q2", "a")
      intercept[TypeConversionException](receiveBody[Int]("seda:q2").runWith(Sink.ignore).await)
    }
  }

  "receiveRequest" must {
    "consume request messages from an endpoint and send reply messages to that endpoint" in {
      val uri = "direct:d1"
      receiveRequestBody[String, String](uri, capacity = 3).map(s => s"re-$s").reply.run()

      awaitEndpointRegistration(uri)
      Seq("a", "b", "c").foreach { s => producerTemplate.requestBody(uri, s) should be(s"re-$s") }
    }
    "complete with an error if type conversion fails" in {
      val uri = "direct:d2"
      val execution = receiveRequestBody[String, Int](uri, capacity = 3).map(s => s"re-$s").alsoToMat(Sink.ignore)(Keep.right).reply.run()

      awaitEndpointRegistration(uri)
      intercept[CamelExecutionException](producerTemplate.requestBody(uri, "a"))
      intercept[TypeConversionException](execution.await)
    }
  }

  "send" must {
    "send a message to an endpoint and continue with the sent message" in {
      val result = Source(Seq(1, 2, 3)).send("seda:q3").take(3).runWith(Sink.seq[Int])
      1 to 3 foreach { i => consumerTemplate.receiveBody("seda:q3") should be(i) }
      result.await should be(Seq(1, 2, 3))
    }
  }

  "sendRequest" must {
    "send a request message to an endpoint and continue with the response message" in {
      Source(Seq(1, 2, 3)).sendRequest("bean:service?method=plusOne").runWith(Sink.seq[Int]).await should be(Seq(2, 3, 4))
    }
    "convert response message types using a Camel type converter" in {
      Source(Seq(1, 2, 3)).sendRequest[String]("bean:service?method=plusOne").runWith(Sink.seq[String]).await should be(Seq("2", "3", "4"))
    }
    "complete with an error if the request fails" in {
      intercept[Exception](Source(Seq(-1, 2, 3)).sendRequest("bean:service?method=plusOne").runWith(Sink.ignore).await).getMessage should be("test")
    }
  }
}
