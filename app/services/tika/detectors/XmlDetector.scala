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

package services.tika.detectors

import org.apache.tika.detect.{Detector, XmlRootExtractor}
import org.apache.tika.metadata.{Metadata, TikaCoreProperties}
import org.apache.tika.mime.MediaType

import java.io.InputStream

class XmlDetector extends Detector:

  val extractor = new XmlRootExtractor

  override def detect(input: InputStream, metadata: Metadata): MediaType =
    if filenameHasHtmlExtension(metadata) then
      MediaType.OCTET_STREAM
    else
      input.mark(1024)

      try
        Option(extractor.extractRootElement(input))
          .fold(MediaType.OCTET_STREAM)(_ => MediaType.APPLICATION_XML)
      finally
        input.reset()

  private def filenameHasHtmlExtension(metadata: Metadata): Boolean =
    Option(metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY))
      .exists(_.toLowerCase.matches(".*\\.htm(l)?"))
