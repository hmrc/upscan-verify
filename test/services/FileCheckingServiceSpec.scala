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

import java.io.{ByteArrayInputStream, InputStream}
import java.time.Instant

import model._
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.{verifyZeroInteractions, when}
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{GivenWhenThen, Matchers}
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class FileCheckingServiceSpec extends UnitSpec with Matchers with GivenWhenThen with MockitoSugar {

  "File checking service" should {

    val content = ObjectContent(new ByteArrayInputStream("TEST".getBytes), "TEST".length)

    val fileManager = new FileManager {
      override def copyToOutboundBucket(
        objectLocation: S3ObjectLocation,
        metadata: OutboundObjectMetadata): Future[Unit] = ???

      override def writeToQuarantineBucket(
        objectLocation: S3ObjectLocation,
        content: InputStream,
        metadata: OutboundObjectMetadata): Future[Unit] = ???

      override def delete(objectLocation: S3ObjectLocation): Future[Unit] = ???

      override def getObjectMetadata(objectLocation: S3ObjectLocation): Future[InboundObjectMetadata] = ???

      override def withObjectContent[T](objectLocation: S3ObjectLocation)(
        function: ObjectContent => Future[T]): Future[T] =
        if (objectLocation.objectKey.contains("exception")) {
          Future.failed(new RuntimeException("Expected exception"))
        } else {
          function(content)
        }

    }

    val location = S3ObjectLocation("bucket", "file")
    val metadata = InboundObjectMetadata(Map("consuming-service" -> "ScanUploadedFilesFlowSpec"), Instant.now)

    "succeed when virus and file type scanning succedded" in {

      val virusScanningService    = mock[ScanningService]
      val fileTypeCheckingService = mock[FileTypeCheckingService]
      val fileCheckingService     = new FileCheckingService(fileManager, virusScanningService, fileTypeCheckingService)

      when(virusScanningService.scan(location, content, metadata))
        .thenReturn(Future.successful(Right(NoVirusFound("CHECKSUM"))))

      when(fileTypeCheckingService.scan(location, content, metadata))
        .thenReturn(Future.successful(Right(FileAllowed(MimeType("application/xml")))))

      Await.result(fileCheckingService.check(location, metadata), 30.seconds) shouldBe
        Right(FileValidationSuccess("CHECKSUM", MimeType("application/xml")))

    }

    "do not check file type if virus found and return virus details" in {

      val virusScanningService    = mock[ScanningService]
      val fileTypeCheckingService = mock[FileTypeCheckingService]
      val fileCheckingService     = new FileCheckingService(fileManager, virusScanningService, fileTypeCheckingService)

      when(virusScanningService.scan(location, content, metadata))
        .thenReturn(Future.successful(Left(FileInfected("Virus"))))
      when(fileTypeCheckingService.scan(location, content, metadata))
        .thenReturn(Future.successful(Right(FileAllowed(MimeType("application/xml")))))

      Await.result(fileCheckingService.check(location, metadata), 30 seconds) shouldBe Left(FileInfected("Virus"))

      verifyZeroInteractions(fileTypeCheckingService)
    }

    "return failed file type scanning if virus not found but invalid file type" in {

      val virusScanningService    = mock[ScanningService]
      val fileTypeCheckingService = mock[FileTypeCheckingService]
      val fileCheckingService     = new FileCheckingService(fileManager, virusScanningService, fileTypeCheckingService)

      when(virusScanningService.scan(location, content, metadata))
        .thenReturn(Future.successful(Right(NoVirusFound("CHECKSUM"))))

      when(fileTypeCheckingService.scan(location, content, metadata))
        .thenReturn(Future.successful(Left(IncorrectFileType(MimeType("application/xml"), Some("valid-test-service")))))

      Await.result(fileCheckingService.check(location, metadata), 30 seconds) shouldBe Left(
        IncorrectFileType(
          MimeType("application/xml"),
          Some("valid-test-service")
        ))

    }
  }
}
