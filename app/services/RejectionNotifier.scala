/*
 * Copyright 2020 HM Revenue & Customs
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

import java.time.Instant

import model.S3ObjectLocation
import play.api.Logging

import scala.concurrent.Future

trait RejectionNotifier {

  def notifyRejection(fileProperties: S3ObjectLocation,
                      checksum: String,
                      fileSize: Long,
                      fileUploadDatetime: Instant,
                      details: String,
                      serviceName: Option[String],
                      customMessagePrefix: String): Future[Unit]

  def notifyFileInfected(fileProperties: S3ObjectLocation,
                         checksum: String,
                         fileSize: Long,
                         fileUploadDatetime: Instant,
                         details: String,
                         serviceName: Option[String]
                        ): Future[Unit] = notifyRejection(fileProperties, checksum, fileSize, fileUploadDatetime, details, serviceName, "Virus detected in file.")

  def notifyInvalidFileType(fileProperties: S3ObjectLocation,
                            checksum: String,
                            fileSize: Long,
                            fileUploadDatetime: Instant,
                            details: String,
                            serviceName: Option[String]
                           ): Future[Unit] = notifyRejection(fileProperties, checksum, fileSize, fileUploadDatetime, details, serviceName, "Invalid file type uploaded, which is not whitelisted for this service.")
}


object LoggingRejectionNotifier extends RejectionNotifier with Logging {

  override def notifyRejection(fileProperties: S3ObjectLocation,
                               checksum: String,
                               fileSize: Long,
                               fileUploadDatetime: Instant,
                               details: String,
                               serviceName: Option[String],
                               customMessagePrefix: String): Future[Unit] = {
    logger.warn(
      s"""$customMessagePrefix${serviceName.fold("")(service => s"\nService name: [$service]")}
         |File ID: [${fileProperties.objectKey}]${fileProperties.objectVersion.fold("")(version => s"\nVersion: [$version]")}
         |Checksum: [$checksum]
         |File size: [$fileSize B]
         |File upload datetime: [$fileUploadDatetime]
         |Bucket: [${fileProperties.bucket}]
         |Details: [$details].
       """.stripMargin
    )
    Future.successful(())
  }
}
