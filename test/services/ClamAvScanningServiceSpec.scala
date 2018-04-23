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
import java.time.{LocalDateTime, ZoneOffset}
import java.util.{Calendar, GregorianCalendar}

import com.codahale.metrics.MetricRegistry
import com.kenshoo.play.metrics.Metrics
import model.S3ObjectLocation
import org.mockito.Mockito
import org.scalatest.{Assertions, GivenWhenThen, Matchers}
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.clamav.{ClamAntiVirus, ClamAntiVirusFactory}
import uk.gov.hmrc.play.test.UnitSpec
import org.mockito.ArgumentMatchers.any
import org.scalatest.concurrent.ScalaFutures
import uk.gov.hmrc.clamav.model.{Clean, Infected}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class ClamAvScanningServiceSpec extends UnitSpec with Matchers with Assertions with GivenWhenThen with MockitoSugar {

  "ClamAvScanningService" should {

    val fileManager = new FileManager {
      override def delete(file: S3ObjectLocation): Future[Unit] = ???

      override def copyToOutboundBucket(file: S3ObjectLocation): Future[Unit] = ???

      override def getObjectContent(file: S3ObjectLocation): Future[ObjectContent] = file.objectKey match {
        case "bad-file" => Future.failed(new RuntimeException("File not retrieved"))
        case _ =>
          val content = "Hello World".getBytes
          Future.successful(ObjectContent(new ByteArrayInputStream(content), content.length))
      }

      override def writeToQuarantineBucket(
        file: S3ObjectLocation,
        content: InputStream,
        metadata: ObjectMetadata): Future[Unit] = ???

      override def getObjectMetadata(file: S3ObjectLocation): Future[ObjectMetadata] = {
        val lastModified = LocalDateTime.of(2018, 1, 27, 0, 0).toInstant(ZoneOffset.UTC)
        Future(ObjectMetadata(Map.empty, lastModified))
      }
    }

    def metricsStub() = new Metrics {
      override val defaultRegistry: MetricRegistry = new MetricRegistry

      override def toJson: String = ???
    }

    "return success if file can be retrieved and scan result clean" in {
      val client = mock[ClamAntiVirus]
      Mockito.when(client.sendAndCheck(any(), any())(any())).thenReturn(Future.successful(Clean))

      val factory = mock[ClamAntiVirusFactory]
      Mockito.when(factory.getClient()).thenReturn(client)

      val metrics         = metricsStub()
      val scanningService = new ClamAvScanningService(factory, fileManager, metrics)

      Given("a file location pointing to a clean file")
      val fileLocation = S3ObjectLocation("inboundBucket", "file")

      When("scanning service is called")
      val result = Await.result(scanningService.scan(fileLocation), 2.seconds)

      Then("a scanning clean result should be returned")
      result shouldBe FileIsClean(fileLocation)

      And("the metrics should be successfully updated")
      metrics.defaultRegistry.counter("cleanFileUpload").getCount              shouldBe 1
      metrics.defaultRegistry.counter("quarantineFileUpload").getCount         shouldBe 0
      metrics.defaultRegistry.timer("uploadToScanComplete").getSnapshot.size() shouldBe 1
      metrics.defaultRegistry.timer("scanningTime").getSnapshot.size()         shouldBe 1
    }

    "return infected if file can be retrieved and scan result infected" in {
      val client = mock[ClamAntiVirus]
      Mockito.when(client.sendAndCheck(any(), any())(any())).thenReturn(Future.successful(Infected("File dirty")))

      val factory = mock[ClamAntiVirusFactory]
      Mockito.when(factory.getClient()).thenReturn(client)

      val metrics         = metricsStub()
      val scanningService = new ClamAvScanningService(factory, fileManager, metrics)

      Given("a file location pointing to a clean file")
      val fileLocation = S3ObjectLocation("inboundBucket", "file")

      When("scanning service is called")
      val result = Await.result(scanningService.scan(fileLocation), 2.seconds)

      Then("a scanning infected result should be returned")
      result shouldBe FileIsInfected(fileLocation, "File dirty")

      And("the metrics should be successfully updated")
      metrics.defaultRegistry.counter("cleanFileUpload").getCount              shouldBe 0
      metrics.defaultRegistry.counter("quarantineFileUpload").getCount         shouldBe 1
      metrics.defaultRegistry.timer("uploadToScanComplete").getSnapshot.size() shouldBe 1
      metrics.defaultRegistry.timer("scanningTime").getSnapshot.size()         shouldBe 1
    }

    "return failure if file cannot be retrieved" in {
      val client = mock[ClamAntiVirus]

      val factory = mock[ClamAntiVirusFactory]
      Mockito.when(factory.getClient()).thenReturn(client)

      val metrics         = metricsStub()
      val scanningService = new ClamAvScanningService(factory, fileManager, metrics)

      Given("a file location that cannot be retrieved from the file manager")
      val fileLocation = S3ObjectLocation("inboundBucket", "bad-file")

      When("scanning service is called")
      val result = Await.ready(scanningService.scan(fileLocation), 2.seconds)

      Then("error is returned")
      ScalaFutures.whenReady(result.failed) { error =>
        error            shouldBe a[RuntimeException]
        error.getMessage shouldBe "File not retrieved"

        And("the metrics should NOT be updated")
        metrics.defaultRegistry.counter("cleanFileUpload").getCount              shouldBe 0
        metrics.defaultRegistry.counter("quarantineFileUpload").getCount         shouldBe 0
        metrics.defaultRegistry.timer("uploadToScanComplete").getSnapshot.size() shouldBe 0
        metrics.defaultRegistry.timer("scanningTime").getSnapshot.size()         shouldBe 0
      }
    }
  }
}
