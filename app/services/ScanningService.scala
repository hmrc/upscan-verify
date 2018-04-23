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

import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import com.kenshoo.play.metrics.Metrics
import model.S3ObjectLocation
import play.api.Logger
import uk.gov.hmrc.clamav._
import uk.gov.hmrc.clamav.model.{Clean, Infected}
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext.fromLoggingDetails
import util.logging.LoggingDetails

import scala.concurrent.Future

sealed trait ScanningResult extends Product with Serializable {
  def location: S3ObjectLocation
}

case class FileIsClean(location: S3ObjectLocation) extends ScanningResult

case class FileIsInfected(location: S3ObjectLocation, details: String) extends ScanningResult

trait ScanningService {
  def scan(location: S3ObjectLocation): Future[ScanningResult]
}

class ClamAvScanningService @Inject()(
  clamClientFactory: ClamAntiVirusFactory,
  fileManager: FileManager,
  metrics: Metrics)
    extends ScanningService {

  override def scan(location: S3ObjectLocation): Future[ScanningResult] = {
    implicit val ld = LoggingDetails.fromS3ObjectLocation(location)

    for {
      metadata    <- fileManager.getObjectMetadata(location)
      fileContent <- fileManager.getObjectContent(location)
      antivirusClient       = clamClientFactory.getClient()
      startTimeMilliseconds = System.currentTimeMillis()
      scanResult <- antivirusClient.sendAndCheck(fileContent.inputStream, fileContent.length.toInt) map {
                     case Clean =>
                       val clean = FileIsClean(location)
                       Logger.debug(s"File is clean: [$clean].")
                       metrics.defaultRegistry.counter("cleanFileUpload").inc()
                       clean
                     case Infected(message) =>
                       val infected = FileIsInfected(location, message)
                       Logger.warn(s"File is infected: [$infected].")
                       metrics.defaultRegistry.counter("quarantineFileUpload").inc()
                       infected
                   }

    } yield {
      val endTimeMilliseconds = System.currentTimeMillis()
      addUploadToEndScanMetrics(metadata.uploadedTimestamp, endTimeMilliseconds)
      addScanningTimeMetrics(startTimeMilliseconds, endTimeMilliseconds)
      scanResult
    }
  }

  private def addUploadToEndScanMetrics(uploadedTimestamp: Instant, endTimeMilliseconds: Long) = {
    val interval = endTimeMilliseconds - uploadedTimestamp.toEpochMilli
    metrics.defaultRegistry.timer("uploadToScanComplete").update(interval, TimeUnit.MILLISECONDS)
  }

  private def addScanningTimeMetrics(startTimeMilliseconds: Long, endTimeMilliseconds: Long) = {
    val interval = endTimeMilliseconds - startTimeMilliseconds
    metrics.defaultRegistry.timer("scanningTime").update(interval, TimeUnit.MILLISECONDS)
  }
}
