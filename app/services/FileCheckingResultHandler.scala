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

  def handleCheckingResult(result: FileCheckingResult): Future[InstanceSafety] = {
    implicit val ld = LoggingDetails.fromS3ObjectLocation(result.location)

    result match {
      case ValidFileCheckingResult(location)                   => handleValid(location)
      case FileInfectedCheckingResult(location, details)       => handleInfected(location, details)
      case IncorrectFileType(location, mime, consumingService) => handleIncorrectType(location, mime, consumingService)
    }
  }

  private def handleValid(objectLocation: S3ObjectLocation)(implicit ec: ExecutionContext) =
    for {
      _ <- fileManager.copyToOutboundBucket(objectLocation)
      _ <- fileManager.delete(objectLocation)
    } yield SafeToContinue

  private def handleInfected(objectLocation: S3ObjectLocation, details: String)(implicit ec: ExecutionContext) = {
    val fileCheckingError = ErrorMessage(Quarantine, details)
    val objectContent     = new ByteArrayInputStream(Json.toJson(fileCheckingError).toString.getBytes)

    for {
      _        <- virusNotifier.notifyFileInfected(objectLocation, details)
      metadata <- fileManager.getObjectMetadata(objectLocation)
      _        <- fileManager.writeToQuarantineBucket(objectLocation, objectContent, metadata)
      _        <- fileManager.delete(objectLocation)
    } yield ShouldTerminate
  }

  private def handleIncorrectType(objectLocation: S3ObjectLocation, mimeType: MimeType, serviceName: Option[String])(
    implicit ec: ExecutionContext) = {
    val details =
      s"MIME type [${mimeType.value}] is not allowed for service: [${serviceName.getOrElse("No service name provided")}]"
    val fileCheckingError = ErrorMessage(Rejected, details)
    val objectContent     = new ByteArrayInputStream(Json.toJson(fileCheckingError).toString.getBytes)

    for {
      metadata <- fileManager.getObjectMetadata(objectLocation)
      _        <- fileManager.writeToQuarantineBucket(objectLocation, objectContent, metadata)
      _        <- fileManager.delete(objectLocation)
    } yield SafeToContinue
  }
}
