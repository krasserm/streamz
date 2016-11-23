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

import org.apache.camel.impl.{ DefaultExchange, DefaultMessage }
import org.scalatest._

class StreamMessageSpec extends WordSpec with Matchers with BeforeAndAfterAll {
  implicit val context = DefaultStreamContext()

  override def afterAll(): Unit =
    context.stop()

  val message: StreamMessage[String] =
    StreamMessage("1", Map("k1" -> "1", "k2" -> "2"))

  "A StreamMessage" must {
    "support body conversion" in {
      message.bodyAs[Int] should be(1)
    }
    "support header conversion" in {
      message.headerAs[Int]("k1") should be(1)
    }
    "support Camel Message creation" in {
      val camelMessage = message.camelMessage

      camelMessage.getBody should be("1")
      camelMessage.getHeader("k1") should be("1")
      camelMessage.getHeader("k2") should be("2")
    }
  }

  "A StreamMessage" can {
    "be created from a Camel Message and a Camel type converter" in {
      val camelExchange = new DefaultExchange(context.camelContext)
      val camelMessage = new DefaultMessage()

      camelMessage.setExchange(camelExchange)
      camelMessage.setBody(1)
      camelMessage.setHeader("k1", "1")
      camelMessage.setHeader("k2", "2")

      StreamMessage.from[String](camelMessage) should be(message)
      StreamMessage.from[Int](camelMessage) should be(message.copy(1))
      StreamMessage.from[Double](camelMessage) should be(message.copy(1.0))
    }
  }
}
