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

import com.codahale.metrics.MetricRegistry
import org.mockito.ArgumentMatchers.{any, eq => eqTo}
import org.mockito.Mockito.{verify, verifyNoInteractions, verifyNoMoreInteractions, when}
import org.scalatest.GivenWhenThen
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import software.amazon.awssdk.services.sqs.model.SqsException
import uk.gov.hmrc.upscanverify.model._
import uk.gov.hmrc.upscanverify.test.{UnitSpec, WithIncrementingClock}

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ScanUploadedFilesFlowSpec
  extends UnitSpec
     with GivenWhenThen
     with WithIncrementingClock
     with ScalaFutures
     with IntegrationPatience:

  override lazy val clockStart = Instant.parse("2018-12-04T17:48:30Z")

  "ScanUploadedFilesFlow" should:
    "scan and post-process valid message" in:
      Given("there is a valid message in the queue")
      val message          = Message("ID", "VALID-BODY", "RECEIPT-1", clock.instant(), Some(clock.instant().minusSeconds(1)))
      val fileUploadEvent  = FileUploadEvent(S3ObjectLocation("bucket", message.id, None), "127.0.0.1")
      val location         = S3ObjectLocation("bucket", "ID", None)
      val processingResult = VerifyResult.FileValidationSuccess(
                               VirusScanResult.NoVirusFound("CHECKSUM", Timings(clock.instant(), clock.instant())),
                               FileAllowed(MimeType("application/xml"), Timings(clock.instant(), clock.instant()))
                             )
      val inboundObjectMetadata =
        InboundObjectMetadata(Map("consuming-service" -> "ScanUploadedFilesFlowSpec"), clockStart.minusSeconds(1), 0)

      val fileManager           = mock[FileManager]
      val scanningResultHandler = mock[FileCheckingResultHandler]
      val fileCheckingService   = mock[FileCheckingService]
      val metricRegistry        = MetricRegistry()
      val flow =
        ScanUploadedFilesFlow(
          fileManager,
          fileCheckingService,
          scanningResultHandler,
          metricRegistry,
          clock
        )

      when(fileManager.getObjectMetadata(eqTo(location)))
        .thenReturn(Future.successful(inboundObjectMetadata))

      when(fileCheckingService.check(any[S3ObjectLocation], any[InboundObjectMetadata]))
        .thenReturn(Future.successful(Right(processingResult)))

      when(scanningResultHandler.handleCheckingResult(
        any[InboundObjectDetails], any[VerifyResult], any[Instant]))
        .thenReturn(Future.unit)

      When("message is handled")
      val result = flow.processMessage(fileUploadEvent, message)

      Then("processing result is success")
      result.futureValue

      And("scanning result handler is called")
      verify(scanningResultHandler)
        .handleCheckingResult(
          eqTo(InboundObjectDetails(inboundObjectMetadata, "127.0.0.1", location)),
          eqTo(Right(processingResult)),
          any[Instant]
        )

      And("the metrics should be successfully updated")
      metricRegistry.timer("uploadToScanComplete"      ).getSnapshot.size() shouldBe 1
      metricRegistry.timer("uploadToStartProcessing"   ).getSnapshot.size() shouldBe 1
      metricRegistry.timer("upscanVerifyProcessing"    ).getSnapshot.size() shouldBe 1
      metricRegistry.timer("queueSentToStartProcessing").getSnapshot.size() shouldBe 1

    "skip processing if file metadata is unavailable" in:
      val fileManager           = mock[FileManager]
      val scanningResultHandler = mock[FileCheckingResultHandler]

      Given("there is a valid message")
      val location            = S3ObjectLocation("bucket", "ID2", None)
      val message             = Message("ID2", "VALID-BODY", "RECEIPT-2", clock.instant(), None)
      val fileUploadEvent     = FileUploadEvent(S3ObjectLocation("bucket", message.id, None), "127.0.0.1")
      val fileCheckingService = mock[FileCheckingService]
      val metricRegistry      = MetricRegistry()
      val flow =
        ScanUploadedFilesFlow(
          fileManager,
          fileCheckingService,
          scanningResultHandler,
          metricRegistry,
          clock
        )

      And("the fileManager fails to return file metadata for the message")

      when(fileManager.getObjectMetadata(eqTo(location)))
        .thenReturn(Future.failed(SqsException.builder().message("Expected exception").build()))

      When("message is processed")
      val result = flow.processMessage(fileUploadEvent, message)

      Then("result should be a failure")
      result.failed.futureValue

      And("file checking service should not be invoked")
      verifyNoMoreInteractions(fileCheckingService)

      And("the metrics should not be updated")
      metricRegistry.timer("uploadToScanComplete"   ).getSnapshot.size() shouldBe 0
      metricRegistry.timer("uploadToStartProcessing").getSnapshot.size() shouldBe 0
      metricRegistry.timer("upscanVerifyProcessing" ).getSnapshot.size() shouldBe 0

    "return error if scanning failed" in:
      Given("there is a valid message in the queue")
      val message         = Message("ID", "VALID-BODY", "RECEIPT-1", clock.instant(), None)
      val fileUploadEvent = FileUploadEvent(S3ObjectLocation("bucket", message.id, None), "127.0.0.1")
      val location        = S3ObjectLocation("bucket", "ID", None)
      val inboundObjectMetadata =
        InboundObjectMetadata(Map("consuming-service" -> "ScanUploadedFilesFlowSpec"), clockStart.minusSeconds(1), 0)

      val fileManager           = mock[FileManager]
      val scanningResultHandler = mock[FileCheckingResultHandler]
      val fileCheckingService   = mock[FileCheckingService]
      val metricRegistry        = MetricRegistry()
      val flow =
        ScanUploadedFilesFlow(
          fileManager,
          fileCheckingService,
          scanningResultHandler,
          metricRegistry,
          clock
        )

      when(fileManager.getObjectMetadata(eqTo(location)))
        .thenReturn(Future.successful(inboundObjectMetadata))

      when(fileCheckingService.check(any[S3ObjectLocation], any[InboundObjectMetadata]))
        .thenReturn(Future.failed(RuntimeException("Expected exception")))

      When("message is handled")
      val result = flow.processMessage(fileUploadEvent, message)

      Then("processing result is success")
      result.failed.futureValue

      And("scanning result handler is not invoked")
      verifyNoInteractions(scanningResultHandler)

      And("the metrics should not be updated")
      metricRegistry.timer("uploadToScanComplete"   ).getSnapshot.size() shouldBe 0
      metricRegistry.timer("uploadToStartProcessing").getSnapshot.size() shouldBe 0
      metricRegistry.timer("upscanVerifyProcessing" ).getSnapshot.size() shouldBe 0
