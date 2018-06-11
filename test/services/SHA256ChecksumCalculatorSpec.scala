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

import model._
import org.mockito.Mockito.when
import org.scalatest.GivenWhenThen
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global

class SHA256ChecksumCalculatorSpec extends UnitSpec with GivenWhenThen with MockitoSugar {

  "SHA256ChecksumCalculator" should {

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
          val stubContent = s"contentOfDocument$objectLocation".getBytes
          val stubObject  = ObjectContent(new ByteArrayInputStream(stubContent), stubContent.length)
          function(stubObject)
        }

    }

    "return checksum if loading file succeeds" in {

      val checksumCalculationService = new SHA256ChecksumCalculator(fileManager)

      Given("there is a file on S3")

      val location = S3ObjectLocation("inbound-bucket", "valid-file")

      When("the file is checked")
      val result: String = Await.result(checksumCalculationService.calculateChecksum(location), 2.seconds)

      Then("a valid result should be returned")
      result shouldBe "396f101dd52e8b2ace0dcf5ed09b1d1f030e608938510ce46e7a5c7a4e775100"
    }

    "return failure if loading the file fails" in {

      val checksumCalculationService = new SHA256ChecksumCalculator(fileManager)

      Given("fetching the file from S3 fails")

      val location = S3ObjectLocation("inbound-bucket", "exception")

      When("the file is checked")
      val calculationResult = checksumCalculationService.calculateChecksum(location)
      Await.ready(calculationResult, 2.seconds)

      Then("an exception should be returned")
      calculationResult.value.get.isFailure shouldBe true
    }
  }

}
