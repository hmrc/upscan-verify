/*
 * Copyright 2021 HM Revenue & Customs
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

import cats.syntax.either._
import com.kenshoo.play.metrics.Metrics
import config.ServiceConfiguration
import model._
import play.api.Logging
import services.MimeTypeDetector.MimeTypeDetectionError.NotAllowedFileExtension
import uk.gov.hmrc.http.logging.LoggingDetails
import util.logging.WithLoggingDetails.withLoggingDetails

import java.time.{Clock, Instant}
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import scala.concurrent.Future

case class FileAllowed(mimeType: MimeType, fileTypeTimings: Timings)

class FileTypeCheckingService @Inject()(
  mimeTypeDetector: MimeTypeDetector,
  serviceConfiguration: ServiceConfiguration,
  metrics: Metrics,
  clock: Clock)
    extends Logging {

  def scan(location: S3ObjectLocation, objectContent: ObjectContent, objectMetadata: InboundObjectMetadata)(
    implicit ld: LoggingDetails): Future[Either[IncorrectFileType, FileAllowed]] = {

    val startTime = clock.instant()

    val consumingService = objectMetadata.consumingService
    val mimeType = mimeTypeDetector
      .detect(objectContent.inputStream, objectMetadata.originalFilename)
      .leftMap {
        case NotAllowedFileExtension(detectedMimeType, fileExtension) =>
          logger.warn(
            s"A file with $fileExtension was rejected. detectedMimeType=$detectedMimeType, consumingService=${objectMetadata.consumingService}, objectLocation=${location.bucket}/${location.objectKey}")
          MimeType.octetStream
      }
      .merge

    addCheckingTimeMetrics(startTime)

    if (isAllowedForService(mimeType, consumingService, location)) {
      metrics.defaultRegistry.counter("validTypeFileUpload").inc()
      Future.successful(Right(FileAllowed(mimeType, Timings(startTime, clock.instant()))))
    } else {
      metrics.defaultRegistry.counter("invalidTypeFileUpload").inc()
      Future.successful(Left(IncorrectFileType(mimeType, consumingService, Timings(startTime, clock.instant()))))
    }
  }

  private def isAllowedForService(mimeType: MimeType, consumingService: Option[String], location: S3ObjectLocation)(
    implicit ld: LoggingDetails): Boolean = {

    val allowedMimeTypes =
      consumingService
        .flatMap(serviceConfiguration.allowedMimeTypes)
        .getOrElse(serviceConfiguration.defaultAllowedMimeTypes)

    if (allowedMimeTypes.contains(mimeType.value)) true
    else {
      withLoggingDetails(ld) {
        logger.warn(
          s"File with Key=[${location.objectKey}] is not allowed by [$consumingService] - service does not allow MIME type: [${mimeType.value}]")
      }
      false
    }
  }

  private def addCheckingTimeMetrics(startTime: Instant) {
    val interval = clock.instant().toEpochMilli - startTime.toEpochMilli
    metrics.defaultRegistry.timer("fileTypeCheckingTime").update(interval, TimeUnit.MILLISECONDS)
  }
}
