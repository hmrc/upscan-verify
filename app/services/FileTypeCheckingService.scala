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

import model.{FileCheckingResult, S3ObjectLocation, ValidFileCheckingResult}
import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}

class FileTypeCheckingService @Inject()(fileTypeDetector: FileTypeDetector)(implicit ec: ExecutionContext) {

  def check(
    location: S3ObjectLocation,
    objectContent: ObjectContent,
    objectMetadata: ObjectMetadata): Future[FileCheckingResult] = {
    val fileType = fileTypeDetector.detectType(objectContent.inputStream, objectMetadata.originalFilename)
    Logger.info(s"File [$location] has a type [$fileType]")
    Future.successful(ValidFileCheckingResult(location))
  }
}
