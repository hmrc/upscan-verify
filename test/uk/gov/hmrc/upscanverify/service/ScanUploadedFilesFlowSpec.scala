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

package uk.gov.hmrc.upscanverify.service

import com.amazonaws.AmazonServiceException
import com.codahale.metrics.MetricRegistry
import org.scalatest.GivenWhenThen
import org.scalatest.concurrent.ScalaFutures
import org.mockito.ArgumentMatchers.{any, eq => eqTo}
import org.mockito.Mockito.{verify, verifyNoInteractions, verifyNoMoreInteractions, when}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.logging.LoggingDetails
import uk.gov.hmrc.play.bootstrap.metrics.Metrics
import uk.gov.hmrc.upscanverify.model._
import uk.gov.hmrc.upscanverify.test.{UnitSpec, WithIncrementingClock}
import uk.gov.hmrc.upscanverify.util.logging.LoggingDetails

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ScanUploadedFilesFlowSpec
  extends UnitSpec
     with GivenWhenThen
     with WithIncrementingClock
     with ScalaFutures:

  override lazy val clockStart = Instant.parse("2018-12-04T17:48:30Z")

  given  HeaderCarrier = LoggingDetails.fromMessageContext(MessageContext("TEST"))

  val parser: MessageParser = new MessageParser:
    override def parse(message: Message) =
      message.body match
        case "VALID-BODY" => Future.successful(FileUploadEvent(S3ObjectLocation("bucket", message.id, None), "127.0.0.1"))
        case _            => Future.failed(Exception("Invalid body"))

  def metricsStub() = new Metrics:
    override val defaultRegistry: MetricRegistry =
      MetricRegistry()

  "ScanUploadedFilesFlow" should:
    "scan and post-process valid message" in:
      Given("there is a valid message in the queue")
      val message          = Message("ID", "VALID-BODY", "RECEIPT-1", clock.instant(), Some(clock.instant().minusSeconds(1)))
      val location         = S3ObjectLocation("bucket", "ID", None)
      val processingResult = VerifyResult.FileValidationSuccess(
                               "CHECKSUM",
                               MimeType("application/xml"),
                               Timings(clock.instant(), clock.instant()),
                               Timings(clock.instant(), clock.instant())
                             )
      val inboundObjectMetadata =
        InboundObjectMetadata(Map("consuming-service" -> "ScanUploadedFilesFlowSpec"), clockStart.minusSeconds(1), 0)

      val fileManager           = mock[FileManager]
      val scanningResultHandler = mock[FileCheckingResultHandler]
      val fileCheckingService   = mock[FileCheckingService]
      val metrics: Metrics      = metricsStub()
      val flow =
        ScanUploadedFilesFlow(
          parser,
          fileManager,
          fileCheckingService,
          scanningResultHandler,
          metrics,
          clock
        )

      when(fileManager.getObjectMetadata(eqTo(location))(using any[LoggingDetails]))
        .thenReturn(Future.successful(inboundObjectMetadata))

      when(fileCheckingService.check(any[S3ObjectLocation], any[InboundObjectMetadata])(using any[LoggingDetails]))
        .thenReturn(Future.successful(processingResult))

      when(scanningResultHandler.handleCheckingResult(
        any[InboundObjectDetails], any[VerifyResult], any[Instant])(using any[LoggingDetails]))
        .thenReturn(Future.unit)

      When("message is handled")
      val result = flow.processMessage(message)

      Then("processing result is success")
      result.value.futureValue.isRight shouldBe true

      And("scanning result handler is called")
      verify(scanningResultHandler)
        .handleCheckingResult(
          eqTo(InboundObjectDetails(inboundObjectMetadata, "127.0.0.1", location)),
          eqTo(processingResult),
          any[Instant]
        )(using any[LoggingDetails])

      And("the metrics should be successfully updated")
      metrics.defaultRegistry.timer("uploadToScanComplete"      ).getSnapshot.size() shouldBe 1
      metrics.defaultRegistry.timer("uploadToStartProcessing"   ).getSnapshot.size() shouldBe 1
      metrics.defaultRegistry.timer("upscanVerifyProcessing"    ).getSnapshot.size() shouldBe 1
      metrics.defaultRegistry.timer("queueSentToStartProcessing").getSnapshot.size() shouldBe 1

    "skip processing if file metadata is unavailable" in:
      val fileManager           = mock[FileManager]
      val scanningResultHandler = mock[FileCheckingResultHandler]

      Given("there is a valid message")
      val location            = S3ObjectLocation("bucket", "ID2", None)
      val message             = Message("ID2", "VALID-BODY", "RECEIPT-2", clock.instant(), None)
      val fileCheckingService = mock[FileCheckingService]
      val metrics: Metrics    = metricsStub()
      val flow =
        ScanUploadedFilesFlow(
          parser,
          fileManager,
          fileCheckingService,
          scanningResultHandler,
          metrics,
          clock
        )

      And("the fileManager fails to return file metadata for the message")

      when(fileManager.getObjectMetadata(eqTo(location))(using any[LoggingDetails]))
        .thenReturn(Future.failed(AmazonServiceException("Expected exception")))

      When("message is processed")
      val result = flow.processMessage(message)

      Then("result should be a failure")
      result.value.futureValue.isLeft shouldBe true

      And("file checking service should not be invoked")
      verifyNoMoreInteractions(fileCheckingService)

      And("the metrics should not be updated")
      metrics.defaultRegistry.timer("uploadToScanComplete"   ).getSnapshot.size() shouldBe 0
      metrics.defaultRegistry.timer("uploadToStartProcessing").getSnapshot.size() shouldBe 0
      metrics.defaultRegistry.timer("upscanVerifyProcessing" ).getSnapshot.size() shouldBe 0

    "skip processing when parsing failed" in:
      val fileManager           = mock[FileManager]
      val scanningResultHandler = mock[FileCheckingResultHandler]

      Given("there is a valid message")
      val message             = Message("ID2", "INVALID-BODY", "RECEIPT-2", clock.instant(), None)
      val fileCheckingService = mock[FileCheckingService]
      val metrics: Metrics    = metricsStub()
      val flow =
        ScanUploadedFilesFlow(
          parser,
          fileManager,
          fileCheckingService,
          scanningResultHandler,
          metrics,
          clock
        )

      When("message is processed")
      val result = flow.processMessage(message)

      Then("result should be a failure")
      result.value.futureValue.isLeft shouldBe true

      And("file checking service should not be invoked")
      verifyNoInteractions(fileCheckingService)

      And("the metrics should not be updated")
      metrics.defaultRegistry.timer("uploadToScanComplete"   ).getSnapshot.size() shouldBe 0
      metrics.defaultRegistry.timer("uploadToStartProcessing").getSnapshot.size() shouldBe 0
      metrics.defaultRegistry.timer("upscanVerifyProcessing" ).getSnapshot.size() shouldBe 0

    "return error if scanning failed" in:
      Given("there is a valid message in the queue")
      val message  = Message("ID", "VALID-BODY", "RECEIPT-1", clock.instant(), None)
      val location = S3ObjectLocation("bucket", "ID", None)
      val inboundObjectMetadata =
        InboundObjectMetadata(Map("consuming-service" -> "ScanUploadedFilesFlowSpec"), clockStart.minusSeconds(1), 0)

      val fileManager           = mock[FileManager]
      val scanningResultHandler = mock[FileCheckingResultHandler]
      val fileCheckingService   = mock[FileCheckingService]
      val metrics: Metrics      = metricsStub()
      val flow =
        ScanUploadedFilesFlow(
          parser,
          fileManager,
          fileCheckingService,
          scanningResultHandler,
          metrics,
          clock
        )

      when(fileManager.getObjectMetadata(eqTo(location))(using any[LoggingDetails]))
        .thenReturn(Future.successful(inboundObjectMetadata))

      when(fileCheckingService.check(any[S3ObjectLocation], any[InboundObjectMetadata])(using any[LoggingDetails]))
        .thenReturn(Future.failed(RuntimeException("Expected exception")))

      When("message is handled")
      val result = flow.processMessage(message)

      Then("processing result is success")
      result.value.futureValue.isLeft shouldBe true

      And("scanning result handler is not invoked")
      verifyNoInteractions(scanningResultHandler)

      And("the metrics should not be updated")
      metrics.defaultRegistry.timer("uploadToScanComplete"   ).getSnapshot.size() shouldBe 0
      metrics.defaultRegistry.timer("uploadToStartProcessing").getSnapshot.size() shouldBe 0
      metrics.defaultRegistry.timer("upscanVerifyProcessing" ).getSnapshot.size() shouldBe 0
