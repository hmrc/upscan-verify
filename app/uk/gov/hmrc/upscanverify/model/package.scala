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

package uk.gov.hmrc.upscanverify.model

import play.api.libs.json._
import uk.gov.hmrc.upscanverify.service.MimeType

import java.time.{Clock, Instant}

case class Message(
  id            : String,
  body          : String,
  receiptHandle : String,
  receivedAt    : Instant,
  queueTimeStamp: Option[Instant]
)

case class S3ObjectLocation(
  bucket       : String,
  objectKey    : String,
  objectVersion: Option[String]
)

case class FileUploadEvent(
  location: S3ObjectLocation,
  clientIp: String
)

case class Timings(
  start: Instant,
  end  : Instant
):
  def asMetadata(checkpoint: String): Map[String, String] =
    Map(
      s"x-amz-meta-upscan-verify-$checkpoint-started" -> start.toString,
      s"x-amz-meta-upscan-verify-$checkpoint-ended"   -> end.toString
    )

  lazy val difference: Long =
    end.toEpochMilli - start.toEpochMilli

object Timings:

  type Timer = () => Timings

  def timer()(using clock: Clock): Timer =
    val startTime = clock.instant()
    () =>
      Timings(startTime, clock.instant())

object VirusScanResult:
  case class FileInfected(
    details         : String,
    checksum        : String,
    virusScanTimings: Timings
  )

  case class NoVirusFound(
    checksum        : String,
    virusScanTimings: Timings
  )

type VirusScanResult = Either[VirusScanResult.FileInfected, VirusScanResult.NoVirusFound]

case class FileAllowed(
  mimeType       : MimeType,
  fileTypeTimings: Timings
)

enum FileTypeError(
  val consumingService: Option[String],
  val fileTypeTimings : Timings
):
  case NotAllowedMimeType(
    typeFound                    : MimeType,
    override val consumingService: Option[String],
    override val fileTypeTimings : Timings
  ) extends FileTypeError(consumingService, fileTypeTimings)

  case NotAllowedFileExtension(
    typeFound                    : MimeType,
    fileExtension                : String,
    override val consumingService: Option[String],
    override val fileTypeTimings : Timings
  ) extends FileTypeError(consumingService, fileTypeTimings)

type FileTypeCheckResult = Either[FileTypeError, FileAllowed]

object VerifyResult:
  enum FileRejected:
    case VirusScanFailure(
      virusScanResult: VirusScanResult.FileInfected
    ) extends FileRejected

    case FileTypeFailure(
      virusScanResult: VirusScanResult.NoVirusFound,
      fileTypeResult : FileTypeError
    ) extends FileRejected

  case class FileValidationSuccess(
    virusScanResult: VirusScanResult.NoVirusFound,
    fileTypeResult : FileAllowed
  )

type VerifyResult = Either[VerifyResult.FileRejected, VerifyResult.FileValidationSuccess]


enum FileCheckingError(val value: String):
  case Quarantine extends FileCheckingError("QUARANTINE")
  case Rejected   extends FileCheckingError("REJECTED")

object FileCheckingError:
  given Writes[FileCheckingError] =
    (fileCheckingError: FileCheckingError) => JsString(fileCheckingError.value)

case class ErrorMessage(
  failureReason: FileCheckingError,
  message      : String
)

object ErrorMessage:
  given Writes[ErrorMessage] =
    Json.writes[ErrorMessage]
