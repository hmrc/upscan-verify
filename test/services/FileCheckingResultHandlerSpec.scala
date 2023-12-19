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

package services

import config.ServiceConfiguration
import model.FileTypeError.{NotAllowedFileExtension, NotAllowedMimeType}
import model._
import org.apache.commons.io.IOUtils
import org.mockito.captor.ArgCaptor
import org.scalatest.GivenWhenThen
import org.scalatest.concurrent.Eventually
import test.{UnitSpec, WithIncrementingClock}
import uk.gov.hmrc.http.logging.LoggingDetails
import util.logging.LoggingDetails

import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class FileCheckingResultHandlerSpec extends UnitSpec with Eventually with GivenWhenThen with WithIncrementingClock {

  val fileSize: Long = 97

  val uploadedAt     = Instant.parse("2018-12-04T17:48:29Z")
  val receivedAt     = Instant.parse("2018-12-04T17:48:30Z")
  val virusScanStart = Instant.parse("2018-12-04T17:48:31Z")
  val virusScanEnd   = Instant.parse("2018-12-04T17:48:32Z")
  val fileTypeStart  = Instant.parse("2018-12-04T17:48:33Z")
  val fileTypeEnd    = Instant.parse("2018-12-04T17:48:34Z")

  // Clock starts after above processing checkpoints...
  override lazy val clockStart = Instant.parse("2018-12-04T17:48:35Z")

  val virusScanTimings = Timings(virusScanStart, virusScanEnd)
  val fileTypeTimings  = Timings(fileTypeStart, fileTypeEnd)


  val allExpectedCheckpoints = Map(
    "x-amz-meta-upscan-verify-received"          -> receivedAt.toString,
    "x-amz-meta-upscan-verify-virusscan-started" -> virusScanStart.toString,
    "x-amz-meta-upscan-verify-virusscan-ended"   -> virusScanEnd.toString,
    "x-amz-meta-upscan-verify-filetype-started"  -> fileTypeStart.toString,
    "x-amz-meta-upscan-verify-filetype-ended"    -> fileTypeEnd.toString,
    "x-amz-meta-upscan-verify-outbound-queued"   -> "2018-12-04T17:48:35Z"
  )

  val virusFoundExpectedCheckpoints = Map(
    "x-amz-meta-upscan-verify-received"          -> receivedAt.toString,
    "x-amz-meta-upscan-verify-virusscan-started" -> virusScanStart.toString,
    "x-amz-meta-upscan-verify-virusscan-ended"   -> virusScanEnd.toString,
    "x-amz-meta-upscan-verify-rejected-queued"   -> "2018-12-04T17:48:35Z"
  )

  val fileTypeRejectedExpectedCheckpoints = Map(
    "x-amz-meta-upscan-verify-received"          -> receivedAt.toString,
    "x-amz-meta-upscan-verify-virusscan-started" -> virusScanStart.toString,
    "x-amz-meta-upscan-verify-virusscan-ended"   -> virusScanEnd.toString,
    "x-amz-meta-upscan-verify-filetype-started"  -> fileTypeStart.toString,
    "x-amz-meta-upscan-verify-filetype-ended"    -> fileTypeEnd.toString,
    "x-amz-meta-upscan-verify-rejected-queued"   -> "2018-12-04T17:48:35Z"
  )

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

      override def allowedMimeTypes(serviceName: String): Option[List[String]] = ???

      override def defaultAllowedMimeTypes: List[String] = ???
    }

    "Move clean file from inbound bucket to outbound bucket" in {

      val fileManager: FileManager             = mock[FileManager]
      val rejectionNotifier: RejectionNotifier = mock[RejectionNotifier]
      val handler                              = new FileCheckingResultHandler(fileManager, rejectionNotifier, configuration, clock)

      Given("there is a clean file")
      val file             = S3ObjectLocation("bucket", "file", None)
      val expectedChecksum = "1234567890"
      val expectedMimeType = MimeType("application/xml")
      val clientIp         = "127.0.0.1"

      val inboundObjectMetadata = InboundObjectMetadata(Map("someKey" -> "someValue"), uploadedAt, fileSize)
      val inboundObjectDetails  = InboundObjectDetails(inboundObjectMetadata, clientIp, file)
      val outboundObjectMetadata =
        ValidOutboundObjectMetadata(inboundObjectDetails, expectedChecksum, expectedMimeType, allExpectedCheckpoints)

      when(fileManager.copyObject(eqTo(file), any[S3ObjectLocation], any[OutboundObjectMetadata])(any[LoggingDetails])).thenReturn(Future.successful(()))
      when(fileManager.delete(file)).thenReturn(Future.successful(()))

      When("when processing scanning result")
      Await.result(
        handler
          .handleCheckingResult(inboundObjectDetails,
            Right(FileValidationSuccess(expectedChecksum, expectedMimeType, virusScanTimings, fileTypeTimings)), receivedAt),
        10.seconds
      )

      Then("file should be copied from inbound bucket to outbound bucket")
      val locationCaptor = ArgCaptor[S3ObjectLocation]
      verify(fileManager).copyObject(eqTo(file), locationCaptor, eqTo(outboundObjectMetadata))(any[LoggingDetails])
      locationCaptor.value.bucket shouldBe configuration.outboundBucket

      And("file should be removed from inbound bucket")
      verify(fileManager).delete(file)

    }

    "Not delete file from outbound bucket if copying failed" in {

      val fileManager: FileManager             = mock[FileManager]
      val rejectionNotifier: RejectionNotifier = mock[RejectionNotifier]

      val handler = new FileCheckingResultHandler(fileManager, rejectionNotifier, configuration, clock)

      Given("there is a clean file")
      val file                  = S3ObjectLocation("bucket", "file", None)
      val expectedChecksum      = "1234567890"
      val expectedMimeType      = MimeType("application/xml")
      val clientIp              = "127.0.0.1"
      val inboundObjectMetadata = InboundObjectMetadata(Map.empty, uploadedAt, fileSize)
      val inboundObjectDetails  = InboundObjectDetails(inboundObjectMetadata, clientIp, file)

      And("copying the file would fail")
      when(fileManager.copyObject(eqTo(file), any[S3ObjectLocation], any[OutboundObjectMetadata])(any[LoggingDetails]))
        .thenReturn(Future.failed(new Exception("Copy failed")))

      When("when processing scanning result")

      val result =
        Await.ready(
          handler.handleCheckingResult(
            inboundObjectDetails,
            Right(FileValidationSuccess(expectedChecksum, expectedMimeType, virusScanTimings, fileTypeTimings)), receivedAt),
          10.seconds
        )

      Then("original file should not be deleted from inbound bucket")
      verify(fileManager).copyObject(
        eqTo(file),
        any[S3ObjectLocation],
        eqTo(ValidOutboundObjectMetadata(inboundObjectDetails, expectedChecksum, expectedMimeType, allExpectedCheckpoints)))(
        any[LoggingDetails])
      verifyNoMoreInteractions(fileManager)

      And("the whole process fails")
      result.value.get.isFailure shouldBe true

    }

    "Return failure if deleting a clean file fails" in {

      val fileManager: FileManager             = mock[FileManager]
      val rejectionNotifier: RejectionNotifier = mock[RejectionNotifier]

      val handler = new FileCheckingResultHandler(fileManager, rejectionNotifier, configuration, clock)

      Given("there is a clean file")
      val file             = S3ObjectLocation("bucket", "file", None)
      val expectedChecksum = "1234567890"
      val expectedMimeType = MimeType("application/xml")
      val clientIp         = "127.0.0.1"

      val inboundObjectMetadata = InboundObjectMetadata(Map("someKey" -> "someValue"), uploadedAt, fileSize)
      val inboundObjectDetails  = InboundObjectDetails(inboundObjectMetadata, clientIp, file)
      val outboundObjectMetadata =
        ValidOutboundObjectMetadata(inboundObjectDetails, expectedChecksum, expectedMimeType, allExpectedCheckpoints)

      when(fileManager.copyObject(eqTo(file), any[S3ObjectLocation], eqTo(outboundObjectMetadata))(any[LoggingDetails]))
        .thenReturn(Future.successful(()))
      when(fileManager.delete(file)).thenReturn(Future.failed(new RuntimeException("Expected failure")))

      When("when processing scanning result")
      val result =
        Await.ready(
          handler.handleCheckingResult(
            inboundObjectDetails,
            Right(FileValidationSuccess(expectedChecksum, expectedMimeType, virusScanTimings, fileTypeTimings)), receivedAt),
          10.seconds
        )

      Then("the process fails")
      result.value.get.isFailure shouldBe true

    }

    "Create virus notification, add error and metadata to quarantine bucket, and delete infected file in case of virus" in {

      val fileManager: FileManager             = mock[FileManager]
      val rejectionNotifier: RejectionNotifier = mock[RejectionNotifier]

      val handler = new FileCheckingResultHandler(fileManager, rejectionNotifier, configuration, clock)

      Given("there is an infected file")
      val file = S3ObjectLocation("bucket", "file", None)

      val clientIp               = "127.0.0.1"
      val inboundObjectMetadata  = InboundObjectMetadata(Map("someKey" -> "someValue"), uploadedAt, fileSize)
      val inboundObjectDetails   = InboundObjectDetails(inboundObjectMetadata, clientIp, file)
      val outboundObjectMetadata = InvalidOutboundObjectMetadata(inboundObjectDetails, virusFoundExpectedCheckpoints)

      val details:  String = "There is a virus"
      val checksum: String = "CHECKSUM"

      when(rejectionNotifier.notifyFileInfected(any[S3ObjectLocation], any[String], any[Long], any[Instant], any[String], any[Option[String]])(any[LoggingDetails]))
        .thenReturn(Future.successful(()))
      when(fileManager.writeObject(eqTo(file), any[S3ObjectLocation], any[InputStream], any[OutboundObjectMetadata])(any[LoggingDetails]))
        .thenReturn(Future.successful(()))
      when(fileManager.delete(file)).thenReturn(Future.successful(()))

      When("scanning infected file")
      Await.result(
        handler.handleCheckingResult(
          InboundObjectDetails(inboundObjectMetadata, clientIp, file),
          Left(FileRejected(Left(FileInfected(details, checksum, virusScanTimings)))), receivedAt),
        10.seconds)

      Then("notification is created")
      verify(rejectionNotifier).notifyFileInfected(file, checksum, fileSize, uploadedAt, details, None)

      And("file metadata and error details are stored in the quarantine bucket")
      val locationCaptor = ArgCaptor[S3ObjectLocation]
      val contentCaptor = ArgCaptor[InputStream]
      verify(fileManager)
        .writeObject(
          eqTo(file),
          locationCaptor,
          contentCaptor,
          eqTo(outboundObjectMetadata))(any[LoggingDetails])
      IOUtils.toString(contentCaptor.value, UTF_8) shouldBe """{"failureReason":"QUARANTINE","message":"There is a virus"}"""

      locationCaptor.value.bucket shouldBe configuration.quarantineBucket

      And("infected file is deleted")
      verify(fileManager).delete(file)
      verifyNoMoreInteractions(fileManager)
    }

    "Do not delete infected file if notification creation failed (so that we are able to retry)" in {
      val fileManager: FileManager             = mock[FileManager]
      val rejectionNotifier: RejectionNotifier = mock[RejectionNotifier]

      val handler = new FileCheckingResultHandler(fileManager, rejectionNotifier, configuration, clock)

      Given("there is an infected file")
      val file     = S3ObjectLocation("bucket", "file", None)
      val clientIp = "127.0.0.1"

      val inboundObjectMetadata = InboundObjectMetadata(Map("someKey" -> "someValue"), uploadedAt, fileSize)
      val checksum: String = "CHECKSUM"

      Given("notification service fails")
      when(rejectionNotifier.notifyFileInfected(any[S3ObjectLocation], any[String], any[Long], any[Instant], any[String], any[Option[String]])(any[LoggingDetails]))
        .thenReturn(Future.failed(new Exception("Notification failed")))

      When("scanning infected file")
      val result =
        Await.ready(
          handler
            .handleCheckingResult(
              InboundObjectDetails(inboundObjectMetadata, clientIp, file),
              Left(FileRejected(Left(FileInfected("There is a virus", checksum, virusScanTimings)))), receivedAt),
          10.seconds
        )

      Then("file is not deleted")
      verifyZeroInteractions(fileManager)

      And("the whole process fails")
      result.value.get.isFailure shouldBe true
    }

    "Return failure if deleting a infected file fails" in {

      val fileManager: FileManager             = mock[FileManager]
      val rejectionNotifier: RejectionNotifier = mock[RejectionNotifier]

      val handler = new FileCheckingResultHandler(fileManager, rejectionNotifier, configuration, clock)

      Given("there is an infected file")
      val clientIp = "127.0.0.1"
      val file     = S3ObjectLocation("bucket", "file", None)

      val inboundObjectMetadata = InboundObjectMetadata(Map("someKey" -> "someValue"), uploadedAt, fileSize)
      val checksum: String = "CHECKSUM"

      when(rejectionNotifier.notifyFileInfected(any[S3ObjectLocation], any[String], any[Long], any[Instant], any[String], any[Option[String]])(any[LoggingDetails]))
        .thenReturn(Future.successful(()))
      when(fileManager.writeObject(eqTo(file), any[S3ObjectLocation], any[InputStream], any[OutboundObjectMetadata])(any[LoggingDetails])).
        thenReturn(Future.successful(()))
      when(fileManager.delete(file)).thenReturn(Future.failed(new RuntimeException("Expected failure")))

      When("when processing scanning result")
      val result =
        Await.ready(
          handler
            .handleCheckingResult(
              InboundObjectDetails(inboundObjectMetadata, clientIp, file),
              Left(FileRejected(Left(FileInfected("There is a virus", checksum, virusScanTimings)))), receivedAt),
          10.seconds
        )

      Then("the process fails")
      result.value.get.isFailure shouldBe true
    }

    "Add error and metadata to quarantine bucket, and delete incorrect file in case of invalid file mime type" in {
      val fileManager: FileManager             = mock[FileManager]
      val rejectionNotifier: RejectionNotifier = mock[RejectionNotifier]
      val clientIp                             = "127.0.0.1"
      val handler                              = new FileCheckingResultHandler(fileManager, rejectionNotifier, configuration, clock)

      Given("there is a file of a type that is not allowed")
      val file     = S3ObjectLocation("bucket", "file", None)
      val mimeType = MimeType("application/pdf")

      val inboundObjectMetadata  = InboundObjectMetadata(Map("someKey" -> "someValue"), uploadedAt, fileSize)
      val inboundObjectDetails   = InboundObjectDetails(inboundObjectMetadata, clientIp, file)
      val outboundObjectMetadata = InvalidOutboundObjectMetadata(inboundObjectDetails, fileTypeRejectedExpectedCheckpoints)

      when(rejectionNotifier.notifyInvalidFileType(any[S3ObjectLocation], any[String], any[Long], any[Instant], any[String], any[Option[String]])(any[LoggingDetails]))
        .thenReturn(Future.successful(()))
      when(fileManager.writeObject(eqTo(file), any[S3ObjectLocation], any[InputStream], any[OutboundObjectMetadata])(any[LoggingDetails]))
        .thenReturn(Future.successful(()))
      when(fileManager.delete(file)).thenReturn(Future.successful(()))

      When("checking incorrectly typed file")
      val serviceName: Option[String] = Some("valid-test-service")
      val checksum: String = "CHECKSUM"
      Await
        .result(
          handler
            .handleCheckingResult(
              InboundObjectDetails(inboundObjectMetadata, clientIp, file),
              Left(FileRejected(Right(NoVirusFound(checksum, virusScanTimings)),
                                Some(NotAllowedMimeType(mimeType, serviceName, fileTypeTimings)))), receivedAt
            ),
          10.seconds
        )

      Then("notification is created")
      verify(rejectionNotifier).notifyInvalidFileType(eqTo(file), eqTo(checksum), eqTo(fileSize), eqTo(uploadedAt), any[String], eqTo(serviceName))(eqTo(ld))

      And("file metadata and error details are stored in the quarantine bucket")

      val locationCaptor = ArgCaptor[S3ObjectLocation]
      val streamCaptor = ArgCaptor[InputStream]
      verify(fileManager)
        .writeObject(
          eqTo(file),
          locationCaptor,
          streamCaptor,
          eqTo(outboundObjectMetadata))(any[LoggingDetails])
      IOUtils.toString(streamCaptor.value, UTF_8) shouldBe
        """{"failureReason":"REJECTED","message":"MIME type [application/pdf] is not allowed for service: [valid-test-service]"}"""
      locationCaptor.value.bucket shouldBe configuration.quarantineBucket

      And("incorrectly typed file is deleted")
      verify(fileManager).delete(file)
      verifyNoMoreInteractions(fileManager)
    }

    "Add error and metadata to quarantine bucket, and delete incorrect file in case of invalid file extension" in {
      val fileManager: FileManager             = mock[FileManager]
      val rejectionNotifier: RejectionNotifier = mock[RejectionNotifier]
      val clientIp                             = "127.0.0.1"
      val handler                              = new FileCheckingResultHandler(fileManager, rejectionNotifier, configuration, clock)

      Given("there is a file with extension which is not allowed for the file's mime-type")
      val file     = S3ObjectLocation("bucket", "file", None)
      val mimeType = MimeType("text/plain")

      val inboundObjectMetadata  = InboundObjectMetadata(Map("someKey" -> "someValue", "original-filename" -> "foo.foo"), uploadedAt, fileSize)
      val inboundObjectDetails   = InboundObjectDetails(inboundObjectMetadata, clientIp, file)
      val outboundObjectMetadata = InvalidOutboundObjectMetadata(inboundObjectDetails, fileTypeRejectedExpectedCheckpoints)

      when(rejectionNotifier.notifyInvalidFileType(any[S3ObjectLocation], any[String], any[Long], any[Instant], any[String], any[Option[String]])(any[LoggingDetails]))
        .thenReturn(Future.successful(()))
      when(fileManager.writeObject(eqTo(file), any[S3ObjectLocation], any[InputStream], any[OutboundObjectMetadata])(any[LoggingDetails]))
        .thenReturn(Future.successful(()))
      when(fileManager.delete(file)).thenReturn(Future.successful(()))

      When("checking incorrectly typed file")
      val serviceName: Option[String] = Some("valid-test-service")
      val checksum: String = "CHECKSUM"
      Await
        .result(
          handler
            .handleCheckingResult(
              InboundObjectDetails(inboundObjectMetadata, clientIp, file),
              Left(FileRejected(Right(NoVirusFound(checksum, virusScanTimings)),
                                Some(NotAllowedFileExtension(mimeType, "foo", serviceName, fileTypeTimings)))), receivedAt
            ),
          10.seconds
        )

      Then("notification is created")
      verify(rejectionNotifier).notifyInvalidFileType(eqTo(file), eqTo(checksum), eqTo(fileSize), eqTo(uploadedAt), any[String], eqTo(serviceName))(eqTo(ld))

      And("file metadata and error details are stored in the quarantine bucket")

      val locationCaptor = ArgCaptor[S3ObjectLocation]
      val streamCaptor = ArgCaptor[InputStream]
      verify(fileManager)
        .writeObject(
          eqTo(file),
          locationCaptor,
          streamCaptor,
          eqTo(outboundObjectMetadata))(any[LoggingDetails])
      IOUtils.toString(streamCaptor.value, UTF_8) shouldBe
        """{"failureReason":"REJECTED","message":"File extension [foo] is not allowed for mime-type [text/plain], service: [valid-test-service]"}"""
      locationCaptor.value.bucket shouldBe configuration.quarantineBucket

      And("incorrectly typed file is deleted")
      verify(fileManager).delete(file)
      verifyNoMoreInteractions(fileManager)
    }

    "Return failure if deleting a file if incorrect type fails" in {
      Given("there is a file of a type that is not allowed")

      val file = S3ObjectLocation("bucket", "file", None)

      val inboundObjectMetadata                = InboundObjectMetadata(Map("someKey" -> "someValue"), uploadedAt, fileSize)
      val mimeType                             = MimeType("application/pdf")
      val fileManager: FileManager             = mock[FileManager]
      val clientIp                             = "127.0.0.1"
      val rejectionNotifier: RejectionNotifier = mock[RejectionNotifier]

      val handler = new FileCheckingResultHandler(fileManager, rejectionNotifier, configuration, clock)

      When("copying the file to outbound bucket succeeds")
      when(fileManager.writeObject(eqTo(file), any[S3ObjectLocation], any[InputStream], any[OutboundObjectMetadata])(any[LoggingDetails]))
        .thenReturn(Future.successful(()))

      And("a file manager that fails to delete correctly")
      when(fileManager.delete(file)).thenReturn(Future.failed(new RuntimeException("Expected failure")))

      When("when processing checking result")
      when(rejectionNotifier.notifyInvalidFileType(any[S3ObjectLocation], any[String], any[Long], any[Instant], any[String], any[Option[String]])(any[LoggingDetails]))
        .thenReturn(Future.successful(()))
      val result =
        Await.ready(
          handler
            .handleCheckingResult(
              InboundObjectDetails(inboundObjectMetadata, clientIp, file),
              Left(FileRejected(Right(NoVirusFound("1234567890", virusScanTimings)),
                   Some(NotAllowedMimeType(mimeType, Some("valid-test-service"), fileTypeTimings)))), receivedAt
            ),
          10.seconds
        )

      Then("the process fails")
      result.value.get.isFailure shouldBe true

    }

  }
}
