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

import org.apache.commons.io.IOUtils
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.{any, eq => eqTo}
import org.mockito.Mockito.{when, verify, verifyNoMoreInteractions, verifyNoInteractions}
import org.scalatest.GivenWhenThen
import org.scalatest.concurrent.{Eventually, IntegrationPatience, ScalaFutures}
import uk.gov.hmrc.upscanverify.config.ServiceConfiguration
import uk.gov.hmrc.upscanverify.model._
import uk.gov.hmrc.upscanverify.test.{UnitSpec, WithIncrementingClock}

import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.{Duration, FiniteDuration}

class FileCheckingResultHandlerSpec
  extends UnitSpec
     with Eventually
     with GivenWhenThen
     with WithIncrementingClock
     with ScalaFutures
     with IntegrationPatience:

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

  "FileCheckingResultHandler" should:
    val configuration = new ServiceConfiguration:
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

      override def inboundQueueVisibilityTimeout: Duration = ???

    "Move clean file from inbound bucket to outbound bucket" in:
      val fileManager: FileManager             = mock[FileManager]
      val rejectionNotifier: RejectionNotifier = mock[RejectionNotifier]
      val handler                              = FileCheckingResultHandler(fileManager, rejectionNotifier, configuration, clock)

      Given("there is a clean file")
      val file             = S3ObjectLocation("bucket", "file", None)
      val expectedChecksum = "1234567890"
      val expectedMimeType = MimeType("application/xml")
      val clientIp         = "127.0.0.1"

      val inboundObjectMetadata = InboundObjectMetadata(Map("someKey" -> "someValue"), uploadedAt, fileSize)
      val inboundObjectDetails  = InboundObjectDetails(inboundObjectMetadata, clientIp, file)
      val outboundObjectMetadata =
        OutboundObjectMetadata.valid(inboundObjectDetails, expectedChecksum, expectedMimeType, allExpectedCheckpoints)

      when(fileManager.copyObject(eqTo(file), any[S3ObjectLocation], any[OutboundObjectMetadata]))
        .thenReturn(Future.unit)
      when(fileManager.delete(file))
        .thenReturn(Future.unit)

      When("when processing scanning result")
      handler
        .handleCheckingResult(
          inboundObjectDetails,
          Right(VerifyResult.FileValidationSuccess(
            VirusScanResult.NoVirusFound(expectedChecksum, virusScanTimings),
            FileAllowed(expectedMimeType, fileTypeTimings)
          )),
          receivedAt
        )
        .futureValue

      Then("file should be copied from inbound bucket to outbound bucket")
      val locationCaptor = ArgumentCaptor.forClass(classOf[S3ObjectLocation])
      verify(fileManager).copyObject(eqTo(file), locationCaptor.capture(), eqTo(outboundObjectMetadata))
      locationCaptor.getValue.bucket shouldBe configuration.outboundBucket

      And("file should be removed from inbound bucket")
      verify(fileManager).delete(file)

    "Not delete file from outbound bucket if copying failed" in:
      val fileManager: FileManager             = mock[FileManager]
      val rejectionNotifier: RejectionNotifier = mock[RejectionNotifier]

      val handler = FileCheckingResultHandler(fileManager, rejectionNotifier, configuration, clock)

      Given("there is a clean file")
      val file                  = S3ObjectLocation("bucket", "file", None)
      val expectedChecksum      = "1234567890"
      val expectedMimeType      = MimeType("application/xml")
      val clientIp              = "127.0.0.1"
      val inboundObjectMetadata = InboundObjectMetadata(Map.empty, uploadedAt, fileSize)
      val inboundObjectDetails  = InboundObjectDetails(inboundObjectMetadata, clientIp, file)

      And("copying the file would fail")
      when(fileManager.copyObject(eqTo(file), any[S3ObjectLocation], any[OutboundObjectMetadata]))
        .thenReturn(Future.failed(Exception("Copy failed")))

      When("when processing scanning result")
      val result =
        handler.handleCheckingResult(
          inboundObjectDetails,
          Right(VerifyResult.FileValidationSuccess(
            VirusScanResult.NoVirusFound(expectedChecksum, virusScanTimings),
            FileAllowed(expectedMimeType, fileTypeTimings)
          )),
          receivedAt
        )

      Then("the whole process fails")
      result.failed.futureValue shouldBe a[Throwable]

      And("original file should not be deleted from inbound bucket")
      verify(fileManager).copyObject(
        eqTo(file),
        any[S3ObjectLocation],
        eqTo(OutboundObjectMetadata.valid(inboundObjectDetails, expectedChecksum, expectedMimeType, allExpectedCheckpoints))
      )
      verifyNoMoreInteractions(fileManager)


    "Return failure if deleting a clean file fails" in:
      val fileManager: FileManager             = mock[FileManager]
      val rejectionNotifier: RejectionNotifier = mock[RejectionNotifier]

      val handler = FileCheckingResultHandler(fileManager, rejectionNotifier, configuration, clock)

      Given("there is a clean file")
      val file             = S3ObjectLocation("bucket", "file", None)
      val expectedChecksum = "1234567890"
      val expectedMimeType = MimeType("application/xml")
      val clientIp         = "127.0.0.1"

      val inboundObjectMetadata = InboundObjectMetadata(Map("someKey" -> "someValue"), uploadedAt, fileSize)
      val inboundObjectDetails  = InboundObjectDetails(inboundObjectMetadata, clientIp, file)
      val outboundObjectMetadata =
        OutboundObjectMetadata.valid(inboundObjectDetails, expectedChecksum, expectedMimeType, allExpectedCheckpoints)

      when(fileManager.copyObject(eqTo(file), any[S3ObjectLocation], eqTo(outboundObjectMetadata)))
        .thenReturn(Future.unit)
      when(fileManager.delete(file))
        .thenReturn(Future.failed(RuntimeException("Expected failure")))

      When("when processing scanning result")
      val result =
        handler.handleCheckingResult(
          inboundObjectDetails,
          Right(VerifyResult.FileValidationSuccess(
            VirusScanResult.NoVirusFound(expectedChecksum, virusScanTimings),
            FileAllowed(expectedMimeType, fileTypeTimings)
          )),
          receivedAt
        )

      Then("the process fails")
      result.failed.futureValue shouldBe a[Throwable]

    "Create virus notification, add error and metadata to quarantine bucket, and delete infected file in case of virus" in:
      val fileManager: FileManager             = mock[FileManager]
      val rejectionNotifier: RejectionNotifier = mock[RejectionNotifier]

      val handler = FileCheckingResultHandler(fileManager, rejectionNotifier, configuration, clock)

      Given("there is an infected file")
      val file = S3ObjectLocation("bucket", "file", None)

      val clientIp               = "127.0.0.1"
      val inboundObjectMetadata  = InboundObjectMetadata(Map("someKey" -> "someValue"), uploadedAt, fileSize)
      val inboundObjectDetails   = InboundObjectDetails(inboundObjectMetadata, clientIp, file)
      val outboundObjectMetadata = OutboundObjectMetadata.invalid(inboundObjectDetails, virusFoundExpectedCheckpoints)

      val errorMessage = ErrorMessage(FileCheckingError.Quarantine, "There is a virus")
      val checksum: String = "CHECKSUM"

      when(rejectionNotifier.notifyRejection(any[S3ObjectLocation], any[String], any[Long], any[Instant], any[ErrorMessage], any[Option[String]]))
        .thenReturn(Future.unit)
      when(fileManager.writeObject(eqTo(file), any[S3ObjectLocation], any[InputStream], any[Long], any[OutboundObjectMetadata]))
        .thenReturn(Future.unit)
      when(fileManager.delete(file))
        .thenReturn(Future.unit)

      When("scanning infected file")
        handler
          .handleCheckingResult(
            InboundObjectDetails(inboundObjectMetadata, clientIp, file),
            Left(VerifyResult.FileRejected.VirusScanFailure(
              VirusScanResult.FileInfected(errorMessage.message, checksum, virusScanTimings)
            )),
            receivedAt
          )
          .futureValue

      Then("notification is created")
      verify(rejectionNotifier).notifyRejection(file, checksum, fileSize, uploadedAt, errorMessage, None)

      And("file metadata and error details are stored in the quarantine bucket")
      val locationCaptor = ArgumentCaptor.forClass(classOf[S3ObjectLocation])
      val contentCaptor  = ArgumentCaptor.forClass(classOf[InputStream])
      verify(fileManager)
        .writeObject(
          eqTo(file),
          locationCaptor.capture(),
          contentCaptor.capture(),
          any[Long], // TODO eqTo(fileSize)
          eqTo(outboundObjectMetadata)
        )
      IOUtils.toString(contentCaptor.getValue, UTF_8) shouldBe """{"failureReason":"QUARANTINE","message":"There is a virus"}"""

      locationCaptor.getValue.bucket shouldBe configuration.quarantineBucket

      And("infected file is deleted")
      verify(fileManager).delete(file)
      verifyNoMoreInteractions(fileManager)

    "Do not delete infected file if notification creation failed (so that we are able to retry)" in:
      val fileManager: FileManager             = mock[FileManager]
      val rejectionNotifier: RejectionNotifier = mock[RejectionNotifier]

      val handler = FileCheckingResultHandler(fileManager, rejectionNotifier, configuration, clock)

      Given("there is an infected file")
      val file     = S3ObjectLocation("bucket", "file", None)
      val clientIp = "127.0.0.1"

      val inboundObjectMetadata = InboundObjectMetadata(Map("someKey" -> "someValue"), uploadedAt, fileSize)
      val checksum: String = "CHECKSUM"

      Given("notification service fails")
      when(rejectionNotifier.notifyRejection(any[S3ObjectLocation], any[String], any[Long], any[Instant], any[ErrorMessage], any[Option[String]]))
        .thenReturn(Future.failed(Exception("Notification failed")))

      When("scanning infected file")
      val result =
        handler
          .handleCheckingResult(
            InboundObjectDetails(inboundObjectMetadata, clientIp, file),
            Left(VerifyResult.FileRejected.VirusScanFailure(
              VirusScanResult.FileInfected("There is a virus", checksum, virusScanTimings)
            )),
            receivedAt
          )

      Then("the whole process fails")
      result.failed.futureValue shouldBe a[Throwable]

      And("file is not deleted")
      verifyNoInteractions(fileManager)

    "Return failure if deleting a infected file fails" in:
      val fileManager: FileManager             = mock[FileManager]
      val rejectionNotifier: RejectionNotifier = mock[RejectionNotifier]

      val handler = FileCheckingResultHandler(fileManager, rejectionNotifier, configuration, clock)

      Given("there is an infected file")
      val clientIp = "127.0.0.1"
      val file     = S3ObjectLocation("bucket", "file", None)

      val inboundObjectMetadata = InboundObjectMetadata(Map("someKey" -> "someValue"), uploadedAt, fileSize)
      val checksum: String = "CHECKSUM"

      when(rejectionNotifier.notifyRejection(any[S3ObjectLocation], any[String], any[Long], any[Instant], any[ErrorMessage], any[Option[String]]))
        .thenReturn(Future.unit)
      when(fileManager.writeObject(eqTo(file), any[S3ObjectLocation], any[InputStream], any[Long], any[OutboundObjectMetadata])).
        thenReturn(Future.unit)
      when(fileManager.delete(file))
        .thenReturn(Future.failed(RuntimeException("Expected failure")))

      When("when processing scanning result")
      val result =
        handler
          .handleCheckingResult(
            InboundObjectDetails(inboundObjectMetadata, clientIp, file),
            Left(VerifyResult.FileRejected.VirusScanFailure(
              VirusScanResult.FileInfected("There is a virus", checksum, virusScanTimings)
            )),
            receivedAt
          )

      Then("the process fails")
      result.failed.futureValue shouldBe a[Throwable]

    "Add error and metadata to quarantine bucket, and delete incorrect file in case of invalid file mime type" in:
      val fileManager: FileManager             = mock[FileManager]
      val rejectionNotifier: RejectionNotifier = mock[RejectionNotifier]
      val clientIp                             = "127.0.0.1"
      val handler                              = FileCheckingResultHandler(fileManager, rejectionNotifier, configuration, clock)

      Given("there is a file of a type that is not allowed")
      val file     = S3ObjectLocation("bucket", "file", None)
      val mimeType = MimeType("application/pdf")

      val inboundObjectMetadata  = InboundObjectMetadata(Map("someKey" -> "someValue"), uploadedAt, fileSize)
      val inboundObjectDetails   = InboundObjectDetails(inboundObjectMetadata, clientIp, file)
      val outboundObjectMetadata = OutboundObjectMetadata.invalid(inboundObjectDetails, fileTypeRejectedExpectedCheckpoints)

      when(rejectionNotifier.notifyRejection(any[S3ObjectLocation], any[String], any[Long], any[Instant], any[ErrorMessage], any[Option[String]]))
        .thenReturn(Future.unit)
      when(fileManager.writeObject(eqTo(file), any[S3ObjectLocation], any[InputStream], any[Long], any[OutboundObjectMetadata]))
        .thenReturn(Future.unit)
      when(fileManager.delete(file))
        .thenReturn(Future.unit)

      When("checking incorrectly typed file")
      val serviceName: Option[String] = Some("valid-test-service")
      val checksum: String = "CHECKSUM"
      handler
        .handleCheckingResult(
          InboundObjectDetails(inboundObjectMetadata, clientIp, file),
          Left(VerifyResult.FileRejected.FileTypeFailure(
            VirusScanResult.NoVirusFound(checksum, virusScanTimings),
            FileTypeError.NotAllowedMimeType(mimeType, serviceName, fileTypeTimings)
          )),
          receivedAt
        )
        .futureValue

      Then("notification is created")
      verify(rejectionNotifier).notifyRejection(eqTo(file), eqTo(checksum), eqTo(fileSize), eqTo(uploadedAt), any[ErrorMessage], eqTo(serviceName))

      And("file metadata and error details are stored in the quarantine bucket")

      val locationCaptor = ArgumentCaptor.forClass(classOf[S3ObjectLocation])
      val streamCaptor = ArgumentCaptor.forClass(classOf[InputStream])
      verify(fileManager)
        .writeObject(
          eqTo(file),
          locationCaptor.capture(),
          streamCaptor.capture(),
          any[Long], // TODO eqTo(fileSize)
          eqTo(outboundObjectMetadata)
        )
      IOUtils.toString(streamCaptor.getValue, UTF_8) shouldBe
        """{"failureReason":"REJECTED","message":"MIME type [application/pdf] is not allowed for service: [valid-test-service]"}"""
      locationCaptor.getValue.bucket shouldBe configuration.quarantineBucket

      And("incorrectly typed file is deleted")
      verify(fileManager).delete(file)
      verifyNoMoreInteractions(fileManager)

    "Add error and metadata to quarantine bucket, and delete incorrect file in case of invalid file extension" in:
      val fileManager: FileManager             = mock[FileManager]
      val rejectionNotifier: RejectionNotifier = mock[RejectionNotifier]
      val clientIp                             = "127.0.0.1"
      val handler                              = FileCheckingResultHandler(fileManager, rejectionNotifier, configuration, clock)

      Given("there is a file with extension which is not allowed for the file's mime-type")
      val file     = S3ObjectLocation("bucket", "file", None)
      val mimeType = MimeType("text/plain")

      val inboundObjectMetadata  = InboundObjectMetadata(Map("someKey" -> "someValue", "original-filename" -> "foo.foo"), uploadedAt, fileSize)
      val inboundObjectDetails   = InboundObjectDetails(inboundObjectMetadata, clientIp, file)
      val outboundObjectMetadata = OutboundObjectMetadata.invalid(inboundObjectDetails, fileTypeRejectedExpectedCheckpoints)

      when(rejectionNotifier.notifyRejection(any[S3ObjectLocation], any[String], any[Long], any[Instant], any[ErrorMessage], any[Option[String]]))
        .thenReturn(Future.unit)
      when(fileManager.writeObject(eqTo(file), any[S3ObjectLocation], any[InputStream], any[Long], any[OutboundObjectMetadata]))
        .thenReturn(Future.unit)
      when(fileManager.delete(file))
        .thenReturn(Future.unit)

      When("checking incorrectly typed file")
      val serviceName: Option[String] = Some("valid-test-service")
      val checksum: String = "CHECKSUM"
      handler
        .handleCheckingResult(
          InboundObjectDetails(inboundObjectMetadata, clientIp, file),
          Left(VerifyResult.FileRejected.FileTypeFailure(
            VirusScanResult.NoVirusFound(checksum, virusScanTimings),
            FileTypeError.NotAllowedFileExtension(mimeType, "foo", serviceName, fileTypeTimings)
          )),
          receivedAt
        )
        .futureValue

      Then("notification is created")
      verify(rejectionNotifier).notifyRejection(eqTo(file), eqTo(checksum), eqTo(fileSize), eqTo(uploadedAt), any[ErrorMessage], eqTo(serviceName))

      And("file metadata and error details are stored in the quarantine bucket")

      val locationCaptor = ArgumentCaptor.forClass(classOf[S3ObjectLocation])
      val streamCaptor = ArgumentCaptor.forClass(classOf[InputStream])
      verify(fileManager)
        .writeObject(
          eqTo(file),
          locationCaptor.capture(),
          streamCaptor.capture(),
          any[Long], // TODO eqTo(fileSize)
          eqTo(outboundObjectMetadata)
        )
      IOUtils.toString(streamCaptor.getValue, UTF_8) shouldBe
        """{"failureReason":"REJECTED","message":"File extension [foo] is not allowed for mime-type [text/plain], service: [valid-test-service]"}"""
      locationCaptor.getValue.bucket shouldBe configuration.quarantineBucket

      And("incorrectly typed file is deleted")
      verify(fileManager).delete(file)
      verifyNoMoreInteractions(fileManager)

    "Return failure if deleting a file if incorrect type fails" in:
      Given("there is a file of a type that is not allowed")
      val file = S3ObjectLocation("bucket", "file", None)

      val inboundObjectMetadata                = InboundObjectMetadata(Map("someKey" -> "someValue"), uploadedAt, fileSize)
      val mimeType                             = MimeType("application/pdf")
      val fileManager: FileManager             = mock[FileManager]
      val clientIp                             = "127.0.0.1"
      val rejectionNotifier: RejectionNotifier = mock[RejectionNotifier]

      val handler = FileCheckingResultHandler(fileManager, rejectionNotifier, configuration, clock)

      When("copying the file to outbound bucket succeeds")
      when(fileManager.writeObject(eqTo(file), any[S3ObjectLocation], any[InputStream], any[Long], any[OutboundObjectMetadata]))
        .thenReturn(Future.unit)

      And("a file manager that fails to delete correctly")
      when(fileManager.delete(file))
        .thenReturn(Future.failed(RuntimeException("Expected failure")))

      When("when processing checking result")
      when(rejectionNotifier.notifyRejection(any[S3ObjectLocation], any[String], any[Long], any[Instant], any[ErrorMessage], any[Option[String]]))
        .thenReturn(Future.unit)
      val result =
        handler
          .handleCheckingResult(
            InboundObjectDetails(inboundObjectMetadata, clientIp, file),
            Left(VerifyResult.FileRejected.FileTypeFailure(
              VirusScanResult.NoVirusFound("1234567890", virusScanTimings),
              FileTypeError.NotAllowedMimeType(mimeType, Some("valid-test-service"), fileTypeTimings)
            )),
            receivedAt
          )

      Then("the process fails")
      result.failed.futureValue shouldBe a[Throwable]
