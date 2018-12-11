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

import java.io.ByteArrayInputStream
import java.time.{Clock, Instant}
import java.util.UUID

import config.ServiceConfiguration
import javax.inject.Inject
import model._
import play.api.Logger
import play.api.libs.json.Json
import uk.gov.hmrc.http.logging.LoggingDetails

import scala.concurrent.{ExecutionContext, Future}

class FileCheckingResultHandler @Inject()(
  fileManager: FileManager,
  virusNotifier: VirusNotifier,
  configuration: ServiceConfiguration,
  clock: Clock)(implicit ec: ExecutionContext) {

  def handleCheckingResult(
    objectDetails: InboundObjectDetails,
    result: Either[FileValidationFailure, FileValidationSuccess],
    messageReceivedAt: Instant)(
    implicit
    ld: LoggingDetails): Future[Unit] =
    result match {
      case Right(FileValidationSuccess(checksum, mimeType, virusScanTimings, fileTypeTimings)) =>
        handleValid(objectDetails, checksum, mimeType)(messageReceivedAt, virusScanTimings, fileTypeTimings)

      case Left(FileRejected(Left(FileInfected(errorMessage, virusScanTimings)), None)) =>
        handleInfected(objectDetails, errorMessage)(messageReceivedAt, virusScanTimings)

      case Left(FileRejected(Right(NoVirusFound(_, virusScanTimings)), Some(IncorrectFileType(mime, consumingService, fileTypeTimings)))) =>
        handleIncorrectType(objectDetails, mime, consumingService)(messageReceivedAt, virusScanTimings, fileTypeTimings)

      case _ => Future.successful(Logger.error(s"Unexpected match result for result: [$result]."))
    }

  private def handleValid(details: InboundObjectDetails, checksum: String, mimeType: MimeType)
                         (messageReceivedAt: Instant, virusScanTimings: Timings, fileTypeTimings: Timings)
                         (implicit ec: ExecutionContext, ld: LoggingDetails) = {

    def metadata(key: String): Option[(String,String)] = details.metadata.items.get(key).map(s"x-amz-meta-${key}" -> _)

    val targetLocation =
      S3ObjectLocation(configuration.outboundBucket, UUID.randomUUID().toString, objectVersion = None)

    for {
      _ <- fileManager
            .copyObject(
              details.location,
              targetLocation,
              ValidOutboundObjectMetadata(
                details, checksum, mimeType,
                timingsMetadata("x-amz-meta-upscan-verify-outbound-queued", messageReceivedAt, virusScanTimings, Some(fileTypeTimings))
                  ++ metadata("upscan-initiate-received")
                  ++ metadata("upscan-initiate-response")
              )
            )
      _ <- fileManager.delete(details.location)
    } yield ()
  }

  private def handleInfected(details: InboundObjectDetails, errorMessage: String)
                            (messageReceivedAt: Instant, virusScanTimings: Timings)
                            (implicit ec: ExecutionContext, ld: LoggingDetails) = {

    def metadata(key: String): Option[(String,String)] = details.metadata.items.get(key).map(s"x-amz-meta-${key}" -> _)

    val fileCheckingError = ErrorMessage(Quarantine, errorMessage)
    val objectContent     = new ByteArrayInputStream(Json.toJson(fileCheckingError).toString.getBytes)
    val targetLocation =
      S3ObjectLocation(configuration.quarantineBucket, UUID.randomUUID().toString, objectVersion = None)

    for {
      _ <- virusNotifier.notifyFileInfected(details.location, errorMessage)
      _ <- fileManager.writeObject(details.location, targetLocation, objectContent,
                                   InvalidOutboundObjectMetadata(details,
                                     timingsMetadata("x-amz-meta-upscan-verify-rejected-queued", messageReceivedAt, virusScanTimings)
                                       ++ metadata("x-amz-meta-upscan-initiate-received")
                                       ++ metadata("x-amz-meta-upscan-initiate-response")
                                   )
           )
      _ <- fileManager.delete(details.location)
    } yield ()
  }

  private def handleIncorrectType(details: InboundObjectDetails, mimeType: MimeType, serviceName: Option[String])
                                 (messageReceivedAt: Instant, virusScanTimings: Timings, fileTypeTimings: Timings)
                                 (implicit ec: ExecutionContext, ld: LoggingDetails) = {

    def metadata(key: String): Option[(String,String)] = details.metadata.items.get(key).map(s"x-amz-meta-${key}" -> _)

    val errorMessage =
      s"MIME type [${mimeType.value}] is not allowed for service: [${serviceName.getOrElse("No service name provided")}]"
    val fileCheckingError = ErrorMessage(Rejected, errorMessage)
    val objectContent     = new ByteArrayInputStream(Json.toJson(fileCheckingError).toString.getBytes)
    val targetLocation =
      S3ObjectLocation(configuration.quarantineBucket, UUID.randomUUID().toString, objectVersion = None)

    for {
      _ <- fileManager
            .writeObject(details.location, targetLocation, objectContent, InvalidOutboundObjectMetadata(
              details,
              timingsMetadata("x-amz-meta-upscan-verify-rejected-queued", messageReceivedAt, virusScanTimings, Some(fileTypeTimings))
                ++ metadata("x-amz-meta-upscan-initiate-received")
                ++ metadata("x-amz-meta-upscan-initiate-response")
            ))
      _ <- fileManager.delete(details.location)
    } yield ()
  }

  private def timingsMetadata(queueMetadataKey: String,
                              messageReceivedAt: Instant,
                              virusScanTimings: Timings,
                              fileTypeTimingsOpt: Option[Timings] = None): Map[String,String] =
    virusScanTimings.asMetadata("virusscan") ++
    fileTypeTimingsOpt.map{_.asMetadata("filetype")}.getOrElse(Map.empty) ++
    Map(
      "x-amz-meta-upscan-verify-received" -> messageReceivedAt.toString(),
      queueMetadataKey                    -> clock.instant().toString()
    )

}
