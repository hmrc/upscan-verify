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
import org.apache.tika.config.TikaConfig
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.{Metadata, TikaMetadataKeys}
import services.MimeTypeDetector.MimeTypeDetectionError
import services.MimeTypeDetector.MimeTypeDetectionError.NotAllowedFileExtension
import services.{MimeType, MimeTypeDetector}

import java.io.InputStream
import javax.inject.{Inject, Singleton}

@Singleton
class TikaMimeTypeDetector @Inject()(fileNameValidator: FileNameValidator) extends MimeTypeDetector {

  private val config   = TikaConfig.getDefaultConfig
  private val detector = config.getDetector

  def detect(inputStream: InputStream, maybeFileName: Option[String]): Either[MimeTypeDetectionError, MimeType] = {
    val mediaType = detectMediaType(inputStream, maybeFileName)
    val mimeType  = MimeType(s"${mediaType.getType}/${mediaType.getSubtype}")
    maybeFileName.fold(mimeType.asRight[MimeTypeDetectionError]) { filename =>
      fileNameValidator
        .validate(mimeType, filename)
        .map(_ => mimeType)
        .leftMap(extension => NotAllowedFileExtension(mimeType, extension))
    }
  }

  private def detectMediaType(inputStream: InputStream, maybeFileName: Option[String]) = {
    val tikaInputStream: TikaInputStream = TikaInputStream.get(inputStream)

    val metadata = new Metadata()
    maybeFileName.foreach(metadata.add(TikaMetadataKeys.RESOURCE_NAME_KEY, _))
    val mediaType = detector.detect(tikaInputStream, metadata)

    tikaInputStream.close()
    mediaType
  }
}
