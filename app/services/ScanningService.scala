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

import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.{CheckedInputStream, Checksum}

import com.kenshoo.play.metrics.Metrics
import javax.inject.Inject
import model._
import org.apache.commons.codec.binary.Hex
import play.api.Logger
import uk.gov.hmrc.clamav.ClamAntiVirusFactory
import uk.gov.hmrc.clamav.model.{Clean, Infected}
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext.fromLoggingDetails
import util.logging.LoggingDetails

import scala.concurrent.Future

trait ScanningService {
  def scan(
    location: S3ObjectLocation,
    objectContent: ObjectContent,
    objectMetadata: InboundObjectMetadata): Future[FileCheckingResultWithChecksum]
}

class ClamAvScanningService @Inject()(clamClientFactory: ClamAntiVirusFactory, metrics: Metrics)
    extends ScanningService {

  override def scan(
    location: S3ObjectLocation,
    fileContent: ObjectContent,
    metadata: InboundObjectMetadata): Future[FileCheckingResultWithChecksum] = {
    implicit val ld = LoggingDetails.fromS3ObjectLocation(location)

    val antivirusClient       = clamClientFactory.getClient()
    val startTimeMilliseconds = System.currentTimeMillis()

    val checksum = new Checksum {

      private val digest = MessageDigest.getInstance("SHA-256")

      override def update(i: Int): Unit =
        digest.update(i.toByte)

      override def update(bytes: Array[Byte], i: Int, i1: Int): Unit =
        digest.update(bytes, i, i1)

      override def getValue: Long = ???

      override def reset(): Unit =
        digest.reset()

      def getChecksum() = Hex.encodeHexString(digest.digest())

    }
    val inputStream = new CheckedInputStream(fileContent.inputStream, checksum)

    for {
      scanResult <- antivirusClient.sendAndCheck(inputStream, fileContent.length.toInt) map {
                     case result if result == Clean =>
                       metrics.defaultRegistry.counter("cleanFileUpload").inc()
                       FileCheckingResultWithChecksum(ValidFileCheckingResult(location), checksum.getChecksum())
                     case result @ Infected(message) =>
                       Logger.warn(s"File is infected: [$message].")
                       metrics.defaultRegistry.counter("quarantineFileUpload").inc()
                       FileCheckingResultWithChecksum(
                         FileInfectedCheckingResult(location, message),
                         checksum.getChecksum())
                   }

    } yield {
      val endTimeMilliseconds = System.currentTimeMillis()
      addScanningTimeMetrics(startTimeMilliseconds, endTimeMilliseconds)
      scanResult
    }
  }

  private def addScanningTimeMetrics(startTimeMilliseconds: Long, endTimeMilliseconds: Long) = {
    val interval = endTimeMilliseconds - startTimeMilliseconds
    metrics.defaultRegistry.timer("scanningTime").update(interval, TimeUnit.MILLISECONDS)
  }
}
