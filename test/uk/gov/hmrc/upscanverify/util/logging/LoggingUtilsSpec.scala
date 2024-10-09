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

package uk.gov.hmrc.upscanverify.util.logging

import org.scalatest.concurrent.ScalaFutures
import org.slf4j.MDC
import uk.gov.hmrc.upscanverify.service.MessageContext
import uk.gov.hmrc.upscanverify.test.UnitSpec

import scala.concurrent.{ExecutionContext, Future}

@org.scalatest.Ignore // TODO test is ignored for now - we need to test with a MDC propagating ExecutionContext
class LoggingUtilsSpec extends UnitSpec with ScalaFutures:

  import scala.concurrent.ExecutionContext.Implicits.global

  "LoggingUtils.withMdc" should:
    "add details to logging context " in:
      MDC.put("key1", "old-val")
      val loggingDetails = MessageContext("new-reference")

      LoggingUtils
        .withMdc(loggingDetails):
          Future:
            MDC.get("key1")           shouldBe "old-val"
            MDC.get("file-reference") shouldBe "new-reference"
        .futureValue

    "restore previous context if it exists" in:
      MDC.put("file-reference", "old-val")
      val loggingDetails = MessageContext("new-reference")

      LoggingUtils
        .withMdc(loggingDetails):
          //do nothing
          Future.unit
        .futureValue

      MDC.get("file-reference") shouldBe "old-val"

    "clear context if there was no previous one" in:
      MDC.clear()
      val loggingDetails = MessageContext("new-reference")

      LoggingUtils
        .withMdc(loggingDetails):
          //do nothing
          Future.unit
        .futureValue

      MDC.get("file-reference") shouldBe null
