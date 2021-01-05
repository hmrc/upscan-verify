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

package services

import java.io.InputStream

import org.apache.tika.config.TikaConfig
import org.apache.tika.metadata.{Metadata, TikaMetadataKeys}

case class MimeType(value: String) extends AnyVal

trait FileTypeDetector {
  def detectType(inputStream: InputStream, fileName: Option[String]): MimeType
}

class TikaFileTypeDetector extends FileTypeDetector {

  private val config   = TikaConfig.getDefaultConfig
  private val detector = config.getDetector

  def detectType(inputStream: InputStream, fileName: Option[String]): MimeType = {
    import org.apache.tika.io.TikaInputStream

    val tikaInputStream = TikaInputStream.get(inputStream)

    val metadata = new Metadata()
    fileName.foreach(metadata.add(TikaMetadataKeys.RESOURCE_NAME_KEY, _))
    val detectionResult = detector.detect(tikaInputStream, metadata)

    tikaInputStream.close()

    MimeType(s"${detectionResult.getType}/${detectionResult.getSubtype}")
  }
}
