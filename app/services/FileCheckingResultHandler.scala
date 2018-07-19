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
import java.util.UUID

import config.ServiceConfiguration
import javax.inject.Inject
import model._
import play.api.libs.json.Json
import uk.gov.hmrc.http.logging.LoggingDetails
import util.logging.LoggingDetails

import scala.concurrent.{ExecutionContext, Future}

class FileCheckingResultHandler @Inject()(
  fileManager: FileManager,
  virusNotifier: VirusNotifier,
  configuration: ServiceConfiguration) {

  def handleCheckingResult(
    objectDetails: InboundObjectDetails,
    result: Either[FileValidationFailure, FileValidationSuccess])(implicit ec: ExecutionContext): Future[Unit] = {
    implicit val ld = LoggingDetails.fromS3ObjectLocation(objectDetails.location)

    result match {
      case Right(FileValidationSuccess(checksum, mimeType)) =>
        handleValid(objectDetails, checksum, mimeType)
      case Left(FileInfected(errorMessage)) =>
        handleInfected(objectDetails, errorMessage)
      case Left(IncorrectFileType(mime, consumingService)) =>
        handleIncorrectType(objectDetails, mime, consumingService)
    }
  }

  private def handleValid(details: InboundObjectDetails, checksum: String, mimeType: MimeType)(
    implicit ec: ExecutionContext,
    ld: LoggingDetails) = {
    val targetLocation =
      S3ObjectLocation(configuration.outboundBucket, UUID.randomUUID().toString, objectVersion = None)
    for {
      _ <- fileManager
            .copyObject(
              details.location,
              targetLocation,
              ValidOutboundObjectMetadata(details, checksum, mimeType)
            )
      _ <- fileManager.delete(details.location)
    } yield ()
  }

  private def handleInfected(details: InboundObjectDetails, errorMessage: String)(
    implicit ec: ExecutionContext,
    ld: LoggingDetails) = {
    val fileCheckingError = ErrorMessage(Quarantine, errorMessage)
    val objectContent     = new ByteArrayInputStream(Json.toJson(fileCheckingError).toString.getBytes)
    val targetLocation =
      S3ObjectLocation(configuration.quarantineBucket, UUID.randomUUID().toString, objectVersion = None)

    for {
      _ <- virusNotifier.notifyFileInfected(details.location, errorMessage)
      _ <- fileManager
            .writeObject(details.location, targetLocation, objectContent, InvalidOutboundObjectMetadata(details))
      _ <- fileManager.delete(details.location)
    } yield ()
  }

  private def handleIncorrectType(details: InboundObjectDetails, mimeType: MimeType, serviceName: Option[String])(
    implicit ec: ExecutionContext,
    ld: LoggingDetails) = {
    val errorMessage =
      s"MIME type [${mimeType.value}] is not allowed for service: [${serviceName.getOrElse("No service name provided")}]"
    val fileCheckingError = ErrorMessage(Rejected, errorMessage)
    val objectContent     = new ByteArrayInputStream(Json.toJson(fileCheckingError).toString.getBytes)
    val targetLocation =
      S3ObjectLocation(configuration.quarantineBucket, UUID.randomUUID().toString, objectVersion = None)

    for {
      _ <- fileManager
            .writeObject(details.location, targetLocation, objectContent, InvalidOutboundObjectMetadata(details))
      _ <- fileManager.delete(details.location)
    } yield ()
  }
}
