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
    "return checksum if loading file succeeds" in {

      val fileManager = mock[FileManager]

      val checksumCalculationService = new SHA256ChecksumCalculator(fileManager)

      Given("there is a file on S3")

      val location    = S3ObjectLocation("inbound-bucket", "valid-file")
      val fileContent = StubObjectContent(new ByteArrayInputStream("TEST".getBytes), 4L)

      when(fileManager.getObjectContent(objectLocation = location)).thenReturn(Future.successful(fileContent))

      When("the file is checked")
      val result: String = Await.result(checksumCalculationService.calculateChecksum(location), 2.seconds)

      Then("a valid result should be returned")
      result shouldBe "94ee059335e587e501cc4bf90613e0814f00a7b08bc7c648fd865a2af6a22cc2"
    }

    "return failure if loading the file fails" in {
      val fileManager = mock[FileManager]

      val checksumCalculationService = new SHA256ChecksumCalculator(fileManager)

      Given("fetching the file from S3 fails")

      val location = S3ObjectLocation("inbound-bucket", "valid-file")

      when(fileManager.getObjectContent(objectLocation = location))
        .thenReturn(Future.failed(new Exception("Expected exception")))

      When("the file is checked")
      val calculationResult = checksumCalculationService.calculateChecksum(location)
      Await.ready(calculationResult, 2.seconds)

      Then("an exception should be returned")
      calculationResult.value.get.isFailure shouldBe true
    }
  }

}
