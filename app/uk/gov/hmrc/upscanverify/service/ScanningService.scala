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
import com.codahale.metrics.MetricRegistry
import uk.gov.hmrc.upscanverify.model._

import java.io.InputStream
import java.time.{Clock, Instant}
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

trait ScanningService:
  def scan(
    location      : S3ObjectLocation,
    content       : InputStream,
    objectMetadata: InboundObjectMetadata
  ): Future[VirusScanResult]

class ClamAvScanningService @Inject()(
  clamClientFactory                       : ClamAntiVirusFactory,
  messageDigestComputingInputStreamFactory: ChecksumComputingInputStreamFactory,
  metricRegistry                          : MetricRegistry,
  clock                                   : Clock
)(using
  ExecutionContext
) extends ScanningService
     with Logging:

  override def scan(
    location   : S3ObjectLocation,
    content    : InputStream,
    metadata   : InboundObjectMetadata
  ): Future[VirusScanResult] =

    val startTime       = clock.instant()
    val antivirusClient = clamClientFactory.getClient()

    val inputStream = messageDigestComputingInputStreamFactory.create(content)

    for
      scanResult <- antivirusClient
                      .sendAndCheck(location.objectKey, inputStream, metadata.fileSize.toInt) // TODO just use Long
                      .map:
                        case ScanningResult.Clean =>
                          metricRegistry.counter("cleanFileUpload").inc()
                          Right(VirusScanResult.NoVirusFound(inputStream.getChecksum(), Timings(startTime, clock.instant())))
                        case ScanningResult.Infected(message) =>
                          logger.warn(s"File with Key=[${location.objectKey}] is infected: [$message].")
                          metricRegistry.counter("quarantineFileUpload").inc()
                          Left(VirusScanResult.FileInfected(message, inputStream.getChecksum(), Timings(startTime, clock.instant())))
    yield
      addScanningTimeMetrics(startTime, clock.instant())
      scanResult

  private def addScanningTimeMetrics(startTime: Instant, endTime: Instant): Unit =
    metricRegistry
      .timer("scanningTime")
      .update(endTime.toEpochMilli - startTime.toEpochMilli, TimeUnit.MILLISECONDS)
