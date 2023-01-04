/*
 * Copyright 2023 HM Revenue & Customs
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

package util.logging

import org.slf4j.MDC
import test.UnitSpec
import uk.gov.hmrc.http.HeaderCarrier

class WithLoggingDetailsSpec extends UnitSpec {
  "loggingWithDetails" should {
    "add details to logging context " in {
      MDC.put("key1", "old-val")
      val loggingDetails = new HeaderCarrier() {
        override lazy val mdcData: Map[String, String] = super.mdcData + ("key1" -> "new-val") + ("key2" -> "some-val")
      }
      WithLoggingDetails.withLoggingDetails(loggingDetails) {
        MDC.get("key1") shouldBe "new-val"
        MDC.get("key2") shouldBe "some-val"
      }
    }

    "restore previous context if it exists" in {
      MDC.put("key1", "old-val")
      val loggingDetails = new HeaderCarrier() {
        override lazy val mdcData: Map[String, String] = super.mdcData + ("key1" -> "new-val") + ("key2" -> "some-val")
      }
      WithLoggingDetails.withLoggingDetails(loggingDetails) {
        //do nothing
      }
      MDC.get("key1") shouldBe "old-val"
      MDC.get("key2") shouldBe null

    }

    "clear context if there was no previous one" in {
      MDC.clear()
      val loggingDetails = new HeaderCarrier() {
        override lazy val mdcData: Map[String, String] = super.mdcData + ("key1" -> "new-val") + ("key2" -> "some-val")
      }
      WithLoggingDetails.withLoggingDetails(loggingDetails) {
        //do nothing
      }
      MDC.get("key1") shouldBe null
      MDC.get("key2") shouldBe null
    }
  }
}
