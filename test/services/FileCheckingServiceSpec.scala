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

import model.{FileInfectedCheckingResult, IncorrectFileType, S3ObjectLocation, ValidFileCheckingResult}
import org.mockito.Mockito
import org.mockito.Mockito.when
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{GivenWhenThen, Matchers}
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class FileCheckingServiceSpec extends UnitSpec with Matchers with GivenWhenThen with MockitoSugar {

  "File checking service" should {

    val location = S3ObjectLocation("bucket", "file")
    val metadata = ObjectMetadata(Map("consuming-service" -> "ScanUploadedFilesFlowSpec"), Instant.now)
    val content  = ObjectContent(new ByteArrayInputStream(Array.emptyByteArray), 0L)

    "succeed when virus and file type scanning succedded" in {

      val fileManager             = mock[FileManager]
      val virusScanningService    = mock[ScanningService]
      val fileTypeCheckingService = mock[FileTypeCheckingService]
      val fileCheckingService     = new FileCheckingService(fileManager, virusScanningService, fileTypeCheckingService)

      when(fileManager.getObjectContent(location)).thenReturn(Future.successful(content), Future.successful(content))
      when(virusScanningService.scan(location, content, metadata))
        .thenReturn(Future.successful(ValidFileCheckingResult(location)))
      when(fileTypeCheckingService.scan(location, content, metadata))
        .thenReturn(Future.successful(ValidFileCheckingResult(location)))

      Await.result(fileCheckingService.check(location, metadata), 30.seconds) shouldBe ValidFileCheckingResult(location)
    }

    "do not check file type if virus found and return virus details" in {

      val fileManager             = mock[FileManager]
      val virusScanningService    = mock[ScanningService]
      val fileTypeCheckingService = mock[FileTypeCheckingService]
      val fileCheckingService     = new FileCheckingService(fileManager, virusScanningService, fileTypeCheckingService)

      when(fileManager.getObjectContent(location)).thenReturn(Future.successful(content), Future.successful(content))
      when(virusScanningService.scan(location, content, metadata))
        .thenReturn(Future.successful(FileInfectedCheckingResult(location, "Virus")))
      when(fileTypeCheckingService.scan(location, content, metadata))
        .thenReturn(Future.successful(ValidFileCheckingResult(location)))

      Await.result(fileCheckingService.check(location, metadata), 30 seconds) shouldBe FileInfectedCheckingResult(
        location,
        "Virus")

      Mockito.verifyZeroInteractions(fileTypeCheckingService)

    }

    "return failed file type scanning if virus not found but invalid file type" in {

      val fileManager             = mock[FileManager]
      val virusScanningService    = mock[ScanningService]
      val fileTypeCheckingService = mock[FileTypeCheckingService]
      val fileCheckingService     = new FileCheckingService(fileManager, virusScanningService, fileTypeCheckingService)

      when(fileManager.getObjectContent(location)).thenReturn(Future.successful(content), Future.successful(content))
      when(virusScanningService.scan(location, content, metadata))
        .thenReturn(Future.successful(ValidFileCheckingResult(location)))
      when(fileTypeCheckingService.scan(location, content, metadata))
        .thenReturn(
          Future.successful(IncorrectFileType(location, MimeType("application/xml"), Some("valid-test-service"))))

      Await.result(fileCheckingService.check(location, metadata), 30 seconds) shouldBe IncorrectFileType(
        location,
        MimeType("application/xml"),
        Some("valid-test-service")
      )
    }
  }

}
