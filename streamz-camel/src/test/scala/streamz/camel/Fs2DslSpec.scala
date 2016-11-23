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

package streamz.camel

import akka.actor._
import akka.testkit.TestKit

import fs2.{ Strategy, Stream }

import streamz.camel.fs2dsl._

import org.apache.camel.TypeConversionException
import org.apache.camel.impl.{ DefaultCamelContext, SimpleRegistry }
import org.scalatest._

import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext.global

class Fs2DslSpec extends TestKit(ActorSystem("test")) with WordSpecLike with Matchers with BeforeAndAfterAll {
  val camelRegistry = new SimpleRegistry
  val camelContext = new DefaultCamelContext()

  camelContext.setRegistry(camelRegistry)
  camelRegistry.put("service", new Service)

  implicit val streamContext = new StreamContext(camelContext)
  implicit val strategy = Strategy.fromExecutionContext(global)

  import streamContext._

  override protected def beforeAll(): Unit = {
    camelContext.start()
    streamContext.start()
  }

  override protected def afterAll(): Unit = {
    camelContext.stop()
    streamContext.stop()
    TestKit.shutdownActorSystem(system)
  }

  class Service {
    def plusOne(i: Int): Int =
      if (i == -1) throw new Exception("test") else i + 1
  }

  "A receiver" must {
    "create a stream" in {
      1 to 3 foreach { i => producerTemplate.sendBody("seda:q1", i) }
      receiveBody[Int]("seda:q1").take(3).runLog.unsafeRun should be(Seq(1, 2, 3))
    }
    "complete with an error if type conversion fails" in {
      producerTemplate.sendBody("seda:q2", "a")
      intercept[TypeConversionException](receiveBody[Int]("seda:q2").run.unsafeRun)
    }
  }

  "A sender" must {
    "send to an endpoint and continue with the sent message" in {
      receiveBody[Int]("seda:q3").send("seda:q4").take(3).runLog.unsafeRunAsync {
        case Right(r) => testActor ! r
        case Left(e) =>
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
      Stream(1, 2, 3).request[Int]("bean:service?method=plusOne").runLog.unsafeRun should be(Seq(2, 3, 4))
    }
    "convert response message types using a Camel type converter" in {
      Stream("1", "2", "3").request[Int]("bean:service?method=plusOne").runLog.unsafeRun should be(Seq(2, 3, 4))
    }
    "complete with an error if the request fails" in {
      intercept[Exception](Stream(-1, 2, 3).request[Int]("bean:service?method=plusOne").run.unsafeRun).getMessage should be("test")
    }
  }
}
