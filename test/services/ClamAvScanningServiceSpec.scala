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

import com.codahale.metrics.MetricRegistry
import com.kenshoo.play.metrics.Metrics
import model.{InvalidFileCheckingResult, S3ObjectLocation, ValidFileCheckingResult}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{Assertions, GivenWhenThen, Matchers}
import uk.gov.hmrc.clamav.model.{Clean, Infected}
import uk.gov.hmrc.clamav.{ClamAntiVirus, ClamAntiVirusFactory}
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class ClamAvScanningServiceSpec extends UnitSpec with Matchers with Assertions with GivenWhenThen with MockitoSugar {

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
      Future.successful(ObjectMetadata(Map("consuming-service" -> "ClamAvScanningServiceSpec"), lastModified))
    }
  }

  "ClamAvScanningService" should {

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

      And("file content with metadata")
      val (fileContent, fileMetadata) = fetchObject(fileLocation)

      When("scanning service is called")
      val result = Await.result(scanningService.scan(fileLocation, fileContent, fileMetadata), 2.seconds)

      Then("a scanning clean result should be returned")
      result shouldBe ValidFileCheckingResult(fileLocation)

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

      And("file content with metadata")
      val (fileContent, fileMetadata) = fetchObject(fileLocation)

      When("scanning service is called")
      val result = Await.result(scanningService.scan(fileLocation, fileContent, fileMetadata), 2.seconds)

      Then("a scanning infected result should be returned")
      result shouldBe InvalidFileCheckingResult(fileLocation, "File dirty")

      And("the metrics should be successfully updated")
      metrics.defaultRegistry.counter("cleanFileUpload").getCount              shouldBe 0
      metrics.defaultRegistry.counter("quarantineFileUpload").getCount         shouldBe 1
      metrics.defaultRegistry.timer("uploadToScanComplete").getSnapshot.size() shouldBe 1
      metrics.defaultRegistry.timer("scanningTime").getSnapshot.size()         shouldBe 1
    }
  }

  private def fetchObject(fileLocation: S3ObjectLocation): (ObjectContent, ObjectMetadata) =
    for {
      content  <- fileManager.getObjectContent(fileLocation)
      metadata <- fileManager.getObjectMetadata(fileLocation)
    } yield (content, metadata)
}
