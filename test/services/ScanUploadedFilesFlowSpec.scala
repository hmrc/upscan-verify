/*
 * Copyright 2020 HM Revenue & Customs
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

package services

import java.time.Instant

import com.amazonaws.AmazonServiceException
import com.codahale.metrics.MetricRegistry
import com.kenshoo.play.metrics.Metrics
import model._
import org.mockito.ArgumentMatchers.{any, eq => meq}
import org.mockito.Mockito.{verify, verifyNoMoreInteractions, when}
import org.mockito.{ArgumentMatchers, Mockito}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{GivenWhenThen, Matchers}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.test.UnitSpec
import util.logging.LoggingDetails
import utils.WithIncrementingClock

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class ScanUploadedFilesFlowSpec extends UnitSpec with Matchers with GivenWhenThen with MockitoSugar with WithIncrementingClock {

  override lazy val clockStart = Instant.parse("2018-12-04T17:48:30Z")

  implicit val ld: HeaderCarrier = LoggingDetails.fromMessageContext(MessageContext("TEST"))

  val parser: MessageParser = new MessageParser {
    override def parse(message: Message) = message.body match {
      case "VALID-BODY" => Future.successful(FileUploadEvent(S3ObjectLocation("bucket", message.id, None), "127.0.0.1"))
      case _            => Future.failed(new Exception("Invalid body"))
    }
  }

  def metricsStub() = new Metrics {
    override val defaultRegistry: MetricRegistry = new MetricRegistry

    override def toJson: String = ???
  }

  "ScanUploadedFilesFlow" should {
    "scan and post-process valid message" in {
      Given("there is a valid message in the queue")
      val message          = Message("ID", "VALID-BODY", "RECEIPT-1", clock.instant())
      val location         = S3ObjectLocation("bucket", "ID", None)
      val processingResult = Right(FileValidationSuccess("CHECKSUM", MimeType("application/xml"),
        Timings(clock.instant(), clock.instant()), Timings(clock.instant(), clock.instant())))
      val inboundObjectMetadata =
        InboundObjectMetadata(Map("consuming-service" -> "ScanUploadedFilesFlowSpec"), clockStart.minusSeconds(1), 0)

      val fileManager           = mock[FileManager]
      val scanningResultHandler = mock[FileCheckingResultHandler]
      val fileCheckingService   = mock[FileCheckingService]
      val metrics: Metrics      = metricsStub()
      val flow =
        new ScanUploadedFilesFlow(
          parser,
          fileManager,
          fileCheckingService,
          scanningResultHandler,
          metrics,
          clock
        )

      when(fileManager.getObjectMetadata(meq(location))(any()))
        .thenReturn(Future.successful(inboundObjectMetadata))

      when(fileCheckingService.check(any(), any())(any()))
        .thenReturn(Future.successful(processingResult))

      when(scanningResultHandler.handleCheckingResult(any(), any(), any())(any()))
        .thenReturn(Future.successful(()))

      When("message is handled")
      val result = flow.processMessage(message)

      Then("processing result is success")
      Await.result(result.value, 10 seconds).isRight shouldBe true

      And("scanning result handler is called")
      verify(scanningResultHandler)
        .handleCheckingResult(
          meq(InboundObjectDetails(inboundObjectMetadata, "127.0.0.1", location)),
          meq(processingResult),
          ArgumentMatchers.any[Instant]())(any())

      And("the metrics should be successfully updated")
      metrics.defaultRegistry.timer("uploadToScanComplete").getSnapshot.size()    shouldBe 1
      metrics.defaultRegistry.timer("uploadToStartProcessing").getSnapshot.size() shouldBe 1
      metrics.defaultRegistry.timer("upscanVerifyProcessing").getSnapshot.size()  shouldBe 1

    }

    "skip processing if file metadata is unavailable" in {

      val fileManager           = mock[FileManager]
      val scanningResultHandler = mock[FileCheckingResultHandler]

      Given("there is a valid message")
      val location            = S3ObjectLocation("bucket", "ID2", None)
      val message             = Message("ID2", "VALID-BODY", "RECEIPT-2", clock.instant())
      val fileCheckingService = mock[FileCheckingService]
      val metrics: Metrics    = metricsStub()
      val flow =
        new ScanUploadedFilesFlow(
          parser,
          fileManager,
          fileCheckingService,
          scanningResultHandler,
          metrics,
          clock
        )

      And("the fileManager fails to return file metadata for the message")

      when(fileManager.getObjectMetadata(meq(location))(any()))
        .thenReturn(Future.failed(new AmazonServiceException("Expected exception")))

      When("message is processed")
      val result = flow.processMessage(message)

      Then("result should be a failure")
      Await.result(result.value, 10 seconds).isLeft shouldBe true

      And("file checking service should not be invoked")
      verifyNoMoreInteractions(fileCheckingService)

      And("the metrics should not be updated")
      metrics.defaultRegistry.timer("uploadToScanComplete").getSnapshot.size()    shouldBe 0
      metrics.defaultRegistry.timer("uploadToStartProcessing").getSnapshot.size() shouldBe 0
      metrics.defaultRegistry.timer("upscanVerifyProcessing").getSnapshot.size()  shouldBe 0
    }

    "skip processing when parsing failed" in {
      val fileManager           = mock[FileManager]
      val scanningResultHandler = mock[FileCheckingResultHandler]

      Given("there is a valid message")
      val location            = S3ObjectLocation("bucket", "ID2", None)
      val message             = Message("ID2", "INVALID-BODY", "RECEIPT-2", clock.instant())
      val fileCheckingService = mock[FileCheckingService]
      val metrics: Metrics    = metricsStub()
      val inboundObjectMetadata =
        InboundObjectMetadata(Map("consuming-service" -> "ScanUploadedFilesFlowSpec"), clockStart.minusSeconds(1), 0)
      val flow =
        new ScanUploadedFilesFlow(
          parser,
          fileManager,
          fileCheckingService,
          scanningResultHandler,
          metrics,
          clock
        )

      when(fileManager.getObjectMetadata(meq(location))(any()))
        .thenReturn(Future.successful(inboundObjectMetadata))

      When("message is processed")
      val result = flow.processMessage(message)

      Then("result should be a failure")
      Await.result(result.value, 10 seconds).isLeft shouldBe true

      And("file checking service should not be invoked")
      verifyNoMoreInteractions(fileCheckingService)

      And("the metrics should not be updated")
      metrics.defaultRegistry.timer("uploadToScanComplete").getSnapshot.size()    shouldBe 0
      metrics.defaultRegistry.timer("uploadToStartProcessing").getSnapshot.size() shouldBe 0
      metrics.defaultRegistry.timer("upscanVerifyProcessing").getSnapshot.size()  shouldBe 0
    }

    "return error if scanning failed" in {
      Given("there is a valid message in the queue")
      val message  = Message("ID", "VALID-BODY", "RECEIPT-1", clock.instant())
      val location = S3ObjectLocation("bucket", "ID", None)
      val inboundObjectMetadata =
        InboundObjectMetadata(Map("consuming-service" -> "ScanUploadedFilesFlowSpec"), clockStart.minusSeconds(1), 0)

      val fileManager           = mock[FileManager]
      val scanningResultHandler = mock[FileCheckingResultHandler]
      val fileCheckingService   = mock[FileCheckingService]
      val metrics: Metrics      = metricsStub()
      val flow =
        new ScanUploadedFilesFlow(
          parser,
          fileManager,
          fileCheckingService,
          scanningResultHandler,
          metrics,
          clock
        )

      when(fileManager.getObjectMetadata(meq(location))(any()))
        .thenReturn(Future.successful(inboundObjectMetadata))

      when(fileCheckingService.check(any(), any())(any()))
        .thenReturn(Future.failed(new RuntimeException("Expected exception")))

      When("message is handled")
      val result = flow.processMessage(message)

      Then("processing result is success")
      Await.result(result.value, 10 seconds).isLeft shouldBe true

      And("scanning result handler is not invoked")
      Mockito.verifyZeroInteractions(scanningResultHandler)

      And("the metrics should not be updated")
      metrics.defaultRegistry.timer("uploadToScanComplete").getSnapshot.size()    shouldBe 0
      metrics.defaultRegistry.timer("uploadToStartProcessing").getSnapshot.size() shouldBe 0
      metrics.defaultRegistry.timer("upscanVerifyProcessing").getSnapshot.size()  shouldBe 0
    }

  }
}
