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

package model

import play.api.libs.json._
import services.MimeType

import scala.concurrent.{ExecutionContext, Future}

case class Message(id: String, body: String, receiptHandle: String)

case class S3ObjectLocation(bucket: String, objectKey: String)
case class FileUploadEvent(location: S3ObjectLocation)

sealed trait FileCheckingResult {
  val location: S3ObjectLocation

  def andThen(f: () => Future[FileCheckingResult]): Future[FileCheckingResult] =
    this match {
      case v: ValidFileCheckingResult   => f()
      case i: InvalidFileCheckingResult => Future.successful(i)
    }
}

object FileCheckingResult {

  implicit class FutureFileCheckingResult(origin: Future[FileCheckingResult]) {
    def andThenCheck(f: () => Future[FileCheckingResult])(implicit ec: ExecutionContext): Future[FileCheckingResult] =
      origin.flatMap(result => result.andThen(f))
  }
}

case class ValidFileCheckingResult(location: S3ObjectLocation) extends FileCheckingResult

sealed trait InvalidFileCheckingResult extends FileCheckingResult
case class FileInfectedCheckingResult(location: S3ObjectLocation, details: String) extends InvalidFileCheckingResult
case class IncorrectFileType(location: S3ObjectLocation, typeFound: MimeType, consumingService: Option[String])
    extends InvalidFileCheckingResult

case class AllowedMimeTypes(serviceName: String, allowedMimeTypes: List[String])

case class ConsumingServicesConfiguration(serviceConfigurations: List[AllowedMimeTypes]) {
  def allowedMimeTypes(consumingService: String): List[String] =
    serviceConfigurations
      .find(_.serviceName == consumingService)
      .map(_.allowedMimeTypes)
      .getOrElse(Nil)
}

sealed trait FileCheckingError {
  def value: String
}

case object Quarantine extends FileCheckingError {
  override def value: String = "QUARANTINE"
}
case object Rejected extends FileCheckingError {
  override def value: String = "REJECTED"
}

object FileCheckingError {
  implicit val fileCheckingErrorWrites: Writes[FileCheckingError] = new Writes[FileCheckingError] {
    override def writes(fileCheckingError: FileCheckingError): JsValue = JsString(fileCheckingError.value)
  }
}

case class ErrorMessage(failureReason: FileCheckingError, message: String)

object ErrorMessage {
  implicit val errorMessageWrites: Writes[ErrorMessage] = Json.writes[ErrorMessage]
}
