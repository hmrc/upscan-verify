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

import java.time.{Clock, Instant}
import java.util.concurrent.TimeUnit

import com.kenshoo.play.metrics.Metrics
import config.ServiceConfiguration
import javax.inject.Inject
import model._
import play.api.Logger
import uk.gov.hmrc.http.logging.LoggingDetails
import util.logging.LoggingDetails
import util.logging.WithLoggingDetails.withLoggingDetails

import scala.concurrent.{ExecutionContext, Future}

case class FileAllowed(mimeType: MimeType, fileTypeTimings: Timings)

class FileTypeCheckingService @Inject()(
  fileTypeDetector: FileTypeDetector,
  serviceConfiguration: ServiceConfiguration,
  metrics: Metrics,
  clock: Clock)(implicit ec: ExecutionContext) {

  def scan(location: S3ObjectLocation, objectContent: ObjectContent, objectMetadata: InboundObjectMetadata)(
    implicit ld: LoggingDetails): Future[Either[FileValidationFailure, FileAllowed]] = {

    val startTime = clock.instant()

    val consumingService = objectMetadata.items.get("consuming-service")
    val fileType         = fileTypeDetector.detectType(objectContent.inputStream, objectMetadata.originalFilename)

    addCheckingTimeMetrics(startTime)

    if (isAllowedMimeType(fileType, location, consumingService)) {
      metrics.defaultRegistry.counter("validTypeFileUpload").inc()
      Future.successful(Right(FileAllowed(fileType, Timings(startTime, clock.instant()))))
    } else {
      metrics.defaultRegistry.counter("invalidTypeFileUpload").inc()
      Future.successful(Left(IncorrectFileType(fileType, consumingService, Timings(startTime, clock.instant()))))
    }
  }

  private def isAllowedMimeType(mimeType: MimeType, location: S3ObjectLocation, consumingService: Option[String])(
    implicit ld: LoggingDetails): Boolean =
    consumingService match {
      case Some(service) => isAllowedForService(mimeType, service)
      case None =>
        withLoggingDetails(ld) {
          Logger.error(s"No x-amz-meta-consuming-service metadata for [${location.objectKey}]")
        }
        false
    }

  private def isAllowedForService(mimeType: MimeType, consumingService: String)(
    implicit ld: LoggingDetails): Boolean = {
    val allowedMimeTypes = serviceConfiguration.consumingServicesConfiguration.allowedMimeTypes(consumingService)
    if (allowedMimeTypes.contains(mimeType.value)) true
    else {
      withLoggingDetails(ld) {
        Logger.error(s"Consuming service [$consumingService] does not allow MIME type: [${mimeType.value}]")
      }
      false
    }
  }

  private def addCheckingTimeMetrics(startTime: Instant) = {
    val interval = clock.instant().toEpochMilli() - startTime.toEpochMilli()
    metrics.defaultRegistry.timer("fileTypeCheckingTime").update(interval, TimeUnit.MILLISECONDS)
  }
}
