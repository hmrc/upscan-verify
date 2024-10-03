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

import cats.syntax.either._
import play.api.Logging
import uk.gov.hmrc.http.logging.LoggingDetails
import uk.gov.hmrc.play.bootstrap.metrics.Metrics
import uk.gov.hmrc.upscanverify.config.ServiceConfiguration
import uk.gov.hmrc.upscanverify.model._
import uk.gov.hmrc.upscanverify.model.Timings.{Timer, timer}
import uk.gov.hmrc.upscanverify.service.tika.FileNameValidator
import uk.gov.hmrc.upscanverify.util.logging.WithLoggingDetails.withLoggingDetails

import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class FileTypeCheckingService @Inject()(
  mimeTypeDetector    : MimeTypeDetector,
  fileNameValidator   : FileNameValidator,
  serviceConfiguration: ServiceConfiguration,
  metrics             : Metrics
)(using
  ExecutionContext,
  Clock
) extends Logging:

  def scan(
    location      : S3ObjectLocation,
    objectContent : ObjectContent,
    objectMetadata: InboundObjectMetadata
  )(using
    ld: LoggingDetails
  ): Future[FileTypeCheckResult] =
    Future:
      given endTimer: Timer = timer()

      val consumingService = objectMetadata.consumingService
      val filename         = objectMetadata.originalFilename

      val detectedMimeType = mimeTypeDetector.detect(objectContent.inputStream, filename)
      addCheckingTimeMetrics()
      logZeroLengthFiles(detectedMimeType, location, consumingService)

      for
        mimeType <- valiateNotCorrupt(detectedMimeType, consumingService, location)
        _        <- validateMimeType(mimeType, consumingService, location)
        _        <- validateFileExtension(mimeType, consumingService, location, filename)
      yield
        metrics.defaultRegistry.counter("validTypeFileUpload").inc()
        FileAllowed(mimeType, endTimer())


  private def logZeroLengthFiles(
    detectedMimeType: DetectedMimeType,
    location        : S3ObjectLocation,
    consumingService: Option[String]
  )(using
    ld              : LoggingDetails
  ): Unit =
    detectedMimeType match
      case _: DetectedMimeType.EmptyLength =>
        withLoggingDetails(ld):
          logger.info(
            s"File with key=[${location.objectKey}] was uploaded with 0 bytes for consuming service [$consumingService]"
          )
      case _ =>
        ()

  private def valiateNotCorrupt(
    detectedMimeType: DetectedMimeType,
    consumingService: Option[String],
    location        : S3ObjectLocation
  )(using
    ld   : LoggingDetails,
    timer: Timer
  ): Either[FileTypeError, MimeType] =
    detectedMimeType match
      case DetectedMimeType.Detected(mimeType)    => Right(mimeType)
      case DetectedMimeType.EmptyLength(mimeType) => Right(mimeType)
      case DetectedMimeType.Failed(message)       =>
        // TODO do we need these warns? The RejectionNotifier will also log them
        withLoggingDetails(ld):
          logger.warn(
            s"File with Key=[${location.objectKey}] could not be scanned. consuming service [$consumingService]: $message"
          )
        metrics.defaultRegistry.counter("invalidTypeFileUpload").inc()
        Left(FileTypeError.Corrupt(consumingService, timer()))

  private def validateMimeType(
    mimeType        : MimeType,
    consumingService: Option[String],
    location        : S3ObjectLocation
  )(using
    ld              : LoggingDetails,
    timer           : Timer
  ): Either[FileTypeError, Unit] =
    val allowedMimeTypes =
      consumingService
        .flatMap(serviceConfiguration.allowedMimeTypes)
        .getOrElse(serviceConfiguration.defaultAllowedMimeTypes)

    if allowedMimeTypes.contains(mimeType.value) then
      Right(())
    else
      withLoggingDetails(ld):
        logger.warn(
          s"File with Key=[${location.objectKey}] is not allowed by [$consumingService] - service does not allow MIME type: [${mimeType.value}]"
        )
      metrics.defaultRegistry.counter("invalidTypeFileUpload").inc()
      Left(FileTypeError.NotAllowedMimeType(mimeType, consumingService, timer()))

  private def validateFileExtension(
    mimeType        : MimeType,
    consumingService: Option[String],
    location        : S3ObjectLocation,
    maybeFilename   : Option[String]
  )(using
    ld   : LoggingDetails,
    timer: Timer
  ): Either[FileTypeError, Unit] =
    maybeFilename
      .map: filename =>
        fileNameValidator
          .validate(mimeType, filename)
          .leftMap: extension =>
            withLoggingDetails(ld):
              logger.warn(
                s"File with extension=[$extension] is not allowed for MIME type=[$mimeType]. consumingService=[$consumingService], key=[${location.objectKey}]"
              )
            metrics.defaultRegistry.counter("invalidTypeFileUpload").inc()
            FileTypeError.NotAllowedFileExtension(mimeType, extension, consumingService, timer())
      .getOrElse(Right(()))

  private def addCheckingTimeMetrics()(using timer: Timer): Unit =
    metrics.defaultRegistry.timer("fileTypeCheckingTime").update(timer().difference, TimeUnit.MILLISECONDS)
