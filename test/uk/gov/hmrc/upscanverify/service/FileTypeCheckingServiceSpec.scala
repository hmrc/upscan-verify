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

import com.codahale.metrics.MetricRegistry
import org.mockito.Mockito.when
import org.scalatest.GivenWhenThen
import org.scalatest.concurrent.ScalaFutures
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.metrics.Metrics
import uk.gov.hmrc.upscanverify.config.ServiceConfiguration
import uk.gov.hmrc.upscanverify.model._
import uk.gov.hmrc.upscanverify.service.tika.{FileNameValidator, TikaMimeTypeDetector}
import uk.gov.hmrc.upscanverify.test.{UnitSpec, WithIncrementingClock}
import uk.gov.hmrc.upscanverify.util.logging.LoggingDetails

import java.io.{ByteArrayInputStream, InputStream}
import java.time.Instant
import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global

class FileTypeCheckingServiceSpec
  extends UnitSpec
     with GivenWhenThen
     with WithIncrementingClock
     with ScalaFutures:

  override lazy val clockStart = Instant.parse("2018-12-04T17:48:30Z")

  given HeaderCarrier = LoggingDetails.fromMessageContext(MessageContext("TEST"))

  def metricsStub() = new Metrics:
    override val defaultRegistry: MetricRegistry =
      MetricRegistry()

  "FileTypeCheckingService" should:
    "return valid result for allowed mime type for the service" in:
      Given("an uploaded file with a valid MIME type for the service")
      val serviceName = "valid-test-service"
      val allowedMimeTypes = List("application/pdf")

      val location = S3ObjectLocation("inbound-bucket", "valid-file", None)
      val content  = ObjectContent(null, 1200)
      val filename = "some-file"
      val metadata = InboundObjectMetadata(Map("consuming-service" -> serviceName, "original-filename" -> filename), Instant.now(), 0)
      val mimeType = MimeType("application/pdf")
      val detector = new MimeTypeDetector:
        override def detect(inputStream: InputStream, fileName: Option[String]) =
          mimeType

      val configuration = mock[ServiceConfiguration]
      when(configuration.allowedMimeTypes(serviceName))
        .thenReturn(Some(allowedMimeTypes))

      val metrics               = metricsStub()
      val mockFilenameValidator = mock[FileNameValidator]
      val checkingService       = FileTypeCheckingService(detector, mockFilenameValidator, configuration, metrics)(using summon[ExecutionContext], clock)

      When("the file is checked")
      when(mockFilenameValidator.validate(mimeType, filename))
        .thenReturn(Right(()))
      val result = checkingService.scan(location, content, metadata).futureValue

      Then("a valid result should be returned")
      result shouldBe Right(
        FileAllowed(
          MimeType("application/pdf"),
          Timings(Instant.parse("2018-12-04T17:48:30Z"), Instant.parse("2018-12-04T17:48:32Z"))
        )
      )

      And("the metrics should be successfully updated")
      metrics.defaultRegistry.counter("validTypeFileUpload").getCount        shouldBe 1
      metrics.defaultRegistry.counter("invalidTypeFileUpload").getCount      shouldBe 0
      metrics.defaultRegistry.timer("fileTypeCheckingTime").getSnapshot.size shouldBe 1

    "return invalid result when mime type not valid for service" in:
      Given("an uploaded file with a valid MIME type for the service")
      val serviceName = "valid-test-service"
      val allowedMimeTypes = List("application/pdf")

      val location = S3ObjectLocation("inbound-bucket", "valid-file", None)
      val content = ObjectContent(null, 1200)
      val metadata = InboundObjectMetadata(Map("consuming-service" -> "valid-test-service", "original-filename" -> "some-file"), Instant.now(), 0)

      val fileMimeType = MimeType("image/jpeg")
      val mockFilenameValidator = mock[FileNameValidator]

      val detector = new MimeTypeDetector:
        override def detect(inputStream: InputStream, fileName: Option[String]) =
          fileMimeType

      val configuration = mock[ServiceConfiguration]
      when(configuration.allowedMimeTypes(serviceName))
        .thenReturn(Some(allowedMimeTypes))

      val metrics = metricsStub()
      val checkingService = FileTypeCheckingService(detector, mockFilenameValidator, configuration, metrics)(using summon[ExecutionContext], clock)

      When("the file is checked")
      val result = checkingService.scan(location, content, metadata).futureValue

      Then("an incorrect file type result should be returned")
      result shouldBe Left(
        FileTypeError.NotAllowedMimeType(
          fileMimeType,
          Some("valid-test-service"),
          Timings(Instant.parse("2018-12-04T17:48:30Z"), Instant.parse("2018-12-04T17:48:32Z"))
        )
      )

      And("the metrics should be successfully updated")
      metrics.defaultRegistry.counter("validTypeFileUpload").getCount        shouldBe 0
      metrics.defaultRegistry.counter("invalidTypeFileUpload").getCount      shouldBe 1
      metrics.defaultRegistry.timer("fileTypeCheckingTime").getSnapshot.size shouldBe 1

    "return invalid result when file extension for detected mimetype is not allowed" in:
      Given("an uploaded file with a valid MIME type for the service")
      val serviceName = "valid-test-service"
      val allowedMimeTypes = List("application/pdf")

      val location = S3ObjectLocation("inbound-bucket", "valid-file", None)
      val content  = ObjectContent(null, 1200)
      val filename = "file.foo"
      val metadata = InboundObjectMetadata(Map("consuming-service" -> "valid-test-service", "original-filename" -> filename), Instant.now(), 0)
      val mimeType = MimeType("application/pdf")
      val detector = new MimeTypeDetector:
        override def detect(inputStream: InputStream, fileName: Option[String]) =
          mimeType

      val configuration = mock[ServiceConfiguration]
      when(configuration.allowedMimeTypes(serviceName))
        .thenReturn(Some(allowedMimeTypes))

      val metrics = metricsStub()
      val mockFilenameValidator = mock[FileNameValidator]
      val checkingService = FileTypeCheckingService(detector, mockFilenameValidator, configuration, metrics)(using summon[ExecutionContext], clock)

      When("the file is checked")
      when(mockFilenameValidator.validate(mimeType, filename))
        .thenReturn(Left("foo"))
      val result = checkingService.scan(location, content, metadata).futureValue

      Then("an incorrect file type result should be returned")
      result shouldBe Left(
        FileTypeError.NotAllowedFileExtension(
          mimeType,
          "foo",
          Some("valid-test-service"),
          Timings(Instant.parse("2018-12-04T17:48:30Z"), Instant.parse("2018-12-04T17:48:32Z"))
        )
      )

      And("the metrics should be successfully updated")
      metrics.defaultRegistry.counter("validTypeFileUpload").getCount        shouldBe 0
      metrics.defaultRegistry.counter("invalidTypeFileUpload").getCount      shouldBe 1
      metrics.defaultRegistry.timer("fileTypeCheckingTime").getSnapshot.size shouldBe 1

    "return valid result when file meets default allowed mime types" in:
      Given("an uploaded file with a valid MIME type for the service")
      val serviceName = "valid-test-service"
      val defaultAllowedMimeTypes = List("application/pdf")

      val location = S3ObjectLocation("inbound-bucket", "valid-file", None)
      val content  = ObjectContent(null, 1200)
      val filename = "some-file"
      val metadata = InboundObjectMetadata(Map("consuming-service" -> serviceName, "original-filename" -> filename), Instant.now(), 0)

      val fileMimeType = MimeType("application/pdf")
      val detector = new MimeTypeDetector:
        override def detect(inputStream: InputStream, fileName: Option[String]) =
          fileMimeType

      val configuration = mock[ServiceConfiguration]
      when(configuration.allowedMimeTypes(serviceName))
        .thenReturn(None)
      when(configuration.defaultAllowedMimeTypes)
        .thenReturn(defaultAllowedMimeTypes)

      val metrics = metricsStub()
      val mockFilenameValidator = mock[FileNameValidator]
      val checkingService = FileTypeCheckingService(detector, mockFilenameValidator, configuration, metrics)(using summon[ExecutionContext], clock)

      When("the file is checked")
      when(mockFilenameValidator.validate(fileMimeType, filename))
        .thenReturn(Right(()))
      val result = checkingService.scan(location, content, metadata).futureValue

      Then("a valid result should be returned")
      result shouldBe Right(
        FileAllowed(
          MimeType("application/pdf"),
          Timings(Instant.parse("2018-12-04T17:48:30Z"), Instant.parse("2018-12-04T17:48:32Z"))))

      And("the metrics should be successfully updated")
      metrics.defaultRegistry.counter("validTypeFileUpload").getCount        shouldBe 1
      metrics.defaultRegistry.counter("invalidTypeFileUpload").getCount      shouldBe 0
      metrics.defaultRegistry.timer("fileTypeCheckingTime").getSnapshot.size shouldBe 1

    "return valid result when file meets default allowed mime types and no filename is provided" in:
      Given("an uploaded file with a valid MIME type for the service")
      val serviceName = "valid-test-service"
      val defaultAllowedMimeTypes = List("application/pdf")

      val location = S3ObjectLocation("inbound-bucket", "valid-file", None)
      val content  = ObjectContent(null, 1200)
      val metadata = InboundObjectMetadata(Map("consuming-service" -> serviceName), Instant.now(), 0)

      val fileMimeType = MimeType("application/pdf")
      val detector = new MimeTypeDetector:
        override def detect(inputStream: InputStream, fileName: Option[String]) =
          fileMimeType

      val configuration = mock[ServiceConfiguration]
      when(configuration.allowedMimeTypes(serviceName))
        .thenReturn(None)
      when(configuration.defaultAllowedMimeTypes)
        .thenReturn(defaultAllowedMimeTypes)

      val metrics               = metricsStub()
      val mockFilenameValidator = mock[FileNameValidator]
      val checkingService       = FileTypeCheckingService(detector, mockFilenameValidator, configuration, metrics)(using summon[ExecutionContext], clock)

      When("the file is checked")
      val result = checkingService.scan(location, content, metadata).futureValue

      Then("a valid result should be returned")
      result shouldBe Right(
        FileAllowed(
          MimeType("application/pdf"),
          Timings(Instant.parse("2018-12-04T17:48:30Z"), Instant.parse("2018-12-04T17:48:32Z"))
        )
      )

      And("the metrics should be successfully updated")
      metrics.defaultRegistry.counter("validTypeFileUpload").getCount        shouldBe 1
      metrics.defaultRegistry.counter("invalidTypeFileUpload").getCount      shouldBe 0
      metrics.defaultRegistry.timer("fileTypeCheckingTime").getSnapshot.size shouldBe 1

    "return invalid result when MIME type cannot be determined due to a 0 byte upload" in:
      Given("an uploaded file with a invalid MIME type for the service (application/octet-stream)")
      val serviceName      = "valid-test-service"
      val allowedMimeTypes = List("application/pdf")

      val location = S3ObjectLocation("inbound-bucket", "valid-file", None)
      val content  = ObjectContent(ByteArrayInputStream(Array.emptyByteArray), 0)
      val metadata = InboundObjectMetadata(Map("consuming-service" -> serviceName), Instant.now(), 0)

      val fileMimeType = MimeType("application/octet-stream")
      val detector     = TikaMimeTypeDetector()

      val configuration = mock[ServiceConfiguration]
      when(configuration.allowedMimeTypes(serviceName))
        .thenReturn(Some(allowedMimeTypes))

      val metrics               = metricsStub()
      val mockFilenameValidator = mock[FileNameValidator]
      val checkingService       = FileTypeCheckingService(detector, mockFilenameValidator, configuration, metrics)(using summon[ExecutionContext], clock)

      When("the file is checked")
      val result = checkingService.scan(location, content, metadata).futureValue

      Then("an incorrect file type result should be returned")
      result shouldBe Left(
        FileTypeError.NotAllowedMimeType(
          fileMimeType,
          Some("valid-test-service"),
          Timings(Instant.parse("2018-12-04T17:48:30Z"), Instant.parse("2018-12-04T17:48:32Z"))
        )
      )

      And("the metrics should be successfully updated")
      metrics.defaultRegistry.counter("validTypeFileUpload").getCount        shouldBe 0
      metrics.defaultRegistry.counter("invalidTypeFileUpload").getCount      shouldBe 1
      metrics.defaultRegistry.timer("fileTypeCheckingTime").getSnapshot.size shouldBe 1
