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

package model

import org.scalatest.GivenWhenThen
import uk.gov.hmrc.clamav.model.{Clean, Infected}
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class FileCheckingResultSpec extends UnitSpec with GivenWhenThen {
  "FileCheckingResult" should {

    "chain two different successful check results" in {
      Given("a successful file check result")
      val fileLocation         = S3ObjectLocation("some-bucket-name", "some-object-key")
      val firstSuccessfulCheck = ValidFileCheckingResult(fileLocation)

      When("the result is chained to a second successful file check result")
      val otherFileLocation = S3ObjectLocation("some-other-bucket-name", "some-other-object-key")
      val secondSuccessfulCheck: () => Future[FileCheckingResult] = { () =>
        Future.successful(ValidFileCheckingResult(otherFileLocation))
      }

      Then("the eventual result should be successful")
      val eventualResult: FileCheckingResult =
        Await.result(firstSuccessfulCheck.andThen(secondSuccessfulCheck), 2.seconds)
      eventualResult shouldBe ValidFileCheckingResult(otherFileLocation)
    }

    "chain two different failed check results" in {
      Given("a failed file check result")
      val fileLocation     = S3ObjectLocation("some-bucket-name", "some-object-key")
      val firstFailedCheck = FileInfectedCheckingResult(fileLocation, "Some first error")

      When("the result is chained to a second failed file check result")
      val otherFileLocation = S3ObjectLocation("some-other-bucket-name", "some-other-object-key")
      val secondFailedCheck: () => Future[FileCheckingResult] = { () =>
        Future.successful(FileInfectedCheckingResult(otherFileLocation, "Some second error"))
      }

      Then("the eventual result should be the first failed check")
      val eventualResult: FileCheckingResult = Await.result(firstFailedCheck.andThen(secondFailedCheck), 2.seconds)
      eventualResult shouldBe FileInfectedCheckingResult(fileLocation, "Some first error")
    }

    "chain a successful followed by failure check results" in {
      Given("a failed file check result")
      val fileLocation         = S3ObjectLocation("some-bucket-name", "some-object-key")
      val firstSuccessfulCheck = ValidFileCheckingResult(fileLocation)

      When("the result is chained to a successful file check result")
      val otherFileLocation = S3ObjectLocation("some-other-bucket-name", "some-other-object-key")
      val secondFailedCheck: () => Future[FileCheckingResult] = { () =>
        Future.successful(FileInfectedCheckingResult(otherFileLocation, "Some second error"))
      }

      Then("the eventual result should be the second failed check")
      val eventualResult: FileCheckingResult = Await.result(firstSuccessfulCheck.andThen(secondFailedCheck), 2.seconds)
      eventualResult shouldBe FileInfectedCheckingResult(otherFileLocation, "Some second error")
    }

    "chain a failure followed by successful checking results" in {
      Given("a failed file check result")
      val fileLocation     = S3ObjectLocation("some-bucket-name", "some-object-key")
      val firstFailedCheck = FileInfectedCheckingResult(fileLocation, "Some first error")

      When("the result is chained to a successful file check result")
      val otherFileLocation = S3ObjectLocation("some-other-bucket-name", "some-other-object-key")
      val secondSuccessfulCheck: () => Future[FileCheckingResult] = { () =>
        Future.successful(ValidFileCheckingResult(otherFileLocation))
      }

      Then("the eventual result should be the first failed check")
      val eventualResult: FileCheckingResult = Await.result(firstFailedCheck.andThen(secondSuccessfulCheck), 2.seconds)
      eventualResult shouldBe FileInfectedCheckingResult(fileLocation, "Some first error")
    }
  }
}
