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
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatest.{Assertions, GivenWhenThen}
import org.scalatest.concurrent.ScalaFutures
import uk.gov.hmrc.clamav.model.ScanningResult
import uk.gov.hmrc.clamav.{ClamAntiVirus, ClamAntiVirusFactory}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.logging.LoggingDetails
import uk.gov.hmrc.play.bootstrap.metrics.Metrics
import uk.gov.hmrc.upscanverify.model.{FileInfected, S3ObjectLocation, Timings}
import uk.gov.hmrc.upscanverify.test.{UnitSpec, WithIncrementingClock}
import uk.gov.hmrc.upscanverify.util.logging.LoggingDetails

import java.io.{ByteArrayInputStream, FilterInputStream, InputStream}
import java.time.{Instant, LocalDateTime, ZoneOffset}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.ExecutionContext.Implicits.global

class ClamAvScanningServiceSpec
  extends UnitSpec
     with Assertions
     with GivenWhenThen
     with WithIncrementingClock
     with ScalaFutures:

  given HeaderCarrier = LoggingDetails.fromMessageContext(MessageContext("TEST"))

  override lazy val clockStart = Instant.parse("2018-12-04T17:48:30Z")

  "ClamAvScanningService" should:

    def metricsStub() = new Metrics:
      override val defaultRegistry: MetricRegistry =
        new MetricRegistry()

    val checksumInputStreamFactoryStub = new ChecksumComputingInputStreamFactory:
      override def create(source: InputStream): InputStream with ChecksumSource =
        new FilterInputStream(source) with ChecksumSource:
          override def getChecksum(): String = "CHECKSUM"

    "return success if file can be retrieved and scan result clean" in:
      val client = mock[ClamAntiVirus]
      when(client.sendAndCheck(any[String], any[InputStream], any[Int])(using any[LoggingDetails], any[ExecutionContext]))
        .thenReturn(Future.successful(ScanningResult.Clean))

      val factory = mock[ClamAntiVirusFactory]
      when(factory.getClient())
        .thenReturn(client)

      val metrics         = metricsStub()
      val scanningService = ClamAvScanningService(factory, checksumInputStreamFactoryStub, metrics, clock)

      Given("a file location pointing to a clean file")
      val fileLocation = S3ObjectLocation("inboundBucket", "file", None)

      And("file content with metadata")
      val content      = "Hello World".getBytes
      val fileContent  = ObjectContent(ByteArrayInputStream(content), content.length)
      val lastModified = LocalDateTime.of(2018, 1, 27, 0, 0).toInstant(ZoneOffset.UTC)
      val fileMetadata = InboundObjectMetadata(Map("consuming-service" -> "ClamAvScanningServiceSpec"), lastModified, content.length)

      When("scanning service is called")
      val result = scanningService.scan(fileLocation, fileContent, fileMetadata).futureValue

      Then("a scanning clean result should be returned")
      result shouldBe Right(NoVirusFound("CHECKSUM", Timings(Instant.parse("2018-12-04T17:48:30Z"), Instant.parse("2018-12-04T17:48:31Z"))))

      And("the metrics should be successfully updated")
      metrics.defaultRegistry.counter("cleanFileUpload").getCount      shouldBe 1
      metrics.defaultRegistry.counter("quarantineFileUpload").getCount shouldBe 0
      metrics.defaultRegistry.timer("scanningTime").getSnapshot.size() shouldBe 1

    "return infected if file can be retrieved and scan result infected" in:
      val client = mock[ClamAntiVirus]
      when(client.sendAndCheck(any[String], any[InputStream], any[Int])(using any[LoggingDetails], any[ExecutionContext]))
        .thenReturn(Future.successful(ScanningResult.Infected("File dirty")))

      val factory = mock[ClamAntiVirusFactory]
      when(factory.getClient())
        .thenReturn(client)

      val metrics         = metricsStub()
      val scanningService = ClamAvScanningService(factory, checksumInputStreamFactoryStub, metrics, clock)

      Given("a file location pointing to a clean file")
      val fileLocation = S3ObjectLocation("inboundBucket", "file", None)

      And("file content with metadata")
      val content      = "Hello World".getBytes
      val fileContent  = ObjectContent(ByteArrayInputStream(content), content.length)
      val lastModified = LocalDateTime.of(2018, 1, 27, 0, 0).toInstant(ZoneOffset.UTC)
      val fileMetadata = InboundObjectMetadata(Map("consuming-service" -> "ClamAvScanningServiceSpec"), lastModified, content.length)

      When("scanning service is called")
      val result = Await.result(scanningService.scan(fileLocation, fileContent, fileMetadata), 2.seconds)

      Then("a scanning infected result should be returned")
      result shouldBe Left(FileInfected("File dirty", "CHECKSUM", Timings(Instant.parse("2018-12-04T17:48:30Z"), Instant.parse("2018-12-04T17:48:31Z"))))

      And("the metrics should be successfully updated")
      metrics.defaultRegistry.counter("cleanFileUpload").getCount      shouldBe 0
      metrics.defaultRegistry.counter("quarantineFileUpload").getCount shouldBe 1
      metrics.defaultRegistry.timer("scanningTime").getSnapshot.size() shouldBe 1
