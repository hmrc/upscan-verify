/*
 * Copyright 2019 HM Revenue & Customs
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

import java.time.{Clock, Instant}
import java.util.concurrent.TimeUnit

import com.kenshoo.play.metrics.Metrics
import javax.inject.Inject
import model._
import play.api.Logger
import uk.gov.hmrc.clamav.ClamAntiVirusFactory
import uk.gov.hmrc.clamav.model.{Clean, Infected}
import uk.gov.hmrc.http.logging.LoggingDetails
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext.fromLoggingDetails

import scala.concurrent.Future

case class NoVirusFound(checksum: String, virusScanTimings: Timings)

trait ScanningService {
  def scan(location: S3ObjectLocation, objectContent: ObjectContent, objectMetadata: InboundObjectMetadata)(
    implicit ld: LoggingDetails): Future[Either[FileValidationFailure, NoVirusFound]]
}

class ClamAvScanningService @Inject()(
  clamClientFactory: ClamAntiVirusFactory,
  messageDigestComputingInputStreamFactory: ChecksumComputingInputStreamFactory,
  metrics: Metrics,
  clock: Clock)
    extends ScanningService {

  override def scan(location: S3ObjectLocation, fileContent: ObjectContent, metadata: InboundObjectMetadata)(
    implicit ld: LoggingDetails): Future[Either[FileInfected, NoVirusFound]] = {

    val startTime       = clock.instant()
    val antivirusClient = clamClientFactory.getClient()

    val inputStream = messageDigestComputingInputStreamFactory.create(fileContent.inputStream)

    for {
      scanResult <- antivirusClient.sendAndCheck(inputStream, fileContent.length.toInt).map {
                     case Clean =>
                       metrics.defaultRegistry.counter("cleanFileUpload").inc()
                       Right(NoVirusFound(inputStream.getChecksum(), Timings(startTime, clock.instant())))
                     case Infected(message) =>
                       Logger.warn(s"File is infected: [$message].")
                       metrics.defaultRegistry.counter("quarantineFileUpload").inc()
                       Left(FileInfected(message, inputStream.getChecksum(), Timings(startTime, clock.instant())))
                   }
    } yield {
      addScanningTimeMetrics(startTime, clock.instant())
      scanResult
    }
  }

  private def addScanningTimeMetrics(startTime: Instant, endTime: Instant): Unit = {
    metrics.defaultRegistry
      .timer("scanningTime")
      .update(endTime.toEpochMilli() - startTime.toEpochMilli(), TimeUnit.MILLISECONDS)
  }
}
