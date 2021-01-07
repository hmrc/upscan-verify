/*
 * Copyright 2021 HM Revenue & Customs
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

import java.io.InputStream
import java.time.Instant

import config.ServiceConfiguration
import model._
import org.scalatest.GivenWhenThen
import com.kenshoo.play.metrics.Metrics
import com.codahale.metrics.MetricRegistry
import test.{UnitSpec, WithIncrementingClock}
import util.logging.LoggingDetails

import scala.concurrent.Await
import scala.concurrent.duration._

class FileTypeCheckingServiceSpec extends UnitSpec with GivenWhenThen with WithIncrementingClock {

  override lazy val clockStart = Instant.parse("2018-12-04T17:48:30Z")

  implicit val ld = LoggingDetails.fromMessageContext(MessageContext("TEST"))

  def metricsStub() = new Metrics {
    override val defaultRegistry: MetricRegistry = new MetricRegistry

    override def toJson: String = ???
  }

  "FileTypeCheckingService" should {
    "return valid result for allowed mime type for the service" in {

      Given("an uploaded file with a valid MIME type for the service")
      val serviceName                    = "valid-test-service"
      val allowedMimeTypes               = List("application/pdf")

      val location = S3ObjectLocation("inbound-bucket", "valid-file", None)
      val content  = ObjectContent(null, 1200)
      val metadata = InboundObjectMetadata(Map("consuming-service" -> serviceName), Instant.now, 0)

      val detector = new FileTypeDetector {
        override def detectType(inputStream: InputStream, fileName: Option[String]): MimeType =
          MimeType("application/pdf")
      }

      val configuration = mock[ServiceConfiguration]
      when(configuration.allowedMimeTypes(serviceName)).thenReturn(Some(allowedMimeTypes))

      val metrics         = metricsStub()
      val checkingService = new FileTypeCheckingService(detector, configuration, metrics, clock)

      When("the file is checked")
      val result: Either[FileValidationFailure, FileAllowed] =
        Await.result(checkingService.scan(location, content, metadata), 2.seconds)

      Then("a valid result should be returned")
      result shouldBe Right(FileAllowed(MimeType("application/pdf"), Timings(Instant.parse("2018-12-04T17:48:30Z"), Instant.parse("2018-12-04T17:48:32Z"))))

      And("the metrics should be successfully updated")
      metrics.defaultRegistry.counter("validTypeFileUpload").getCount          shouldBe 1
      metrics.defaultRegistry.counter("invalidTypeFileUpload").getCount        shouldBe 0
      metrics.defaultRegistry.timer("fileTypeCheckingTime").getSnapshot.size() shouldBe 1
    }

    "return invalid result when mime type not valid for service" in {

      Given("an uploaded file with a valid MIME type for the service")
      val serviceName                    = "valid-test-service"
      val allowedMimeTypes               = List("application/pdf")

      val location = S3ObjectLocation("inbound-bucket", "valid-file", None)
      val content  = ObjectContent(null, 1200)
      val metadata = InboundObjectMetadata(Map("consuming-service" -> "valid-test-service"), Instant.now, 0)

      val fileMimeType = MimeType("image/jpeg")
      val detector = new FileTypeDetector {
        override def detectType(inputStream: InputStream, fileName: Option[String]): MimeType =
          fileMimeType
      }

      val configuration = mock[ServiceConfiguration]
      when(configuration.allowedMimeTypes(serviceName)).thenReturn(Some(allowedMimeTypes))

      val metrics         = metricsStub()
      val checkingService = new FileTypeCheckingService(detector, configuration, metrics, clock)

      When("the file is checked")
      val result = Await.result(checkingService.scan(location, content, metadata), 2.seconds)

      Then("an incorrect file type result should be returned")
      result shouldBe Left(IncorrectFileType(fileMimeType, Some("valid-test-service"), Timings(Instant.parse("2018-12-04T17:48:30Z"), Instant.parse("2018-12-04T17:48:32Z"))))

      And("the metrics should be successfully updated")
      metrics.defaultRegistry.counter("validTypeFileUpload").getCount          shouldBe 0
      metrics.defaultRegistry.counter("invalidTypeFileUpload").getCount        shouldBe 1
      metrics.defaultRegistry.timer("fileTypeCheckingTime").getSnapshot.size() shouldBe 1
    }

    "return valid result when file meets default allowed mime types" in {

      Given("an uploaded file with a valid MIME type for the service")
      val serviceName                    = "valid-test-service"
      val defaultAllowedMimeTypes        = List("application/pdf")

      val location = S3ObjectLocation("inbound-bucket", "valid-file", None)
      val content  = ObjectContent(null, 1200)
      val metadata = InboundObjectMetadata(Map("consuming-service" -> serviceName), Instant.now, 0)

      val fileMimeType = MimeType("application/pdf")
      val detector = new FileTypeDetector {
        override def detectType(inputStream: InputStream, fileName: Option[String]): MimeType =
          fileMimeType
      }

      val configuration = mock[ServiceConfiguration]
      when(configuration.allowedMimeTypes(serviceName)).thenReturn(None)
      when(configuration.defaultAllowedMimeTypes).thenReturn(defaultAllowedMimeTypes)

      val metrics         = metricsStub()
      val checkingService = new FileTypeCheckingService(detector, configuration, metrics, clock)

      When("the file is checked")
      val result = Await.result(checkingService.scan(location, content, metadata), 2.seconds)

      Then("a valid result should be returned")
      result shouldBe Right(FileAllowed(MimeType("application/pdf"), Timings(Instant.parse("2018-12-04T17:48:30Z"), Instant.parse("2018-12-04T17:48:32Z"))))

      And("the metrics should be successfully updated")
      metrics.defaultRegistry.counter("validTypeFileUpload").getCount          shouldBe 1
      metrics.defaultRegistry.counter("invalidTypeFileUpload").getCount        shouldBe 0
      metrics.defaultRegistry.timer("fileTypeCheckingTime").getSnapshot.size() shouldBe 1

    }
  }
}
