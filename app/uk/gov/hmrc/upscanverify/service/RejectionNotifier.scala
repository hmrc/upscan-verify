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
import uk.gov.hmrc.upscanverify.model.{ErrorMessage, FileCheckingError, S3ObjectLocation}

import java.time.Instant
import scala.concurrent.Future

trait RejectionNotifier:

  def notifyRejection(
    fileProperties     : S3ObjectLocation,
    checksum           : String,
    fileSize           : Long,
    fileUploadDatetime : Instant,
    errorMessage       : ErrorMessage,
    serviceName        : Option[String]
  ): Future[Unit]

object LoggingRejectionNotifier
  extends RejectionNotifier
     with Logging:

  override def notifyRejection(
    fileProperties     : S3ObjectLocation,
    checksum           : String,
    fileSize           : Long,
    fileUploadDatetime : Instant,
    errorMessage       : ErrorMessage,
    serviceName        : Option[String]
  ): Future[Unit] =
    val customMessagePrefix =
      errorMessage.failureReason match
        case FileCheckingError.Quarantine => "Virus detected in file."
        case FileCheckingError.Rejected   => "File type is not allowed for this service."

    logger.warn(
      s"""$customMessagePrefix${serviceName.fold("")(service => s"\nService name: [$service]")}
          |Key: [${fileProperties.objectKey}]${fileProperties.objectVersion.fold("")(version => s"\nVersion: [$version]")}
          |Checksum: [$checksum]
          |File size: [$fileSize B]
          |File upload datetime: [$fileUploadDatetime]
          |Bucket: [${fileProperties.bucket}]
          |Details: [${errorMessage.message}].
        """.stripMargin
    )
    Future.unit
