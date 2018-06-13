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

import java.util.concurrent.TimeUnit

import com.kenshoo.play.metrics.Metrics
import javax.inject.Inject
import model._
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

class ClamAvScanningService @Inject()(
  clamClientFactory: ClamAntiVirusFactory,
  messageDigestComputingInputStreamFactory: ChecksumComputingInputStreamFactory,
  metrics: Metrics)
    extends ScanningService {

  override def scan(
    location: S3ObjectLocation,
    fileContent: ObjectContent,
    metadata: InboundObjectMetadata): Future[FileCheckingResultWithChecksum] = {
    implicit val ld = LoggingDetails.fromS3ObjectLocation(location)

    val antivirusClient       = clamClientFactory.getClient()
    val startTimeMilliseconds = System.currentTimeMillis()

    val inputStream = messageDigestComputingInputStreamFactory.create(fileContent.inputStream)

    for {
      scanResult <- antivirusClient.sendAndCheck(inputStream, fileContent.length.toInt) map {
                     case Clean =>
                       metrics.defaultRegistry.counter("cleanFileUpload").inc()
                       ValidFileCheckingResult(location)
                     case Infected(message) =>
                       Logger.warn(s"File is infected: [$message].")
                       metrics.defaultRegistry.counter("quarantineFileUpload").inc()
                       FileInfectedCheckingResult(location, message)
                   }

    } yield {
      val endTimeMilliseconds = System.currentTimeMillis()
      addScanningTimeMetrics(startTimeMilliseconds, endTimeMilliseconds)
      FileCheckingResultWithChecksum(scanResult, inputStream.getChecksum())
    }
  }

  private def addScanningTimeMetrics(startTimeMilliseconds: Long, endTimeMilliseconds: Long) = {
    val interval = endTimeMilliseconds - startTimeMilliseconds
    metrics.defaultRegistry.timer("scanningTime").update(interval, TimeUnit.MILLISECONDS)
  }
}
