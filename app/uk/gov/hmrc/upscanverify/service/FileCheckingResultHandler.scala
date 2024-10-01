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
import play.api.libs.json.Json
import uk.gov.hmrc.http.logging.LoggingDetails
import uk.gov.hmrc.upscanverify.config.ServiceConfiguration
import uk.gov.hmrc.upscanverify.model._
import uk.gov.hmrc.upscanverify.util.logging.WithLoggingDetails.withLoggingDetails

import java.io.ByteArrayInputStream
import java.time.{Clock, Instant}
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class FileCheckingResultHandler @Inject()(
  fileManager      : FileManager,
  rejectionNotifier: RejectionNotifier,
  configuration    : ServiceConfiguration,
  clock            : Clock
)(using
  ExecutionContext
) extends Logging:

  def handleCheckingResult(
    objectDetails    : InboundObjectDetails,
    result           : Either[FileRejected, FileValidationSuccess],
    messageReceivedAt: Instant
  )(using ld: LoggingDetails): Future[Unit] =
    withLoggingDetails(ld):
      logger.info(s"Handling check result for Key=[${objectDetails.location.objectKey}] Result=[$result]")
    result match
      case Right(FileValidationSuccess(checksum, mimeType, virusScanTimings, fileTypeTimings)) =>
        handleValid(objectDetails, checksum, mimeType)(messageReceivedAt, virusScanTimings, fileTypeTimings)

      case Left(FileRejected(Left(FileInfected(errorMessage, checksum, virusScanTimings)), None)) =>
        handleInfected(objectDetails, errorMessage)(checksum, messageReceivedAt, virusScanTimings)

      case Left(FileRejected(Right(NoVirusFound(checksum, virusScanTimings)), Some(FileTypeError.NotAllowedMimeType(mime, consumingService, fileTypeTimings)))) =>
        val errorMessage =
          s"MIME type [${mime.value}] is not allowed for service: [${consumingService.getOrElse("No service name provided")}]"
        handleFileTypeError(objectDetails, errorMessage, consumingService)(checksum, messageReceivedAt, virusScanTimings, fileTypeTimings)

      case Left(FileRejected(Right(NoVirusFound(checksum, virusScanTimings)), Some(FileTypeError.NotAllowedFileExtension(mime, extension, consumingService, fileTypeTimings)))) =>
        val errorMessage =
          s"File extension [$extension] is not allowed for mime-type [${mime.value}], service: [${consumingService.getOrElse("No service name provided")}]"
        handleFileTypeError(objectDetails, errorMessage, consumingService)(checksum, messageReceivedAt, virusScanTimings, fileTypeTimings)

      case _ =>
        Future.successful:
          withLoggingDetails(ld):
            logger.error(s"Unexpected match result for Key=[${objectDetails.location.objectKey}] Result=[$result]")

  private def handleValid(
    details: InboundObjectDetails,
    checksum: String,
    mimeType: MimeType
  )(
    messageReceivedAt: Instant,
    virusScanTimings: Timings,
    fileTypeTimings: Timings
  )(using
    ExecutionContext,
    LoggingDetails
  ): Future[Unit] =

    def metadata(key: String): Option[(String, String)] =
      details.metadata.items.get(key).map(s"x-amz-meta-${key}" -> _)

    val targetLocation =
      S3ObjectLocation(configuration.outboundBucket, UUID.randomUUID().toString, objectVersion = None)

    for
      _ <- fileManager
            .copyObject(
              details.location,
              targetLocation,
              OutboundObjectMetadata.valid(
                details,
                checksum,
                mimeType,
                timingsMetadata("x-amz-meta-upscan-verify-outbound-queued", messageReceivedAt, virusScanTimings, Some(fileTypeTimings))
                  ++ metadata("upscan-initiate-received")
                  ++ metadata("upscan-initiate-response")
              )
            )
      _ <- fileManager.delete(details.location)
    yield ()

  private def handleInfected(
    details          : InboundObjectDetails,
    errorMessage     : String
  )(
    checksum         : String,
    messageReceivedAt: Instant,
    virusScanTimings : Timings
  )(using
    ExecutionContext,
    LoggingDetails
  ): Future[Unit] =

    def metadata(key: String): Option[(String, String)] =
      details.metadata.items.get(key).map(s"x-amz-meta-${key}" -> _)

    val fileCheckingError = ErrorMessage(FileCheckingError.Quarantine, errorMessage)
    val objectContent     = ByteArrayInputStream(Json.toJson(fileCheckingError).toString.getBytes)
    val targetLocation    =
      S3ObjectLocation(configuration.quarantineBucket, UUID.randomUUID().toString, objectVersion = None)

    for
      _ <- rejectionNotifier.notifyFileInfected(details.location, checksum, details.metadata.fileSize, details.metadata.uploadedTimestamp, errorMessage, None)
      _ <- fileManager.writeObject(
             details.location,
             targetLocation,
             objectContent,
             OutboundObjectMetadata.invalid(
              details,
              timingsMetadata("x-amz-meta-upscan-verify-rejected-queued", messageReceivedAt, virusScanTimings)
                ++ metadata("upscan-initiate-received")
                ++ metadata("upscan-initiate-response")
             )
           )
      _ <- fileManager.delete(details.location)
    yield ()

  private def handleFileTypeError(
    details          : InboundObjectDetails,
    errorMessage     : String,
    serviceName      : Option[String]
  )(
    checksum         : String,
    messageReceivedAt: Instant,
    virusScanTimings : Timings,
    fileTypeTimings  : Timings
  )(using
    ExecutionContext,
    LoggingDetails
  ): Future[Unit] =
    def metadata(key: String): Option[(String, String)] =
      details.metadata.items.get(key).map(s"x-amz-meta-${key}" -> _)

    val fileCheckingError = ErrorMessage(FileCheckingError.Rejected, errorMessage)
    val objectContent     = ByteArrayInputStream(Json.toJson(fileCheckingError).toString.getBytes)
    val targetLocation    =
      S3ObjectLocation(configuration.quarantineBucket, UUID.randomUUID().toString, objectVersion = None)

    for
      _ <- rejectionNotifier.notifyInvalidFileType(details.location, checksum, details.metadata.fileSize, details.metadata.uploadedTimestamp, errorMessage, serviceName)
      _ <- fileManager.writeObject(
             details.location,
             targetLocation,
             objectContent,
             OutboundObjectMetadata.invalid(
               details,
               timingsMetadata("x-amz-meta-upscan-verify-rejected-queued", messageReceivedAt, virusScanTimings, Some(fileTypeTimings))
                 ++ metadata("upscan-initiate-received")
                 ++ metadata("upscan-initiate-response")
             )
           )
      _ <- fileManager.delete(details.location)
    yield ()

  private def timingsMetadata(
    queueMetadataKey  : String,
    messageReceivedAt : Instant,
    virusScanTimings  : Timings,
    fileTypeTimingsOpt: Option[Timings] = None
  ): Map[String,String] =
    virusScanTimings.asMetadata("virusscan")
      ++ fileTypeTimingsOpt.fold(Map.empty)(_.asMetadata("filetype"))
      ++ Map(
           "x-amz-meta-upscan-verify-received" -> messageReceivedAt.toString(),
           queueMetadataKey                    -> clock.instant().toString()
         )
