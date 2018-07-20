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
import java.time.Instant

import config.ServiceConfiguration
import model._
import org.apache.commons.io.IOUtils
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.mockito.{ArgumentCaptor, ArgumentMatchers}
import org.scalatest.GivenWhenThen
import org.scalatest.concurrent.Eventually
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.play.test.UnitSpec
import util.logging.LoggingDetails

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class FileCheckingResultHandlerSpec extends UnitSpec with MockitoSugar with Eventually with GivenWhenThen {

  "FileCheckingResultHandler" should {

    implicit val ld = LoggingDetails.fromMessageContext(MessageContext("TEST"))

    val configuration = new ServiceConfiguration {
      override def quarantineBucket: String = "quarantine-bucket"

      override def retryInterval: FiniteDuration = ???

      override def inboundQueueUrl: String = ???

      override def accessKeyId: String = ???

      override def secretAccessKey: String = ???

      override def sessionToken: Option[String] = ???

      override def outboundBucket: String = "outbound-bucket"

      override def awsRegion: String = ???

      override def useContainerCredentials: Boolean = ???

      override def processingBatchSize: Int = ???

      override def consumingServicesConfiguration: ConsumingServicesConfiguration = ???
    }

    "Move clean file from inbound bucket to outbound bucket" in {

      val fileManager: FileManager     = mock[FileManager]
      val virusNotifier: VirusNotifier = mock[VirusNotifier]
      val handler                      = new FileCheckingResultHandler(fileManager, virusNotifier, configuration)

      Given("there is a clean file")
      val file             = S3ObjectLocation("bucket", "file", None)
      val expectedChecksum = "1234567890"
      val expectedMimeType = MimeType("application/xml")
      val clientIp         = "127.0.0.1"

      val inboundObjectMetadata = InboundObjectMetadata(Map("someKey" -> "someValue"), Instant.now())
      val inboundObjectDetails  = InboundObjectDetails(inboundObjectMetadata, clientIp, file)
      val outboundObjectMetadata =
        ValidOutboundObjectMetadata(inboundObjectDetails, expectedChecksum, expectedMimeType)

      when(fileManager.copyObject(ArgumentMatchers.eq(file), any(), any())(any())).thenReturn(Future.successful(()))
      when(fileManager.delete(file)).thenReturn(Future.successful(()))

      When("when processing scanning result")
      Await.result(
        handler
          .handleCheckingResult(inboundObjectDetails, Right(FileValidationSuccess(expectedChecksum, expectedMimeType))),
        10 seconds
      )

      Then("file should be copied from inbound bucket to outbound bucket")
      val locationCaptor: ArgumentCaptor[S3ObjectLocation] = ArgumentCaptor.forClass(classOf[S3ObjectLocation])
      verify(fileManager)
        .copyObject(ArgumentMatchers.eq(file), locationCaptor.capture(), ArgumentMatchers.eq(outboundObjectMetadata))(
          any())
      locationCaptor.getValue.bucket shouldBe configuration.outboundBucket

      And("file should be removed from inbound bucket")
      verify(fileManager).delete(file)

    }

    "Not delete file from outbound bucket if copying failed" in {

      val fileManager: FileManager     = mock[FileManager]
      val virusNotifier: VirusNotifier = mock[VirusNotifier]

      val handler = new FileCheckingResultHandler(fileManager, virusNotifier, configuration)

      Given("there is a clean file")
      val file                  = S3ObjectLocation("bucket", "file", None)
      val expectedChecksum      = "1234567890"
      val expectedMimeType      = MimeType("application/xml")
      val clientIp              = "127.0.0.1"
      val inboundObjectMetadata = InboundObjectMetadata(Map.empty, Instant.now())
      val inboundObjectDetails  = InboundObjectDetails(inboundObjectMetadata, clientIp, file)

      And("copying the file would fail")
      when(fileManager.copyObject(ArgumentMatchers.eq(file), any(), any())(any()))
        .thenReturn(Future.failed(new Exception("Copy failed")))

      When("when processing scanning result")

      val result =
        Await.ready(
          handler.handleCheckingResult(
            inboundObjectDetails,
            Right(FileValidationSuccess(expectedChecksum, expectedMimeType))),
          10 seconds
        )

      Then("original file shoudln't be deleted from inbound bucket")
      verify(fileManager)
        .copyObject(
          ArgumentMatchers.eq(file),
          any(),
          ArgumentMatchers.eq(ValidOutboundObjectMetadata(inboundObjectDetails, expectedChecksum, expectedMimeType)))(
          any())
      verifyNoMoreInteractions(fileManager)

      And("the whole process fails")
      result.value.get.isFailure shouldBe true

    }

    "Return failure if deleting a clean file fails" in {

      val fileManager: FileManager     = mock[FileManager]
      val virusNotifier: VirusNotifier = mock[VirusNotifier]

      val handler = new FileCheckingResultHandler(fileManager, virusNotifier, configuration)

      Given("there is a clean file")
      val file             = S3ObjectLocation("bucket", "file", None)
      val expectedChecksum = "1234567890"
      val expectedMimeType = MimeType("application/xml")
      val clientIp         = "127.0.0.1"

      val inboundObjectMetadata = InboundObjectMetadata(Map("someKey" -> "someValue"), Instant.now())
      val inboundObjectDetails  = InboundObjectDetails(inboundObjectMetadata, clientIp, file)
      val outboundObjectMetadata =
        ValidOutboundObjectMetadata(inboundObjectDetails, expectedChecksum, expectedMimeType)

      when(fileManager.copyObject(ArgumentMatchers.eq(file), any(), ArgumentMatchers.eq(outboundObjectMetadata))(any()))
        .thenReturn(Future.successful(()))
      when(fileManager.delete(file)).thenReturn(Future.failed(new RuntimeException("Expected failure")))

      When("when processing scanning result")
      val result =
        Await.ready(
          handler.handleCheckingResult(
            inboundObjectDetails,
            Right(FileValidationSuccess(expectedChecksum, expectedMimeType))),
          10 seconds
        )

      Then("the process fails")
      result.value.get.isFailure shouldBe true

    }

    "Create virus notification, add error and metadata to quarantine bucket, and delete infected file in case of virus" in {

      val fileManager: FileManager     = mock[FileManager]
      val virusNotifier: VirusNotifier = mock[VirusNotifier]

      val handler = new FileCheckingResultHandler(fileManager, virusNotifier, configuration)

      Given("there is an infected file")
      val file = S3ObjectLocation("bucket", "file", None)

      val clientIp               = "127.0.0.1"
      val inboundObjectMetadata  = InboundObjectMetadata(Map("someKey" -> "someValue"), Instant.now())
      val inboundObjectDetails   = InboundObjectDetails(inboundObjectMetadata, clientIp, file)
      val outboundObjectMetadata = InvalidOutboundObjectMetadata(inboundObjectDetails)

      val details = "There is a virus"

      when(virusNotifier.notifyFileInfected(any(), any())).thenReturn(Future.successful(()))
      when(fileManager.writeObject(ArgumentMatchers.eq(file), any(), any(), any())(any()))
        .thenReturn(Future.successful(()))
      when(fileManager.delete(file)).thenReturn(Future.successful(()))

      When("scanning infected file")
      Await.result(
        handler.handleCheckingResult(
          InboundObjectDetails(inboundObjectMetadata, clientIp, file),
          Left(FileInfected(details))),
        10 seconds)

      Then("notification is created")
      verify(virusNotifier).notifyFileInfected(file, details)

      And("file metadata and error details are stored in the quarantine bucket")
      val locationCaptor: ArgumentCaptor[S3ObjectLocation] = ArgumentCaptor.forClass(classOf[S3ObjectLocation])
      val contentCaptor: ArgumentCaptor[InputStream]       = ArgumentCaptor.forClass(classOf[InputStream])
      verify(fileManager)
        .writeObject(
          ArgumentMatchers.eq(file),
          locationCaptor.capture(),
          contentCaptor.capture(),
          ArgumentMatchers.eq(outboundObjectMetadata))(any())
      IOUtils.toString(contentCaptor.getValue) shouldBe """{"failureReason":"QUARANTINE","message":"There is a virus"}"""

      locationCaptor.getValue.bucket shouldBe configuration.quarantineBucket

      And("infected file is deleted")
      verify(fileManager).delete(file)
      verifyNoMoreInteractions(fileManager)
    }

    "Do not delete infected file if notification creation failed (so that we are able to retry)" in {
      val fileManager: FileManager     = mock[FileManager]
      val virusNotifier: VirusNotifier = mock[VirusNotifier]

      val handler = new FileCheckingResultHandler(fileManager, virusNotifier, configuration)

      Given("there is an infected file")
      val file     = S3ObjectLocation("bucket", "file", None)
      val clientIp = "127.0.0.1"

      val inboundObjectMetadata = InboundObjectMetadata(Map("someKey" -> "someValue"), Instant.now())

      Given("notification service fails")
      when(virusNotifier.notifyFileInfected(any(), any()))
        .thenReturn(Future.failed(new Exception("Notification failed")))
      when(fileManager.delete(file)).thenReturn(Future.successful(()))

      When("scanning infected file")
      val result =
        Await.ready(
          handler
            .handleCheckingResult(
              InboundObjectDetails(inboundObjectMetadata, clientIp, file),
              Left(FileInfected("There is a virus"))),
          10 seconds
        )

      Then("file is not deleted")
      verifyZeroInteractions(fileManager)

      And("the whole process fails")
      result.value.get.isFailure shouldBe true

    }

    "Return failure if deleting a infected file fails" in {

      val fileManager: FileManager     = mock[FileManager]
      val virusNotifier: VirusNotifier = mock[VirusNotifier]

      val handler = new FileCheckingResultHandler(fileManager, virusNotifier, configuration)

      Given("there is an infected file")
      val clientIp = "127.0.0.1"
      val file     = S3ObjectLocation("bucket", "file", None)

      val inboundObjectMetadata = InboundObjectMetadata(Map("someKey" -> "someValue"), Instant.now())

      when(virusNotifier.notifyFileInfected(any(), any())).thenReturn(Future.successful(()))
      when(fileManager.delete(file)).thenReturn(Future.failed(new RuntimeException("Expected failure")))

      When("when processing scanning result")
      val result =
        Await.ready(
          handler
            .handleCheckingResult(
              InboundObjectDetails(inboundObjectMetadata, clientIp, file),
              Left(FileInfected("There is a virus"))),
          10 seconds
        )

      Then("the process fails")
      result.value.get.isFailure shouldBe true

    }

    "Add error and metadata to quarantine bucket, and delete incorrect file in case of invalid file types" in {
      val fileManager: FileManager     = mock[FileManager]
      val virusNotifier: VirusNotifier = mock[VirusNotifier]
      val clientIp                     = "127.0.0.1"
      val handler                      = new FileCheckingResultHandler(fileManager, virusNotifier, configuration)

      Given("there is a file of a type that is not allowed")
      val file     = S3ObjectLocation("bucket", "file", None)
      val mimeType = MimeType("application/pdf")

      val inboundObjectMetadata  = InboundObjectMetadata(Map("someKey" -> "someValue"), Instant.now())
      val inboundObjectDetails   = InboundObjectDetails(inboundObjectMetadata, clientIp, file)
      val outboundObjectMetadata = InvalidOutboundObjectMetadata(inboundObjectDetails)

      when(virusNotifier.notifyFileInfected(any(), any())).thenReturn(Future.successful(()))
      when(fileManager.writeObject(ArgumentMatchers.eq(file), any(), any(), any())(any()))
        .thenReturn(Future.successful(()))
      when(fileManager.delete(file)).thenReturn(Future.successful(()))

      When("checking incorrectly typed file")
      Await
        .result(
          handler
            .handleCheckingResult(
              InboundObjectDetails(inboundObjectMetadata, clientIp, file),
              Left(IncorrectFileType(mimeType, Some("valid-test-service")))),
          10.seconds
        )

      Then("no virus notification is created")
      verifyNoMoreInteractions(virusNotifier)

      And("file metadata and error details are stored in the quarantine bucket")

      val locationCaptor: ArgumentCaptor[S3ObjectLocation] = ArgumentCaptor.forClass(classOf[S3ObjectLocation])
      val captor: ArgumentCaptor[InputStream]              = ArgumentCaptor.forClass(classOf[InputStream])
      verify(fileManager)
        .writeObject(
          ArgumentMatchers.eq(file),
          locationCaptor.capture(),
          captor.capture(),
          ArgumentMatchers.eq(outboundObjectMetadata))(any())
      IOUtils.toString(captor.getValue) shouldBe
        """{"failureReason":"REJECTED","message":"MIME type [application/pdf] is not allowed for service: [valid-test-service]"}"""
      locationCaptor.getValue.bucket shouldBe configuration.quarantineBucket

      And("incorrectly typed file is deleted")
      verify(fileManager).delete(file)
      verifyNoMoreInteractions(fileManager)
    }

    "Return failure if deleting a file if incorrect type fails" in {
      Given("there is a file of a type that is not allowed")

      val file = S3ObjectLocation("bucket", "file", None)

      val inboundObjectMetadata        = InboundObjectMetadata(Map("someKey" -> "someValue"), Instant.now())
      val mimeType                     = MimeType("application/pdf")
      val fileManager: FileManager     = mock[FileManager]
      val clientIp                     = "127.0.0.1"
      val virusNotifier: VirusNotifier = mock[VirusNotifier]

      val handler = new FileCheckingResultHandler(fileManager, virusNotifier, configuration)

      When("copying the file to outbound bucket succeeds")
      when(fileManager.writeObject(ArgumentMatchers.eq(file), any(), any(), any())(any()))
        .thenReturn(Future.successful(()))

      And("a file manager that fails to delete correctly")
      when(fileManager.delete(file)).thenReturn(Future.failed(new RuntimeException("Expected failure")))

      When("when processing checking result")
      val result =
        Await.ready(
          handler
            .handleCheckingResult(
              InboundObjectDetails(inboundObjectMetadata, clientIp, file),
              Left(IncorrectFileType(mimeType, Some("valid-test-service")))),
          10.seconds
        )

      Then("the process fails")
      result.value.get.isFailure shouldBe true

    }

  }
}
