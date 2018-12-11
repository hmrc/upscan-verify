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

import java.time.Instant

import model.S3ObjectLocation
import play.api.Logger

import scala.concurrent.Future

trait RejectionNotifier {

  def notifyRejection(file: S3ObjectLocation,
                      checksum: String,
                      fileSize: Long,
                      fileUploadDatetime: Instant,
                      details: String,
                      serviceName: Option[String],
                      customMessagePrefix: String): Future[Unit]

  def notifyFileInfected(file: S3ObjectLocation,
                         checksum: String,
                         fileSize: Long,
                         fileUploadDatetime: Instant,
                         details: String,
                         serviceName: Option[String]
                        ): Future[Unit] = notifyRejection(file, checksum, fileSize, fileUploadDatetime, details, serviceName, "Virus detected in file.")

  def notifyInvalidFileType(file: S3ObjectLocation,
                            checksum: String,
                            fileSize: Long,
                            fileUploadDatetime: Instant,
                            details: String,
                            serviceName: Option[String]
                           ): Future[Unit] = notifyRejection(file, checksum, fileSize, fileUploadDatetime, details, serviceName, "Invalid file type uploaded for service.")
}


object LoggingRejectionNotifier extends RejectionNotifier {

  override def notifyRejection(file: S3ObjectLocation,
                               checksum: String,
                               fileSize: Long,
                               fileUploadDatetime: Instant,
                               details: String,
                               serviceName: Option[String],
                               customMessagePrefix: String): Future[Unit] = {
    Logger.warn(s"$customMessagePrefix ${serviceName.fold("")(service => s"Service name: [$service]. ")}Checksum: [$checksum]. File size: [$fileSize B]. File upload datetime: [$fileUploadDatetime]. Details: [$details].")
    Future.successful(())
  }
}
