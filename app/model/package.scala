/*
 * Copyright 2021 HM Revenue & Customs
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
import services.{MimeType, NoVirusFound}

import java.time.{Clock, Instant}

case class Message(
  id: String,
  body: String,
  receiptHandle: String,
  receivedAt: Instant,
  queueTimeStamp: Option[Instant])

case class S3ObjectLocation(bucket: String, objectKey: String, objectVersion: Option[String])
case class FileUploadEvent(location: S3ObjectLocation, clientIp: String)

case class Timings(start: Instant, end: Instant) {
  def asMetadata(checkpoint: String): Map[String, String] =
    Map(
      s"x-amz-meta-upscan-verify-$checkpoint-started" -> start.toString,
      s"x-amz-meta-upscan-verify-$checkpoint-ended"   -> end.toString
    )

  lazy val difference: Long = end.toEpochMilli - start.toEpochMilli
}

object Timings {

  type Timer = () => Timings

  def timer()(implicit clock: Clock): Timer = {
    val startTime = clock.instant()
    () =>
      Timings(startTime, clock.instant())
  }
}

case class FileValidationSuccess(
  checksum: String,
  mimeType: MimeType,
  virusScanTimings: Timings,
  fileTypeTimings: Timings)

case class FileInfected(details: String, checksum: String, virusScanTimings: Timings)

sealed trait FileTypeError

object FileTypeError {
  final case class NotAllowedMimeType(typeFound: MimeType, consumingService: Option[String], fileTypeTimings: Timings)
      extends FileTypeError

  final case class NotAllowedFileExtension(
    typeFound: MimeType,
    fileExtension: String,
    consumingService: Option[String],
    fileTypeTimings: Timings)
      extends FileTypeError
}

case class FileRejected(
  virusScanResult: Either[FileInfected, NoVirusFound],
  fileTypeResultOpt: Option[FileTypeError] = None)

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
