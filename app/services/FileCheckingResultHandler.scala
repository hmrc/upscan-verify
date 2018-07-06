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
import javax.inject.Inject

import model._
import play.api.libs.json.Json
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext.fromLoggingDetails
import util.logging.LoggingDetails

import scala.concurrent.{ExecutionContext, Future}

sealed trait InstanceSafety extends Product with Serializable
case object SafeToContinue extends InstanceSafety
case object ShouldTerminate extends InstanceSafety

class FileCheckingResultHandler @Inject()(fileManager: FileManager, virusNotifier: VirusNotifier) {

  def handleCheckingResult(
    objectDetails: InboundObjectDetails,
    result: Either[FileValidationFailure, FileValidationSuccess]): Future[InstanceSafety] = {
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
    implicit ec: ExecutionContext) =
    for {
      _ <- fileManager
            .copyToOutboundBucket(
              details.location,
              ValidOutboundObjectMetadata(details.metadata, checksum, mimeType, details.clientIp))
      _ <- fileManager.delete(details.location)
    } yield SafeToContinue

  private def handleInfected(details: InboundObjectDetails, errorMessage: String)(implicit ec: ExecutionContext) = {
    val fileCheckingError = ErrorMessage(Quarantine, errorMessage)
    val objectContent     = new ByteArrayInputStream(Json.toJson(fileCheckingError).toString.getBytes)

    for {
      _ <- virusNotifier.notifyFileInfected(details.location, errorMessage)
      _ <- fileManager
            .writeToQuarantineBucket(
              details.location,
              objectContent,
              InvalidOutboundObjectMetadata(details.metadata, details.clientIp))
      _ <- fileManager.delete(details.location)
    } yield ShouldTerminate
  }

  private def handleIncorrectType(details: InboundObjectDetails, mimeType: MimeType, serviceName: Option[String])(
    implicit ec: ExecutionContext) = {
    val errorMessage =
      s"MIME type [${mimeType.value}] is not allowed for service: [${serviceName.getOrElse("No service name provided")}]"
    val fileCheckingError = ErrorMessage(Rejected, errorMessage)
    val objectContent     = new ByteArrayInputStream(Json.toJson(fileCheckingError).toString.getBytes)

    for {
      _ <- fileManager
            .writeToQuarantineBucket(
              details.location,
              objectContent,
              InvalidOutboundObjectMetadata(details.metadata, details.clientIp))
      _ <- fileManager.delete(details.location)
    } yield SafeToContinue
  }
}
