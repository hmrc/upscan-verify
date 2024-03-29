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

package services

import cats.syntax.either._
import config.ServiceConfiguration
import model.FileTypeError.{NotAllowedFileExtension, NotAllowedMimeType}
import model.Timings.{Timer, timer}
import model._
import play.api.Logging
import services.tika.FileNameValidator
import uk.gov.hmrc.http.logging.LoggingDetails
import uk.gov.hmrc.play.bootstrap.metrics.Metrics
import util.logging.WithLoggingDetails.withLoggingDetails

import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import scala.concurrent.Future

case class FileAllowed(mimeType: MimeType, fileTypeTimings: Timings)

class FileTypeCheckingService @Inject()(
  mimeTypeDetector: MimeTypeDetector,
  fileNameValidator: FileNameValidator,
  serviceConfiguration: ServiceConfiguration,
  metrics: Metrics)(implicit clock: Clock)
    extends Logging {

  def scan(location: S3ObjectLocation, objectContent: ObjectContent, objectMetadata: InboundObjectMetadata)(
    implicit ld: LoggingDetails): Future[Either[FileTypeError, FileAllowed]] = {

    implicit val endTimer: Timer = timer()

    val consumingService = objectMetadata.consumingService
    val maybeFilename    = objectMetadata.originalFilename
    val detectedMimeType = mimeTypeDetector.detect(objectContent.inputStream, maybeFilename)

    addCheckingTimeMetrics(endTimer)
    logZeroLengthFiles(detectedMimeType, location, consumingService)

    val mimeType = detectedMimeType.value

    val result = for {
      _ <- validateMimeType(mimeType, consumingService, location)
      _ <- validateFileExtension(mimeType, consumingService, location, maybeFilename)
    } yield {
      metrics.defaultRegistry.counter("validTypeFileUpload").inc()
      FileAllowed(mimeType, endTimer())
    }
    Future.successful(result)
  }

  private def logZeroLengthFiles(detectedMimeType: DetectedMimeType, location: S3ObjectLocation, consumingService: Option[String])(
    implicit ld: LoggingDetails): Unit = {
    detectedMimeType match {
      case _ :DetectedMimeType.EmptyLength =>
        withLoggingDetails(ld) {
          logger.info(
            s"File with key=[${location.objectKey}] was uploaded with 0 bytes for consuming service [$consumingService]"
          )
        }
      case _ => ()
    }
  }

  private def validateMimeType(mimeType: MimeType, consumingService: Option[String], location: S3ObjectLocation)(
    implicit ld: LoggingDetails,
    timer: Timer): Either[FileTypeError, Unit] = {
    val allowedMimeTypes =
      consumingService
        .flatMap(serviceConfiguration.allowedMimeTypes)
        .getOrElse(serviceConfiguration.defaultAllowedMimeTypes)

    if (allowedMimeTypes.contains(mimeType.value)) Right(())
    else {
      withLoggingDetails(ld) {
        logger.warn(
          s"File with Key=[${location.objectKey}] is not allowed by [$consumingService] - service does not allow MIME type: [${mimeType.value}]")
      }
      metrics.defaultRegistry.counter("invalidTypeFileUpload").inc()
      Left(NotAllowedMimeType(mimeType, consumingService, timer()))
    }
  }

  private def validateFileExtension(
    mimeType: MimeType,
    consumingService: Option[String],
    location: S3ObjectLocation,
    maybeFilename: Option[String])(implicit ld: LoggingDetails, timer: Timer): Either[FileTypeError, Unit] =
    maybeFilename
      .map { filename =>
        fileNameValidator
          .validate(mimeType, filename)
          .leftMap { extension =>
            withLoggingDetails(ld) {
              logger.warn(
                s"File with extension=[$extension] is not allowed for MIME type=[$mimeType]. consumingService=[$consumingService], key=[${location.objectKey}]")
            }
            metrics.defaultRegistry.counter("invalidTypeFileUpload").inc()
            NotAllowedFileExtension(mimeType, extension, consumingService, timer())
          }
      }
      .getOrElse(Right(()))

  private def addCheckingTimeMetrics(implicit timer: Timer): Unit =
    metrics.defaultRegistry.timer("fileTypeCheckingTime").update(timer().difference, TimeUnit.MILLISECONDS)

}
