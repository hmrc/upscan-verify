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

import java.io.ByteArrayInputStream
import org.scalatest.{Assertions, GivenWhenThen}
import services.MimeType
import test.UnitSpec


class TikaMimeTypeDetectorSpec extends UnitSpec with Assertions with GivenWhenThen {

  "TikaFileTypeDetector" should {

    val tikaMimeTypeDetector = new TikaMimeTypeDetector

    "properly detect XML file" in {
      tikaMimeTypeDetector.detect(this.getClass.getResourceAsStream("/test.xml"), None) shouldBe MimeType(
        "application/xml")
    }

    "properly detect XML file without XML declaration" in {
      tikaMimeTypeDetector.detect(this.getClass.getResourceAsStream("/test2.xml"), None) shouldBe MimeType(
        "application/xml")
    }

    "properly detect JPEG file" in {
      tikaMimeTypeDetector.detect(this.getClass.getResourceAsStream("/test.jpg"), None) shouldBe MimeType(
        "image/jpeg")
    }

    "properly handle empty file" in {
      tikaMimeTypeDetector.detect(new ByteArrayInputStream(Array.emptyByteArray), None) shouldBe MimeType(
        "application/octet-stream")
    }

    "properly detect PNG file" in {
      tikaMimeTypeDetector.detect(this.getClass.getResourceAsStream("/test.png"), None) shouldBe MimeType(
        "image/png")
    }

    "properly detect PDF file" in {
      tikaMimeTypeDetector.detect(this.getClass.getResourceAsStream("/test.pdf"), None) shouldBe MimeType(
        "application/pdf")
    }

    "properly detect Open Office Writer file" in {
      tikaMimeTypeDetector.detect(this.getClass.getResourceAsStream("/test.odt"), None) shouldBe MimeType(
        "application/vnd.oasis.opendocument.text")
    }

    "properly detect MS-Word file" in {
      tikaMimeTypeDetector.detect(this.getClass.getResourceAsStream("/test.docx"), None) shouldBe MimeType(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    }

  }
}
