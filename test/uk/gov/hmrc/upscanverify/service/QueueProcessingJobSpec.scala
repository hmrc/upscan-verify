/*
 * Copyright 2024 HM Revenue & Customs
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

package uk.gov.hmrc.upscanverify.service

import com.codahale.metrics.MetricRegistry
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.GivenWhenThen
import org.scalatest.concurrent.Futures.whenReady
import org.scalatest.concurrent.ScalaFutures
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.sqs.model.{Message, MessageSystemAttributeName}
import uk.gov.hmrc.upscanverify.model.{FileUploadEvent, S3ObjectLocation, Message as UpscanMessage}
import uk.gov.hmrc.upscanverify.test.UnitSpec

import java.time.Clock
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

class QueueProcessingJobSpec
  extends UnitSpec
    with GivenWhenThen
    with ScalaFutures:

  trait Test:
    val messageProcessor = mock[MessageProcessor]
    val messageParser = mock[MessageParser]
    val metricRegistry = MetricRegistry()
    val clock = Clock.systemUTC()
    val timestamp = clock.instant()
    val sentTimestamp = timestamp.toEpochMilli.toString
    val sqsMessage: Message =
      Message
        .builder()
        .messageId("some id")
        .body("some valid body")
        .receiptHandle("some receipt handle")
        .attributes(
          Map(MessageSystemAttributeName.SENT_TIMESTAMP -> sentTimestamp).asJava
        )
        .build()

    private val event =
      FileUploadEvent(
        S3ObjectLocation("bucket-name", "object-key", Some("some-version")),
        "127.0.0.1"
      )

    when(messageParser.parse(any[UpscanMessage]))
      .thenReturn(Future.successful(event))

    lazy val target =
      QueueProcessingJob(
        messageProcessor,
        messageParser,
        metricRegistry,
        clock
      )

  "QueueProcessingJob" should:
    "return a success if the job is processed" in new Test():
      Given("a valid message is retrieved from the queue")
      when(messageProcessor.processMessage(any[FileUploadEvent], any[UpscanMessage]))
        .thenReturn(Future.successful(()))

      When("the message is successfully processed")
      private val result = target.processMessage(sqsMessage)

      Then("a success flag is returned")
      whenReady(result):
        outcome => outcome shouldBe true

      And("the metrics should be successfully updated")
      metricRegistry.meter("verifyThroughput").getCount shouldBe 1

    "return a success if the S3 object cannot be found on the inbound bucket" in new Test():
      Given("a valid message is retrieved from the queue")
      private val exception =
        NoSuchKeyException
          .builder()
          .message("not found")
          .build()

      when(messageProcessor.processMessage(any[FileUploadEvent], any[UpscanMessage]))
        .thenReturn(Future.failed(exception))

      When("the message cannot be processed because it no longer exists on the inbound bucket")
      private val result = target.processMessage(sqsMessage)

      Then("a log entry is made for support purposes")
      And("a success flag is returned to prevent further retries on the message")
      whenReady(result):
        outcome => outcome shouldBe true

    "return a failure if the job in not processed" in new Test:
      Given("a valid message is retrieved from the queue")
      val exception = Exception("oh no!")

      when(messageProcessor.processMessage(any[FileUploadEvent], any[UpscanMessage]))
        .thenReturn(Future.failed(exception))

      When("the message fails to be processed")
      private val result = target.processMessage(sqsMessage)

      Then("a failure flag is returned")
      whenReady(result):
        outcome => outcome shouldBe false
