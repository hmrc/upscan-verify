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

package uk.gov.hmrc.upscanverify.service.tika

import org.apache.tika.config.TikaConfig
import org.apache.tika.metadata.{Metadata, TikaCoreProperties}
import uk.gov.hmrc.upscanverify.service.{DetectedMimeType, MimeType, MimeTypeDetector}

import java.io.InputStream
import javax.inject.Singleton

@Singleton
class TikaMimeTypeDetector extends MimeTypeDetector:

  private val config   = TikaConfig.getDefaultConfig
  private val detector = config.getDetector

  def detect(inputStream: InputStream, fileName: Option[String]): DetectedMimeType =
    import org.apache.tika.io.TikaInputStream

    val tikaInputStream = TikaInputStream.get(inputStream)

    try
      val metadata = Metadata()
      fileName.foreach(name => metadata.add(TikaCoreProperties.RESOURCE_NAME_KEY, name))

      val detectionResult = detector.detect(tikaInputStream, metadata)
      // what does the following line print when it fails?
      val mimeType        = MimeType(s"${detectionResult.getType}/${detectionResult.getSubtype}")

      if tikaInputStream.getLength > 0 then
        DetectedMimeType.Detected(mimeType)
      else
        DetectedMimeType.EmptyLength(mimeType)

    catch
      case e: java.io.IOException if e.getMessage == "Stream is already being read" =>
        DetectedMimeType.Failed(e.getMessage)

    finally
      tikaInputStream.close()