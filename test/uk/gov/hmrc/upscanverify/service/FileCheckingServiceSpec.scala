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

import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{verifyNoInteractions, when}
import org.scalatest.GivenWhenThen
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.mockito.MockitoSugar.mock
import uk.gov.hmrc.upscanverify.model._
import uk.gov.hmrc.upscanverify.test.{UnitSpec, WithIncrementingClock}

import java.io.ByteArrayInputStream
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
    "succeed when virus and file type scanning succeeded" in new Setup:
      val noVirusFound = VirusScanResult.NoVirusFound("CHECKSUM", Timings(clock.instant(), clock.instant()))
      val fileAllowed  = FileAllowed(MimeType("application/xml"), Timings(clock.instant(), clock.instant()))

      when(virusScanningService.scan(location, content, metadata))
        .thenReturn(Future.successful(Right(noVirusFound)))

      when(fileTypeCheckingService.scan(location, content, metadata))
        .thenReturn(Future.successful(Right(fileAllowed)))

      fileCheckingService.check(location, metadata).futureValue shouldBe
        Right(VerifyResult.FileValidationSuccess(noVirusFound, fileAllowed))

    "do not check file type if virus found and return virus details" in new Setup:
      val fileInfected = VirusScanResult.FileInfected("Virus", "CHECKSUM", Timings(clock.instant(), clock.instant()))

      when(virusScanningService.scan(location, content, metadata))
        .thenReturn(Future.successful(Left(fileInfected)))

      fileCheckingService.check(location, metadata).futureValue shouldBe
        Left(VerifyResult.FileRejected.VirusScanFailure(fileInfected))

      verifyNoInteractions(fileTypeCheckingService)

    "return failed file type scanning if virus not found but invalid file type" in new Setup:
      val noVirusFound       = VirusScanResult.NoVirusFound("CHECKSUM", Timings(clock.instant(), clock.instant()))
      val notAllowedMimeType = FileTypeError.NotAllowedMimeType(MimeType("application/xml"), Some("valid-test-service"), Timings(clock.instant(), clock.instant()))

      when(virusScanningService.scan(location, content, metadata))
        .thenReturn(Future.successful(Right(noVirusFound)))

      when(fileTypeCheckingService.scan(location, content, metadata))
        .thenReturn(Future.successful(Left(notAllowedMimeType)))

      fileCheckingService.check(location, metadata).futureValue shouldBe
        Left(VerifyResult.FileRejected.FileTypeFailure(noVirusFound, notAllowedMimeType))

  trait Setup:
    val content = ObjectContent(ByteArrayInputStream("TEST".getBytes), "TEST".length)

    val fileManager    = mock[FileManager]
    val functionCaptor = ArgumentCaptor.forClass(classOf[ObjectContent => Future[Nothing]]) // using argument capture since it can match the function param
    when(fileManager.withObjectContent(any[S3ObjectLocation])(functionCaptor.capture()))
      .thenAnswer { i =>
        val objectLocation = i.getArgument[S3ObjectLocation](0)
        val function       = i.getArgument[ObjectContent => Future[Nothing]](1) // same as functionCaptor.getValue
        if objectLocation.objectKey.contains("exception") then
          Future.failed(RuntimeException("Expected exception"))
        else
          function(content)
      }

    val location = S3ObjectLocation("bucket", "file", None)
    val metadata = InboundObjectMetadata(Map("consuming-service" -> "ScanUploadedFilesFlowSpec"), clock.instant(), 0)

    val virusScanningService    = mock[ScanningService]
    val fileTypeCheckingService = mock[FileTypeCheckingService]
    val fileCheckingService     = FileCheckingService(fileManager, virusScanningService, fileTypeCheckingService)
