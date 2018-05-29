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

import javax.inject.Inject

import config.ServiceConfiguration
import model.{FileCheckingResult, IncorrectFileType, S3ObjectLocation, ValidFileCheckingResult}
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}

class FileTypeCheckingService @Inject()(fileTypeDetector: FileTypeDetector, serviceConfiguration: ServiceConfiguration)(
  implicit ec: ExecutionContext) {

  def check(
    location: S3ObjectLocation,
    objectContent: ObjectContent,
    objectMetadata: ObjectMetadata): Future[FileCheckingResult] = {
    val fileType         = fileTypeDetector.detectType(objectContent.inputStream, objectMetadata.originalFilename)
    val consumingService = objectMetadata.items.get("consuming-service")

    if (isAllowedMimeType(fileType, location, consumingService)) Future.successful(ValidFileCheckingResult(location))
    else Future.successful(IncorrectFileType(location, fileType, consumingService))
  }

  private def isAllowedMimeType(
    mimeType: MimeType,
    location: S3ObjectLocation,
    consumingService: Option[String]): Boolean =
    consumingService match {
      case Some(service) => isAllowedForService(mimeType, service)
      case None =>
        Logger.error(s"No x-amz-meta-consuming-service metadata for [${location.objectKey}]")
        false
    }

  private def isAllowedForService(mimeType: MimeType, consumingService: String): Boolean = {
    val allowedMimeTypes = serviceConfiguration.consumingServicesConfiguration.allowedMimeTypes(consumingService)
    if (allowedMimeTypes.contains(mimeType.value)) true
    else {
      Logger.error(s"Consuming service [$consumingService] does not allow MIME type: [${mimeType.value}]")
      false
    }
  }
}
