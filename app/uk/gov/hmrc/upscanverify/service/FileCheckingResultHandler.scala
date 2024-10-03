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
    result           : VerifyResult,
    messageReceivedAt: Instant
  )(using ld: LoggingDetails): Future[Unit] =
    withLoggingDetails(ld):
      logger.info(s"Handling check result for Key=[${objectDetails.location.objectKey}] Result=[$result]")
    result match
      case Right(VerifyResult.FileValidationSuccess(noVirusFound, fileAllowed)) =>
        handleValid(
          objectDetails,
          noVirusFound.checksum,
          fileAllowed.mimeType,
          metadata = metadata(objectDetails, "x-amz-meta-upscan-verify-outbound-queued", messageReceivedAt, noVirusFound.virusScanTimings, Some(fileAllowed.fileTypeTimings))
        )

      case Left(VerifyResult.FileRejected.VirusScanFailure(virusFound)) =>
        handleError(
          objectDetails,
          virusFound.checksum,
          ErrorMessage(FileCheckingError.Quarantine, virusFound.details),
          serviceName         = None,
          metadata            = metadata(objectDetails, "x-amz-meta-upscan-verify-rejected-queued", messageReceivedAt, virusFound.virusScanTimings, fileTypeTimings = None)
        )

      case Left(VerifyResult.FileRejected.FileTypeFailure(noVirusFound, fileTypeError)) =>
        val errorMessage =
          fileTypeError match
            case FileTypeError.NotAllowedMimeType(mime, consumingService, fileTypeTimings) =>
              s"MIME type [${mime.value}] is not allowed for service: [${consumingService.getOrElse("No service name provided")}]"
            case FileTypeError.NotAllowedFileExtension(mime, extension, consumingService, fileTypeTimings) =>
              s"File extension [$extension] is not allowed for mime-type [${mime.value}], service: [${consumingService.getOrElse("No service name provided")}]"
            case FileTypeError.Corrupt(consumingService, fileTypeTimings) =>
              s"File is corrupt, service: [${consumingService.getOrElse("No service name provided")}]"

        handleError(
          objectDetails,
          noVirusFound.checksum,
          ErrorMessage(FileCheckingError.Rejected, errorMessage),
          serviceName         = fileTypeError.consumingService,
          metadata            = metadata(objectDetails, "x-amz-meta-upscan-verify-rejected-queued", messageReceivedAt, noVirusFound.virusScanTimings, Some(fileTypeError.fileTypeTimings))
        )

  private def handleValid(
    details : InboundObjectDetails,
    checksum: String,
    mimeType: MimeType,
    metadata: Map[String,String]
  )(using
    ExecutionContext,
    LoggingDetails
  ): Future[Unit] =
    for
      _ <- fileManager.copyObject(
             sourceLocation = details.location,
             targetLocation = S3ObjectLocation(configuration.outboundBucket, UUID.randomUUID().toString, objectVersion = None),
             metadata       = OutboundObjectMetadata.valid(
                                details,
                                checksum,
                                mimeType,
                                metadata
                              )
           )
      _ <- fileManager.delete(details.location)
    yield ()

  private def handleError(
    details            : InboundObjectDetails,
    checksum           : String,
    errorMessage       : ErrorMessage,
    serviceName        : Option[String],
    metadata           : Map[String,String]
  )(using
    ExecutionContext,
    LoggingDetails
  ): Future[Unit] =
    for
      _ <- rejectionNotifier.notifyRejection(
             fileProperties      = details.location,
             checksum            = checksum,
             fileSize            = details.metadata.fileSize,
             fileUploadDatetime  = details.metadata.uploadedTimestamp,
             errorMessage        = errorMessage,
             serviceName         = serviceName
           )
      _ <- fileManager.writeObject(
             sourceLocation = details.location,
             targetLocation = S3ObjectLocation(configuration.quarantineBucket, UUID.randomUUID().toString, objectVersion = None),
             content        = ByteArrayInputStream(Json.toJson(errorMessage).toString.getBytes),
             metadata       = OutboundObjectMetadata.invalid(details, metadata)
           )
      _ <- fileManager.delete(details.location)
    yield ()

  def metadata(
    details          : InboundObjectDetails,
    queueMetadataKey : String,
    messageReceivedAt: Instant,
    virusScanTimings : Timings,
    fileTypeTimings  : Option[Timings]
  ): Map[String, String] =
    timingsMetadata(queueMetadataKey, messageReceivedAt, virusScanTimings, fileTypeTimings)
      ++ details.metadata.items.get("upscan-initiate-received").map("x-amz-meta-upscan-initiate-received" -> _)
      ++ details.metadata.items.get("upscan-initiate-response").map("x-amz-meta-upscan-initiate-response" -> _)

  private def timingsMetadata(
    queueMetadataKey : String,
    messageReceivedAt: Instant,
    virusScanTimings : Timings,
    fileTypeTimings  : Option[Timings]
  ): Map[String, String] =
    virusScanTimings.asMetadata("virusscan")
      ++ fileTypeTimings.fold(Map.empty)(_.asMetadata("filetype"))
      ++ Map(
           "x-amz-meta-upscan-verify-received" -> messageReceivedAt.toString,
           queueMetadataKey                    -> clock.instant().toString
         )
