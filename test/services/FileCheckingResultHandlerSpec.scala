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

import java.io.InputStream
import java.time.{Instant, LocalDateTime, ZoneOffset}

import model._
import org.apache.commons.io.IOUtils
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.mockito.{ArgumentCaptor, ArgumentMatchers}
import org.scalatest.GivenWhenThen
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class FileCheckingResultHandlerSpec extends UnitSpec with MockitoSugar with Eventually with GivenWhenThen {

  "FileCheckingResultHandler" should {

    val checksumCalculator: ChecksumCalculator = new ChecksumCalculator {
      override def calculateChecksum(objectLocation: S3ObjectLocation): Future[String] =
        Future.successful("checksum-for-" + objectLocation.objectKey)
    }

    "Move clean file from inbound bucket to outbound bucket" in {

      val fileManager: FileManager     = mock[FileManager]
      val virusNotifier: VirusNotifier = mock[VirusNotifier]
      val handler                      = new FileCheckingResultHandler(fileManager, virusNotifier, checksumCalculator)

      Given("there is a clean file")
      val file             = S3ObjectLocation("bucket", "file")
      val expectedChecksum = checksumCalculator.calculateChecksum(file)

      val inboundObjectMetadata  = InboundObjectMetadata(Map("someKey" -> "someValue"), Instant.now())
      val outboundObjectMetadata = OutboundObjectMetadata.valid(inboundObjectMetadata, expectedChecksum)

      when(fileManager.copyToOutboundBucket(ArgumentMatchers.eq(file), any())).thenReturn(Future.successful(()))
      when(fileManager.delete(file)).thenReturn(Future.successful(()))

      When("when processing scanning result")
      Await.result(handler.handleCheckingResult(ValidFileCheckingResult(file), inboundObjectMetadata), 10 seconds)

      Then("file should be copied from inbound bucket to outbound bucket")
      verify(fileManager).copyToOutboundBucket(file, outboundObjectMetadata)

      And("file should be removed from inbound bucket")
      verify(fileManager).delete(file)

    }

    "Not delete file from outbound bucket if copying failed" in {

      val fileManager: FileManager     = mock[FileManager]
      val virusNotifier: VirusNotifier = mock[VirusNotifier]

      val handler = new FileCheckingResultHandler(fileManager, virusNotifier, checksumCalculator)

      Given("there is a clean file")
      val file             = S3ObjectLocation("bucket", "file")
      val expectedChecksum = checksumCalculator.calculateChecksum(file)

      And("copying the file would fail")
      when(fileManager.copyToOutboundBucket(ArgumentMatchers.eq(file), any()))
        .thenReturn(Future.failed(new Exception("Copy failed")))

      When("when processing scanning result")
      val inboundObjectMetadata = InboundObjectMetadata(Map.empty, Instant.now())
      val result =
        Await.ready(handler.handleCheckingResult(ValidFileCheckingResult(file), inboundObjectMetadata), 10 seconds)

      Then("original file shoudln't be deleted from inbound bucket")
      verify(fileManager)
        .copyToOutboundBucket(file, OutboundObjectMetadata.valid(inboundObjectMetadata, expectedChecksum))
      verifyNoMoreInteractions(fileManager)

      And("the whole process fails")
      result.value.get.isFailure shouldBe true

    }

    "Return failure if deleting a clean file fails" in {

      val fileManager: FileManager     = mock[FileManager]
      val virusNotifier: VirusNotifier = mock[VirusNotifier]

      val handler = new FileCheckingResultHandler(fileManager, virusNotifier, checksumCalculator)

      Given("there is a clean file")
      val file             = S3ObjectLocation("bucket", "file")
      val expectedChecksum = checksumCalculator.calculateChecksum(file)

      val inboundObjectMetadata  = InboundObjectMetadata(Map("someKey" -> "someValue"), Instant.now())
      val outboundObjectMetadata = OutboundObjectMetadata.valid(inboundObjectMetadata, expectedChecksum)

      when(fileManager.copyToOutboundBucket(file, outboundObjectMetadata)).thenReturn(Future.successful(()))
      when(fileManager.delete(file)).thenReturn(Future.failed(new RuntimeException("Expected failure")))

      When("when processing scanning result")
      val result =
        Await.ready(handler.handleCheckingResult(ValidFileCheckingResult(file), inboundObjectMetadata), 10 seconds)

      Then("the process fails")
      result.value.get.isFailure shouldBe true

    }

    "Create virus notification, add error and metadata to quarantine bucket, and delete infected file in case of virus" in {

      val fileManager: FileManager     = mock[FileManager]
      val virusNotifier: VirusNotifier = mock[VirusNotifier]

      val handler = new FileCheckingResultHandler(fileManager, virusNotifier, checksumCalculator)

      Given("there is an infected file")
      val file = S3ObjectLocation("bucket", "file")

      val inboundObjectMetadata  = InboundObjectMetadata(Map("someKey" -> "someValue"), Instant.now())
      val outboundObjectMetadata = OutboundObjectMetadata.invalid(inboundObjectMetadata)

      val details = "There is a virus"

      when(virusNotifier.notifyFileInfected(any(), any())).thenReturn(Future.successful(()))
      when(fileManager.writeToQuarantineBucket(ArgumentMatchers.eq(file), any(), any()))
        .thenReturn(Future.successful(()))
      when(fileManager.delete(file)).thenReturn(Future.successful(()))

      When("scanning infected file")
      Await.result(
        handler.handleCheckingResult(FileInfectedCheckingResult(file, details), inboundObjectMetadata),
        10 seconds)

      Then("notification is created")
      verify(virusNotifier).notifyFileInfected(file, details)

      And("file metadata and error details are stored in the quarantine bucket")
      val captor: ArgumentCaptor[InputStream] = ArgumentCaptor.forClass(classOf[InputStream])
      verify(fileManager)
        .writeToQuarantineBucket(
          ArgumentMatchers.eq(file),
          captor.capture(),
          ArgumentMatchers.eq(outboundObjectMetadata))
      IOUtils.toString(captor.getValue) shouldBe """{"failureReason":"QUARANTINE","message":"There is a virus"}"""

      And("infected file is deleted")
      verify(fileManager).delete(file)
      verifyNoMoreInteractions(fileManager)
    }

    "Do not delete infected file if notification creation failed (so that we are able to retry)" in {
      val fileManager: FileManager     = mock[FileManager]
      val virusNotifier: VirusNotifier = mock[VirusNotifier]

      val handler = new FileCheckingResultHandler(fileManager, virusNotifier, checksumCalculator)

      Given("there is an infected file")
      val file             = S3ObjectLocation("bucket", "file")
      val expectedChecksum = checksumCalculator.calculateChecksum(file)

      val inboundObjectMetadata  = InboundObjectMetadata(Map("someKey" -> "someValue"), Instant.now())
      val outboundObjectMetadata = OutboundObjectMetadata.valid(inboundObjectMetadata, expectedChecksum)

      Given("notification service fails")
      when(virusNotifier.notifyFileInfected(any(), any()))
        .thenReturn(Future.failed(new Exception("Notification failed")))
      when(fileManager.delete(file)).thenReturn(Future.successful(()))

      When("scanning infected file")
      val result =
        Await.ready(
          handler.handleCheckingResult(FileInfectedCheckingResult(file, "There is a virus"), inboundObjectMetadata),
          10 seconds)

      Then("file is not deleted")
      verifyZeroInteractions(fileManager)

      And("the whole process fails")
      result.value.get.isFailure shouldBe true

    }

    "Return failure if deleting a infected file fails" in {

      val fileManager: FileManager     = mock[FileManager]
      val virusNotifier: VirusNotifier = mock[VirusNotifier]

      val handler = new FileCheckingResultHandler(fileManager, virusNotifier, checksumCalculator)

      Given("there is an infected file")
      val file             = S3ObjectLocation("bucket", "file")
      val expectedChecksum = checksumCalculator.calculateChecksum(file)

      val inboundObjectMetadata  = InboundObjectMetadata(Map("someKey" -> "someValue"), Instant.now())
      val outboundObjectMetadata = OutboundObjectMetadata.valid(inboundObjectMetadata, expectedChecksum)

      when(virusNotifier.notifyFileInfected(any(), any())).thenReturn(Future.successful(()))
      when(fileManager.delete(file)).thenReturn(Future.failed(new RuntimeException("Expected failure")))

      When("when processing scanning result")
      val result =
        Await.ready(
          handler.handleCheckingResult(FileInfectedCheckingResult(file, "There is a virus"), inboundObjectMetadata),
          10 seconds)

      Then("the process fails")
      result.value.get.isFailure shouldBe true

    }

    "Add error and metadata to quarantine bucket, and delete incorrect file in case of invalid file types" in {
      val fileManager: FileManager     = mock[FileManager]
      val virusNotifier: VirusNotifier = mock[VirusNotifier]

      val handler = new FileCheckingResultHandler(fileManager, virusNotifier, checksumCalculator)

      Given("there is a file of a type that is not allowed")
      val file             = S3ObjectLocation("bucket", "file")
      val mimeType         = MimeType("application/pdf")
      val expectedChecksum = checksumCalculator.calculateChecksum(file)

      val inboundObjectMetadata  = InboundObjectMetadata(Map("someKey" -> "someValue"), Instant.now())
      val outboundObjectMetadata = OutboundObjectMetadata.invalid(inboundObjectMetadata)

      val lastModified = LocalDateTime.of(2018, 1, 27, 0, 0).toInstant(ZoneOffset.UTC)

      when(virusNotifier.notifyFileInfected(any(), any())).thenReturn(Future.successful(()))
      when(fileManager.writeToQuarantineBucket(ArgumentMatchers.eq(file), any(), any()))
        .thenReturn(Future.successful(()))
      when(fileManager.delete(file)).thenReturn(Future.successful(()))

      When("checking incorrectly typed file")
      Await
        .result(
          handler
            .handleCheckingResult(IncorrectFileType(file, mimeType, Some("valid-test-service")), inboundObjectMetadata),
          10.seconds)

      Then("no virus notification is created")
      verifyNoMoreInteractions(virusNotifier)

      And("file metadata and error details are stored in the quarantine bucket")
      val captor: ArgumentCaptor[InputStream] = ArgumentCaptor.forClass(classOf[InputStream])
      verify(fileManager)
        .writeToQuarantineBucket(
          ArgumentMatchers.eq(file),
          captor.capture(),
          ArgumentMatchers.eq(outboundObjectMetadata))
      IOUtils.toString(captor.getValue) shouldBe
        """{"failureReason":"REJECTED","message":"MIME type [application/pdf] is not allowed for service: [valid-test-service]"}"""

      And("incorrectly typed file is deleted")
      verify(fileManager).delete(file)
      verifyNoMoreInteractions(fileManager)
    }

    "Return failure if deleting a file if incorrect type fails" in {
      Given("there is a file of a type that is not allowed")

      val file = S3ObjectLocation("bucket", "file")

      val inboundObjectMetadata    = InboundObjectMetadata(Map("someKey" -> "someValue"), Instant.now())
      val mimeType                 = MimeType("application/pdf")
      val fileManager: FileManager = mock[FileManager]

      val virusNotifier: VirusNotifier = mock[VirusNotifier]

      val handler = new FileCheckingResultHandler(fileManager, virusNotifier, checksumCalculator)

      When("copying the file to outbound bucket succeeds")
      when(fileManager.writeToQuarantineBucket(ArgumentMatchers.eq(file), any(), any()))
        .thenReturn(Future.successful(()))

      And("a file manager that fails to delete correctly")
      when(fileManager.delete(file)).thenReturn(Future.failed(new RuntimeException("Expected failure")))

      When("when processing checking result")
      val result =
        Await.ready(
          handler
            .handleCheckingResult(IncorrectFileType(file, mimeType, Some("valid-test-service")), inboundObjectMetadata),
          10.seconds)

      Then("the process fails")
      result.value.get.isFailure shouldBe true

    }

  }
}
