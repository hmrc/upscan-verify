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

package services.tika

import cats.syntax.either._
import com.typesafe.config.ConfigFactory
import org.apache.commons.io.FilenameUtils
import play.api.Configuration
import services.MimeType

import javax.inject.Singleton

@Singleton
class FileNameValidator(configuration: Configuration) {

  type FileExtension = String

  def this() = this(Configuration(ConfigFactory.parseResources("extensionsAllowList.conf")))

  private val mimeTypes = configuration.get[Map[String, Seq[String]]]("allowedExtensions")

  def validate(mimeType: MimeType, filename: String): Either[FileExtension, Unit] =
    fileExtension(filename).fold(().asRight[FileExtension])(validateMimeType(mimeType))

  private def fileExtension(filename: String): Option[String] = Option(FilenameUtils.getExtension(filename)).filter(_.nonEmpty)

  private def validateMimeType(mimeType: MimeType)(fileExtension: String): Either[FileExtension, Unit] =
    if (mimeTypes.get(mimeType.value).forall(_.contains(fileExtension))) Right(()) else Left(fileExtension)
}
