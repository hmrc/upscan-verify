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
import com.codahale.metrics.MetricRegistry
import play.api.Logging
import uk.gov.hmrc.upscanverify.config.ServiceConfiguration
import uk.gov.hmrc.upscanverify.model._
import uk.gov.hmrc.upscanverify.model.Timings.{Timer, timer}
import uk.gov.hmrc.upscanverify.service.tika.FileNameValidator

import java.io.InputStream
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class FileTypeCheckingService @Inject()(
  mimeTypeDetector    : MimeTypeDetector,
  fileNameValidator   : FileNameValidator,
  serviceConfiguration: ServiceConfiguration,
  metricRegistry      : MetricRegistry
)(using
  ExecutionContext,
  Clock
) extends Logging:

  def scan(
    location      : S3ObjectLocation,
    objectContent : InputStream,
    objectMetadata: InboundObjectMetadata
  ): Future[FileTypeCheckResult] =
    Future:
      given endTimer: Timer = timer()

      val consumingService = objectMetadata.consumingService
      val filename         = objectMetadata.originalFilename

      val mimeType         = mimeTypeDetector.detect(objectContent, filename)
      addCheckingTimeMetrics()
      logZeroLengthFiles(objectMetadata, location, consumingService)

      for
        _ <- validateMimeType(mimeType, consumingService, location)
        _ <- validateFileExtension(mimeType, consumingService, location, filename)
      yield
        metricRegistry.counter("validTypeFileUpload").inc()
        FileAllowed(mimeType, endTimer())

  private def logZeroLengthFiles(
    objectMetadata  : InboundObjectMetadata,
    location        : S3ObjectLocation,
    consumingService: Option[String]
  ): Unit =
    if objectMetadata.fileSize == 0 then
      logger.info(
        s"File with key=[${location.objectKey}] was uploaded with 0 bytes for consuming service [$consumingService]"
      )

  private def validateMimeType(
    mimeType        : MimeType,
    consumingService: Option[String],
    location        : S3ObjectLocation
  )(using
    timer           : Timer
  ): Either[FileTypeError, Unit] =
    val allowedMimeTypes =
      consumingService
        .flatMap(serviceConfiguration.allowedMimeTypes)
        .getOrElse(serviceConfiguration.defaultAllowedMimeTypes)

    if allowedMimeTypes.contains(mimeType.value) then
      Right(())
    else
      logger.warn(
        s"File with Key=[${location.objectKey}] is not allowed by [$consumingService] - service does not allow MIME type: [${mimeType.value}]"
      )
      metricRegistry.counter("invalidTypeFileUpload").inc()
      Left(FileTypeError.NotAllowedMimeType(mimeType, consumingService, timer()))

  private def validateFileExtension(
    mimeType        : MimeType,
    consumingService: Option[String],
    location        : S3ObjectLocation,
    maybeFilename   : Option[String]
  )(using
    timer           : Timer
  ): Either[FileTypeError, Unit] =
    maybeFilename
      .map: filename =>
        fileNameValidator
          .validate(mimeType, filename)
          .leftMap: extension =>
            logger.warn(
              s"File with extension=[$extension] is not allowed for MIME type=[$mimeType]. consumingService=[$consumingService], key=[${location.objectKey}]"
            )
            metricRegistry.counter("invalidTypeFileUpload").inc()
            FileTypeError.NotAllowedFileExtension(mimeType, extension, consumingService, timer())
      .getOrElse(Right(()))

  private def addCheckingTimeMetrics()(using timer: Timer): Unit =
    metricRegistry.timer("fileTypeCheckingTime").update(timer().difference, TimeUnit.MILLISECONDS)
