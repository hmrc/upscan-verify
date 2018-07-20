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

import java.io.InputStream
import java.time.Instant

import config.ServiceConfiguration
import model._
import org.mockito.Mockito.when
import org.scalatest.GivenWhenThen
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.play.test.UnitSpec
import com.kenshoo.play.metrics.Metrics
import com.codahale.metrics.MetricRegistry
import util.logging.LoggingDetails

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

class FileTypeCheckingServiceSpec extends UnitSpec with GivenWhenThen with MockitoSugar {

  implicit val ld = LoggingDetails.fromMessageContext(MessageContext("TEST"))

  def metricsStub() = new Metrics {
    override val defaultRegistry: MetricRegistry = new MetricRegistry

    override def toJson: String = ???
  }

  "FileTypeCheckingService" should {
    "return valid result for whitelisted mime type for service" in {

      Given("an uploaded file with a valid MIME type for the service")
      val allowedMimeTypes               = AllowedMimeTypes("valid-test-service", List("application/pdf"))
      val consumingServicesConfiguration = ConsumingServicesConfiguration(List(allowedMimeTypes))

      val location = S3ObjectLocation("inbound-bucket", "valid-file", None)
      val content  = ObjectContent(null, 1200)
      val metadata = InboundObjectMetadata(Map("consuming-service" -> "valid-test-service"), Instant.now)

      val detector = new FileTypeDetector {
        override def detectType(inputStream: InputStream, fileName: Option[String]): MimeType =
          MimeType("application/pdf")
      }

      val configuration = mock[ServiceConfiguration]
      when(configuration.consumingServicesConfiguration).thenReturn(consumingServicesConfiguration)

      val metrics         = metricsStub()
      val checkingService = new FileTypeCheckingService(detector, configuration, metrics)

      When("the file is checked")
      val result: Either[FileValidationFailure, FileAllowed] =
        Await.result(checkingService.scan(location, content, metadata), 2.seconds)

      Then("a valid result should be returned")
      result shouldBe Right(FileAllowed(MimeType("application/pdf")))

      And("the metrics should be successfully updated")
      metrics.defaultRegistry.counter("validTypeFileUpload").getCount          shouldBe 1
      metrics.defaultRegistry.counter("invalidTypeFileUpload").getCount        shouldBe 0
      metrics.defaultRegistry.timer("fileTypeCheckingTime").getSnapshot.size() shouldBe 1
    }

    "return invalid result when mime type not listed for service" in {

      Given("an uploaded file with a valid MIME type for the service")
      val allowedMimeTypes               = AllowedMimeTypes("valid-test-service", List("application/pdf"))
      val consumingServicesConfiguration = ConsumingServicesConfiguration(List(allowedMimeTypes))

      val location = S3ObjectLocation("inbound-bucket", "valid-file", None)
      val content  = ObjectContent(null, 1200)
      val metadata = InboundObjectMetadata(Map("consuming-service" -> "valid-test-service"), Instant.now)

      val fileMimeType = MimeType("image/jpeg")
      val detector = new FileTypeDetector {
        override def detectType(inputStream: InputStream, fileName: Option[String]): MimeType =
          fileMimeType
      }

      val configuration = mock[ServiceConfiguration]
      when(configuration.consumingServicesConfiguration).thenReturn(consumingServicesConfiguration)

      val metrics         = metricsStub()
      val checkingService = new FileTypeCheckingService(detector, configuration, metrics)

      When("the file is checked")
      val result = Await.result(checkingService.scan(location, content, metadata), 2.seconds)

      Then("an incorrect file type result should be returned")
      result shouldBe Left(IncorrectFileType(fileMimeType, Some("valid-test-service")))

      And("the metrics should be successfully updated")
      metrics.defaultRegistry.counter("validTypeFileUpload").getCount          shouldBe 0
      metrics.defaultRegistry.counter("invalidTypeFileUpload").getCount        shouldBe 1
      metrics.defaultRegistry.timer("fileTypeCheckingTime").getSnapshot.size() shouldBe 1
    }

    "return invalid result when file does not have service name" in {

      Given("an uploaded file with a valid MIME type for the service")
      val allowedMimeTypes               = AllowedMimeTypes("valid-test-service", List("application/pdf"))
      val consumingServicesConfiguration = ConsumingServicesConfiguration(List(allowedMimeTypes))

      val location = S3ObjectLocation("inbound-bucket", "valid-file", None)
      val content  = ObjectContent(null, 1200)
      val metadata = InboundObjectMetadata(Map.empty, Instant.now)

      val fileMimeType = MimeType("application/pdf")
      val detector = new FileTypeDetector {
        override def detectType(inputStream: InputStream, fileName: Option[String]): MimeType =
          fileMimeType
      }

      val configuration = mock[ServiceConfiguration]
      when(configuration.consumingServicesConfiguration).thenReturn(consumingServicesConfiguration)

      val metrics         = metricsStub()
      val checkingService = new FileTypeCheckingService(detector, configuration, metrics)

      When("the file is checked")
      val result = Await.result(checkingService.scan(location, content, metadata), 2.seconds)

      Then("an incorrect file type result should be returned")
      result shouldBe Left(IncorrectFileType(fileMimeType, None))

      And("the metrics should be successfully updated")
      metrics.defaultRegistry.counter("validTypeFileUpload").getCount          shouldBe 0
      metrics.defaultRegistry.counter("invalidTypeFileUpload").getCount        shouldBe 1
      metrics.defaultRegistry.timer("fileTypeCheckingTime").getSnapshot.size() shouldBe 1
    }
  }
}
