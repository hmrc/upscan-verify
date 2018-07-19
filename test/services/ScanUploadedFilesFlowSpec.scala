/*
 * Copyright 2018 HM Revenue & Customs
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

import java.io.ByteArrayInputStream
import java.time.Instant

import com.amazonaws.AmazonServiceException
import com.codahale.metrics.MetricRegistry
import com.kenshoo.play.metrics.Metrics
import model._
import org.mockito.ArgumentMatchers.{any, eq => meq}
import org.mockito.{ArgumentMatchers, Mockito}
import org.mockito.Mockito.{never, verify, verifyNoMoreInteractions, when}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{GivenWhenThen, Matchers}
import uk.gov.hmrc.clamav.{ClamAntiVirus, ClamAntiVirusFactory}
import uk.gov.hmrc.play.test.UnitSpec
import util.logging.LoggingDetails

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class ScanUploadedFilesFlowSpec extends UnitSpec with Matchers with GivenWhenThen with MockitoSugar {

  implicit val ld = LoggingDetails.fromString("mock")

  val parser = new MessageParser {
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
    "get messages from the queue consumer, and scan and post-process valid messages" in {
      Given("there are only valid messages in a message queue")
      val validMessage = Message("ID", "VALID-BODY", "RECEIPT-1")
      val s3object     = S3ObjectLocation("bucket", "ID", None)

      val queueConsumer = mock[QueueConsumer]
      when(queueConsumer.poll()).thenReturn(List(validMessage))
      when(queueConsumer.confirm(any())).thenReturn(Future.successful(()))

      val fileManager = mock[FileManager]

      val inboundObjectMetadata =
        InboundObjectMetadata(Map("consuming-service" -> "ScanUploadedFilesFlowSpec"), Instant.now)

      when(fileManager.getObjectMetadata(ArgumentMatchers.eq(s3object))(any())).thenReturn(Future.successful(inboundObjectMetadata))

      val fileCheckingService = mock[FileCheckingService]
      when(fileCheckingService.check(any(), any())(any()))
        .thenReturn(Future.successful(Right(FileValidationSuccess("CHECKSUM", MimeType("application/xml")))))

      val scanningResultHandler = mock[FileCheckingResultHandler]
      when(scanningResultHandler.handleCheckingResult(any(), any())(any()))
        .thenReturn(Future.successful(()))

      val metrics = metricsStub()
      val queueOrchestrator =
        new ScanUploadedFilesFlow(
          queueConsumer,
          parser,
          fileManager,
          fileCheckingService,
          scanningResultHandler,
          metrics
        )

      When("the orchestrator is called")
      Await.result(queueOrchestrator.run(), 30 seconds)

      Then("the queue consumer should poll for messages")
      verify(queueConsumer).poll()

      And("scanning result handler is called")
      verify(scanningResultHandler).handleCheckingResult(
        InboundObjectDetails(inboundObjectMetadata, "127.0.0.1", s3object),
        Right(FileValidationSuccess("CHECKSUM", MimeType("application/xml"))))

      And("the metrics should be successfully updated")
      metrics.defaultRegistry.timer("uploadToScanComplete").getSnapshot.size()    shouldBe 1
      metrics.defaultRegistry.timer("uploadToStartProcessing").getSnapshot.size() shouldBe 1
      metrics.defaultRegistry.timer("upscanVerifyProcessing").getSnapshot.size()  shouldBe 1

      And("successfully processed messages are confirmed")
      verify(queueConsumer).confirm(validMessage)
    }

    "get messages from the queue consumer, and perform scanning for valid messages and ignore invalid messages" in {
      Given("there are only valid messages in a message queue")
      val validMessage1  = Message("ID1", "VALID-BODY", "RECEIPT-1")
      val invalidMessage = Message("ID2", "INVALID-BODY", "RECEIPT-2")
      val validMessage2  = Message("ID3", "VALID-BODY", "RECEIPT-3")

      val s3object1 = S3ObjectLocation("bucket", "ID1", None)
      val s3object3 = S3ObjectLocation("bucket", "ID3", None)

      val queueConsumer = mock[QueueConsumer]
      when(queueConsumer.poll()).thenReturn(List(validMessage1, invalidMessage, validMessage2))
      when(queueConsumer.confirm(any()))
        .thenReturn(Future.successful(()))
        .thenReturn(Future.successful(()))

      val fileManager           = mock[FileManager]
      val inboundObjectMetadata = InboundObjectMetadata(Map.empty, Instant.now)
      when(fileManager.getObjectMetadata(any())(any()))
        .thenReturn(Future.successful(inboundObjectMetadata))

      val fileCheckingService = mock[FileCheckingService]
      when(fileCheckingService.check(meq(s3object1), any())(any()))
        .thenReturn(Future.successful(Right(FileValidationSuccess("CHECKSUM", MimeType("application.xml")))))
      when(fileCheckingService.check(meq(s3object3), any())(any()))
        .thenReturn(Future.successful(Left(FileInfected("infection"))))

      val scanningResultHandler = mock[FileCheckingResultHandler]
      when(
        scanningResultHandler.handleCheckingResult(
          InboundObjectDetails(inboundObjectMetadata, "127.0.0.1", s3object1),
          Right(FileValidationSuccess("CHECKSUM", MimeType("application.xml")))))
        .thenReturn(Future.successful(()))

      when(
        scanningResultHandler
          .handleCheckingResult(
            InboundObjectDetails(inboundObjectMetadata, "127.0.0.1", s3object3),
            Left(FileInfected("infection"))))
        .thenReturn(Future.successful(()))

      val metrics = metricsStub()

      val queueOrchestrator =
        new ScanUploadedFilesFlow(
          queueConsumer,
          parser,
          fileManager,
          fileCheckingService,
          scanningResultHandler,
          metrics)

      When("the orchestrator is called")
      Await.result(queueOrchestrator.run(), 30 seconds)

      Then("the queue consumer should poll for messages")
      verify(queueConsumer).poll()

      And("notification service is called only for valid messages")
      verify(scanningResultHandler).handleCheckingResult(
        InboundObjectDetails(inboundObjectMetadata, "127.0.0.1", s3object1),
        Right(FileValidationSuccess("CHECKSUM", MimeType("application.xml"))))
      verify(scanningResultHandler)
        .handleCheckingResult(
          InboundObjectDetails(inboundObjectMetadata, "127.0.0.1", s3object3),
          Left(FileInfected("infection")))
      verifyNoMoreInteractions(scanningResultHandler)

      And("successfully processed messages are confirmed")
      verify(queueConsumer).confirm(validMessage1)
      verify(queueConsumer).confirm(validMessage2)

      And("the metrics should be successfully updated")
      metrics.defaultRegistry.timer("uploadToScanComplete").getSnapshot.size()    shouldBe 2
      metrics.defaultRegistry.timer("uploadToStartProcessing").getSnapshot.size() shouldBe 2
      metrics.defaultRegistry.timer("upscanVerifyProcessing").getSnapshot.size()  shouldBe 2

      And("invalid messages are not confirmed")
      verifyNoMoreInteractions(queueConsumer)

    }

    "do not confirm valid messages for which scanning has failed" in {

      val s3object1 = S3ObjectLocation("bucket", "ID1", None)
      val s3object2 = S3ObjectLocation("bucket", "ID2", None)
      val s3object3 = S3ObjectLocation("bucket", "ID3", None)

      Given("there are only valid messages in a message queue")
      val validMessage1 = Message("ID1", "VALID-BODY", "RECEIPT-1")
      val validMessage2 = Message("ID2", "VALID-BODY", "RECEIPT-2")
      val validMessage3 = Message("ID3", "VALID-BODY", "RECEIPT-3")

      val queueConsumer = mock[QueueConsumer]
      when(queueConsumer.poll()).thenReturn(List(validMessage1, validMessage2, validMessage3))
      when(queueConsumer.confirm(any()))
        .thenReturn(Future.successful(()))
        .thenReturn(Future.successful(()))

      val inboundObjectMetadata = InboundObjectMetadata(Map.empty, Instant.now)

      val fileManager = mock[FileManager]
      when(fileManager.getObjectMetadata(any())(any())).thenReturn(Future.successful(inboundObjectMetadata))

      val fileCheckingService = mock[FileCheckingService]
      when(fileCheckingService.check(meq(s3object1), any())(any()))
        .thenReturn(Future.successful(Right(FileValidationSuccess("CHECKSUM", MimeType("application.xml")))))
      when(fileCheckingService.check(meq(s3object2), any())(any()))
        .thenReturn(Future.failed(new Exception("Planned exception")))

      when(fileCheckingService.check(meq(s3object3), any())(any()))
        .thenReturn(Future.successful(Left(FileInfected("infection"))))

      val scanningResultHandler = mock[FileCheckingResultHandler]
      when(scanningResultHandler.handleCheckingResult(any(), any())(any()))
        .thenReturn(Future.successful(()))

      val metrics = metricsStub()

      val queueOrchestrator =
        new ScanUploadedFilesFlow(
          queueConsumer,
          parser,
          fileManager,
          fileCheckingService,
          scanningResultHandler,
          metrics)

      When("the orchestrator is called")
      Await.result(queueOrchestrator.run(), 30 seconds)

      Then("the queue consumer should poll for messages")
      verify(queueConsumer).poll()

      And("scanning handler is called only for successfully scanned messages")
      verify(scanningResultHandler).handleCheckingResult(
        InboundObjectDetails(inboundObjectMetadata, "127.0.0.1", s3object1),
        Right(FileValidationSuccess("CHECKSUM", MimeType("application.xml"))))
      verify(scanningResultHandler)
        .handleCheckingResult(
          InboundObjectDetails(inboundObjectMetadata, "127.0.0.1", s3object3),
          Left(FileInfected("infection")))
      verifyNoMoreInteractions(scanningResultHandler)

      And("successfully processed messages are confirmed")
      verify(queueConsumer).confirm(validMessage1)
      verify(queueConsumer).confirm(validMessage3)

      And("the metrics should be successfully updated")
      metrics.defaultRegistry.timer("uploadToScanComplete").getSnapshot.size()    shouldBe 2
      metrics.defaultRegistry.timer("uploadToStartProcessing").getSnapshot.size() shouldBe 2
      metrics.defaultRegistry.timer("upscanVerifyProcessing").getSnapshot.size()  shouldBe 2

      And("invalid messages are not confirmed")
      verifyNoMoreInteractions(queueConsumer)

    }

    "skip processing if file metadata is unavailable" in {
      val s3object1 = S3ObjectLocation("bucket", "ID1", None)
      val s3object2 = S3ObjectLocation("bucket", "ID2", None)
      val s3object3 = S3ObjectLocation("bucket", "ID3", None)

      val client = mock[ClamAntiVirus]

      val factory = mock[ClamAntiVirusFactory]
      Mockito.when(factory.getClient()).thenReturn(client)

      Given("there are only valid messages in a message queue")
      val validMessage1 = Message("ID1", "VALID-BODY", "RECEIPT-1")
      val validMessage2 = Message("ID2", "VALID-BODY", "RECEIPT-2")
      val validMessage3 = Message("ID3", "VALID-BODY", "RECEIPT-3")

      val queueConsumer = mock[QueueConsumer]
      when(queueConsumer.poll()).thenReturn(List(validMessage1, validMessage2, validMessage3))
      when(queueConsumer.confirm(any())).thenReturn(Future.successful(()))

      val fileContentsAsBytes = "FileContents".getBytes
      val stringInputStream   = new ByteArrayInputStream(fileContentsAsBytes)

      val metadata1 = InboundObjectMetadata(Map.empty, Instant.now)
      val metadata3 = InboundObjectMetadata(Map.empty, Instant.now)

      And("the fileManager fails to return file metadata for the 2nd message")
      val fileManager = mock[FileManager]
      when(fileManager.getObjectMetadata(ArgumentMatchers.eq(s3object1))(any()))
        .thenReturn(Future.successful(metadata1))
      when(fileManager.getObjectMetadata(ArgumentMatchers.eq(s3object2))(any()))
        .thenReturn(Future.failed(new AmazonServiceException("Expected exception")))
      when(fileManager.getObjectMetadata(ArgumentMatchers.eq(s3object3))(any()))
        .thenReturn(Future.successful(metadata3))

      val fileCheckingService = mock[FileCheckingService]
      when(fileCheckingService.check(meq(s3object1), any())(any()))
        .thenReturn(Future.successful(Right(FileValidationSuccess("CHECKSUM", MimeType("application/xml")))))
      when(fileCheckingService.check(meq(s3object3), any())(any()))
        .thenReturn(Future.successful(Right(FileValidationSuccess("CHECKSUM", MimeType("application/xml")))))

      val scanningResultHandler = mock[FileCheckingResultHandler]
      when(
        scanningResultHandler.handleCheckingResult(
          InboundObjectDetails(metadata1, "127.0.0.1", s3object1),
          Right(FileValidationSuccess("CHECKSUM", MimeType("application/xml")))))
        .thenReturn(Future.successful(()))
      when(
        scanningResultHandler.handleCheckingResult(
          InboundObjectDetails(metadata3, "127.0.0.1", s3object3),
          Right(FileValidationSuccess("CHECKSUM", MimeType("application/xml")))))
        .thenReturn(Future.successful(()))

      val metrics = metricsStub()

      val queueOrchestrator =
        new ScanUploadedFilesFlow(
          queueConsumer,
          parser,
          fileManager,
          fileCheckingService,
          scanningResultHandler,
          metrics)

      When("the orchestrator is called")
      Await.result(queueOrchestrator.run(), 30 seconds)

      Then("the queue consumer should poll for messages")
      verify(queueConsumer).poll()

      And("the subsequent components should be invoked for 1st and 3rd messages")
      verify(fileCheckingService).check(meq(s3object1), any())(any())
      verify(fileCheckingService).check(meq(s3object3), any())(any())

      verify(scanningResultHandler)
        .handleCheckingResult(
          InboundObjectDetails(metadata1, "127.0.0.1", s3object1),
          Right(FileValidationSuccess("CHECKSUM", MimeType("application/xml"))))
      verify(scanningResultHandler)
        .handleCheckingResult(
          InboundObjectDetails(metadata3, "127.0.0.1", s3object3),
          Right(FileValidationSuccess("CHECKSUM", MimeType("application/xml"))))

      verify(queueConsumer).confirm(validMessage1)
      verify(queueConsumer).confirm(validMessage3)

      And("the subsequent components should not be invoked for the 2nd message")
      verify(fileCheckingService, never()).check(meq(s3object2), any())(any())
      verify(queueConsumer, never()).confirm(validMessage2)

      verifyNoMoreInteractions(fileCheckingService, scanningResultHandler, queueConsumer)

      And("the metrics should be successfully updated")
      metrics.defaultRegistry.timer("uploadToScanComplete").getSnapshot.size()    shouldBe 2
      metrics.defaultRegistry.timer("uploadToStartProcessing").getSnapshot.size() shouldBe 2
      metrics.defaultRegistry.timer("upscanVerifyProcessing").getSnapshot.size()  shouldBe 2
    }
  }
}
