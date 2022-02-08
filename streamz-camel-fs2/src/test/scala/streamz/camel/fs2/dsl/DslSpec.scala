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

package streamz.camel.fs2.dsl

import cats.effect.IO
import fs2.Stream
import org.apache.camel.TypeConversionException
import org.apache.camel.impl.{ DefaultCamelContext, SimpleRegistry }
import org.scalatest._
import streamz.camel.StreamContext

import scala.collection.immutable.Seq
import scala.concurrent.Await
import scala.concurrent.duration._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class DslSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {
  import cats.effect.unsafe.implicits.global

  val camelRegistry = new SimpleRegistry
  val camelContext = new DefaultCamelContext()

  camelContext.setRegistry(camelRegistry)
  camelRegistry.put("service", new Service)

  implicit val streamContext = new StreamContext(camelContext)

  import streamContext._

  override protected def beforeAll(): Unit = {
    camelContext.start()
    streamContext.start()
    ()
  }

  override protected def afterAll(): Unit = {
    camelContext.stop()
    streamContext.stop()
  }

  class Service {
    def plusOne(i: Int): Int =
      if (i == -1) throw new Exception("test") else i + 1
  }

  "receive" must {
    "consume from an endpoint" in {
      1 to 3 foreach { i => producerTemplate.sendBody("seda:q1", i) }
      receiveBody[IO, Int]("seda:q1").take(3).compile.toList.unsafeRunSync() should be(Seq(1, 2, 3))
    }
    "complete with an error if type conversion fails" in {
      producerTemplate.sendBody("seda:q2", "a")
      intercept[TypeConversionException](receiveBody[IO, Int]("seda:q2").compile.drain.unsafeRunSync())
    }
  }

  "send" must {
    "send a message to an endpoint and continue with the sent message" in {
      val result = Stream(1, 2, 3).send[IO]("seda:q3").take(3).compile.toList.unsafeToFuture()
      1 to 3 foreach { i => consumerTemplate.receiveBody("seda:q3") should be(i) }
      Await.result(result, 3.seconds) should be(Seq(1, 2, 3))
    }
  }

  "sendRequest" must {
    "send a request message to an endpoint and continue with the response message" in {
      Stream(1, 2, 3).sendRequest[IO, Int]("bean:service?method=plusOne").compile.toList.unsafeRunSync() should be(Seq(2, 3, 4))
    }
    "convert response message types using a Camel type converter" in {
      Stream(1, 2, 3).sendRequest[IO, String]("bean:service?method=plusOne").compile.toList.unsafeRunSync() should be(Seq("2", "3", "4"))
    }
    "complete with an error if the request fails" in {
      intercept[Exception](Stream(-1, 2, 3).sendRequest[IO, Int]("bean:service?method=plusOne").compile.drain.unsafeRunSync()).getMessage should be("test")
    }
  }
}
