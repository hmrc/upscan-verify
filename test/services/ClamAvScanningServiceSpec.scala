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

import model.{S3ObjectLocation, UploadedFile}
import org.mockito.Mockito
import org.scalatest.{Assertions, GivenWhenThen, Matchers}
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.clamav.{ClamAntiVirus, ClamAntiVirusFactory}
import uk.gov.hmrc.play.test.UnitSpec
import org.mockito.ArgumentMatchers.any
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

class ClamAvScanningServiceSpec extends UnitSpec with Matchers with Assertions with GivenWhenThen with MockitoSugar {

  "ClamAvScanningService" should {
    val fileContents = "Hello World"

    val fileManager = new FileManager {
      override def delete(file: S3ObjectLocation): Future[Unit] = ???

      override def copyToOutboundBucket(file: S3ObjectLocation): Future[Unit] = ???

      override def getBytes(file: S3ObjectLocation): Future[Array[Byte]] = file.objectKey match {
        case "bad-file" => Future.failed(new RuntimeException("File not retrieved"))
        case _          => Future.successful(fileContents.getBytes)
      }
        Future(fileContents.getBytes)
    }

    "return success if file can be retrieved and scan result clean" in {
      val client = mock[ClamAntiVirus]
      Mockito.when(client.sendAndCheck(any())(any())).thenReturn(Future.successful(Success(())))

      val factory = mock[ClamAntiVirusFactory]
      Mockito.when(factory.getClient()).thenReturn(client)

      val scanningService = new ClamAvScanningService(factory, fileManager)

      Given("a file location pointing to a clean file")
      val fileLocation = S3ObjectLocation("inboundBucket", "file")
      val uploadedFile = UploadedFile(fileLocation)

      When("scanning service is called")
      val result = Await.result(scanningService.scan(uploadedFile), 2.seconds)

      Then("a scanning clean result should be returned")
      result shouldBe FileIsClean(fileLocation)
    }

    "return infected if file can be retrieved and scan result infected" in {
      val client = mock[ClamAntiVirus]
      Mockito.when(client.sendAndCheck(any())(any())).thenReturn(Future.successful(Failure(new RuntimeException("File dirty"))))

      val factory = mock[ClamAntiVirusFactory]
      Mockito.when(factory.getClient()).thenReturn(client)

      val scanningService = new ClamAvScanningService(factory, fileManager)

      Given("a file location pointing to a clean file")
      val fileLocation = S3ObjectLocation("inboundBucket", "file")
      val uploadedFile = UploadedFile(fileLocation)

      When("scanning service is called")
      val result = Await.result(scanningService.scan(uploadedFile), 2.seconds)

      Then("a scanning clean result should be returned")
      result shouldBe FileIsInfected(fileLocation, "File dirty")
    }

    "return failure if file cannot be retrieved" in {
      val client = mock[ClamAntiVirus]

      val factory = mock[ClamAntiVirusFactory]
      Mockito.when(factory.getClient()).thenReturn(client)

      val scanningService = new ClamAvScanningService(factory, fileManager)

      Given("a file location that cannot be retrieved from the file manager")
      val fileLocation = S3ObjectLocation("inboundBucket", "bad-file")
      val uploadedFile = UploadedFile(fileLocation)

      When("scanning service is called")
      val result = Await.ready(scanningService.scan(uploadedFile), 2.seconds)

      Then("error is returned")
      ScalaFutures.whenReady(result.failed) { error =>
        error shouldBe a[RuntimeException]
        error.getMessage shouldBe "File not retrieved"
      }
    }
  }
}
