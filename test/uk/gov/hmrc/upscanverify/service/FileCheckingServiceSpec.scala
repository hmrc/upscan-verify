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

import org.mockito.Mockito.{verifyNoInteractions, when}
import org.scalatest.GivenWhenThen
import org.scalatest.concurrent.ScalaFutures
import uk.gov.hmrc.http.logging.LoggingDetails
import uk.gov.hmrc.upscanverify.model._
import uk.gov.hmrc.upscanverify.test.{UnitSpec, WithIncrementingClock}
import uk.gov.hmrc.upscanverify.util.logging.LoggingDetails

import java.io.{ByteArrayInputStream, InputStream}
import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class FileCheckingServiceSpec
  extends UnitSpec
     with GivenWhenThen
     with WithIncrementingClock
     with ScalaFutures:

  override lazy val clockStart = Instant.parse("2018-12-04T17:48:30Z")

  "File checking service" should:
    given LoggingDetails = LoggingDetails.fromMessageContext(MessageContext("TEST"))

    val content = ObjectContent(ByteArrayInputStream("TEST".getBytes), "TEST".length)

    val fileManager = new FileManager:
      override def delete(objectLocation: S3ObjectLocation)(using LoggingDetails): Future[Unit] =
        ???

      override def getObjectMetadata(objectLocation: S3ObjectLocation)(using LoggingDetails): Future[InboundObjectMetadata] =
        ???

      override def withObjectContent[T](
        objectLocation: S3ObjectLocation
      )(function: ObjectContent => Future[T]
      )(using LoggingDetails): Future[T] =
        if objectLocation.objectKey.contains("exception") then
          Future.failed(RuntimeException("Expected exception"))
        else
          function(content)

      override def copyObject(
        sourceLocation: S3ObjectLocation,
        targetLocation: S3ObjectLocation,
        metadata      : OutboundObjectMetadata
      )(using LoggingDetails): Future[Unit] =
        ???

      override def writeObject(
        sourceLocation: S3ObjectLocation,
        targetLocation: S3ObjectLocation,
        content       : InputStream,
        metadata      : OutboundObjectMetadata
      )(using LoggingDetails): Future[Unit] =
        ???

    val location = S3ObjectLocation("bucket", "file", None)
    val metadata = InboundObjectMetadata(Map("consuming-service" -> "ScanUploadedFilesFlowSpec"), clock.instant(), 0)

    "succeed when virus and file type scanning succeeded" in:
      val virusScanningService    = mock[ScanningService]
      val fileTypeCheckingService = mock[FileTypeCheckingService]
      val fileCheckingService     = FileCheckingService(fileManager, virusScanningService, fileTypeCheckingService)

      when(virusScanningService.scan(location, content, metadata))
        .thenReturn(Future.successful(Right(NoVirusFound("CHECKSUM", Timings(clock.instant(), clock.instant())))))

      when(fileTypeCheckingService.scan(location, content, metadata))
        .thenReturn(Future.successful(Right(FileAllowed(MimeType("application/xml"), Timings(clock.instant(), clock.instant())))))

      fileCheckingService.check(location, metadata).futureValue shouldBe
        Right(FileValidationSuccess(
          "CHECKSUM",
          MimeType("application/xml"),
          Timings(clockStart.plusSeconds(0), clockStart.plusSeconds(1)),
          Timings(clockStart.plusSeconds(2), clockStart.plusSeconds(3))
        ))

    "do not check file type if virus found and return virus details" in:
      val virusScanningService    = mock[ScanningService]
      val fileTypeCheckingService = mock[FileTypeCheckingService]
      val fileCheckingService     = FileCheckingService(fileManager, virusScanningService, fileTypeCheckingService)
      val checksum: String        = "CHECKSUM"

      when(virusScanningService.scan(location, content, metadata))
        .thenReturn(Future.successful(Left(FileInfected("Virus", checksum, Timings(clock.instant(), clock.instant())))))

      fileCheckingService.check(location, metadata).futureValue shouldBe
        Left(FileRejected(Left(FileInfected("Virus", checksum, Timings(clockStart.plusSeconds(0), clockStart.plusSeconds(1))))))

      verifyNoInteractions(fileTypeCheckingService)

    "return failed file type scanning if virus not found but invalid file type" in:
      val virusScanningService    = mock[ScanningService]
      val fileTypeCheckingService = mock[FileTypeCheckingService]
      val fileCheckingService     = FileCheckingService(fileManager, virusScanningService, fileTypeCheckingService)

      when(virusScanningService.scan(location, content, metadata))
        .thenReturn(Future.successful(Right(NoVirusFound("CHECKSUM", Timings(clock.instant(), clock.instant())))))

      when(fileTypeCheckingService.scan(location, content, metadata)).thenReturn(Future.successful(Left(FileTypeError.NotAllowedMimeType(MimeType("application/xml"), Some("valid-test-service"), Timings(clock.instant(), clock.instant())))))

      fileCheckingService.check(location, metadata).futureValue shouldBe Left(
        FileRejected(
          Right(NoVirusFound("CHECKSUM", Timings(clockStart.plusSeconds(0), clockStart.plusSeconds(1)))),
          Some(FileTypeError.NotAllowedMimeType(
            MimeType("application/xml"),
            Some("valid-test-service"),
            Timings(clockStart.plusSeconds(2), clockStart.plusSeconds(3))
          )
        ))
      )
