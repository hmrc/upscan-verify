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

import play.api.Logging
import uk.gov.hmrc.clamav.ClamAntiVirusFactory
import uk.gov.hmrc.clamav.model.ScanningResult
import uk.gov.hmrc.http.logging.LoggingDetails
import uk.gov.hmrc.play.bootstrap.metrics.Metrics
import uk.gov.hmrc.upscanverify.model._
import uk.gov.hmrc.upscanverify.util.logging.WithLoggingDetails.withLoggingDetails

import java.time.{Clock, Instant}
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

trait ScanningService:
  def scan(
    location      : S3ObjectLocation,
    objectContent : ObjectContent,
    objectMetadata: InboundObjectMetadata
  )(using
    LoggingDetails
  ): Future[VirusScanResult]

class ClamAvScanningService @Inject()(
  clamClientFactory                       : ClamAntiVirusFactory,
  messageDigestComputingInputStreamFactory: ChecksumComputingInputStreamFactory,
  metrics                                 : Metrics,
  clock                                   : Clock
)(using
  ExecutionContext
) extends ScanningService
     with Logging:

  override def scan(
    location   : S3ObjectLocation,
    fileContent: ObjectContent,
    metadata   : InboundObjectMetadata
  )(using
    ld         : LoggingDetails
  ): Future[VirusScanResult] =

    val startTime       = clock.instant()
    val antivirusClient = clamClientFactory.getClient()

    val inputStream = messageDigestComputingInputStreamFactory.create(fileContent.inputStream)

    for
      scanResult <- antivirusClient
                      .sendAndCheck(location.objectKey, inputStream, fileContent.length.toInt)
                      .map:
                        case ScanningResult.Clean =>
                          metrics.defaultRegistry.counter("cleanFileUpload").inc()
                          VirusScanResult.NoVirusFound(inputStream.getChecksum(), Timings(startTime, clock.instant()))
                        case ScanningResult.Infected(message) =>
                          withLoggingDetails(ld):
                            logger.warn(s"File with Key=[${location.objectKey}] is infected: [$message].")
                          metrics.defaultRegistry.counter("quarantineFileUpload").inc()
                          VirusScanResult.FileInfected(message, inputStream.getChecksum(), Timings(startTime, clock.instant()))
    yield
      addScanningTimeMetrics(startTime, clock.instant())
      scanResult

  private def addScanningTimeMetrics(startTime: Instant, endTime: Instant): Unit =
    metrics.defaultRegistry
      .timer("scanningTime")
      .update(endTime.toEpochMilli - startTime.toEpochMilli, TimeUnit.MILLISECONDS)
