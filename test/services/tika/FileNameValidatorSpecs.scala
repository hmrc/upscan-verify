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

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.Configuration
import services.MimeType

import java.util.UUID.randomUUID

class FileNameValidatorSpecs extends AnyWordSpecLike with Matchers {

  "checkIfAllowed" must {
    "return Unit if no mime type is defined" in {
      validator(Map.empty).validate(randomMimeType(), randomFilename().toString) mustBe Right(())
    }

    "return Unit if mime type under test is not defined" in {
      validator(Map(randomMimeType() -> Set(randomString())))
        .validate(randomMimeType(), randomFilename().toString) mustBe Right(())
    }

    "return Unit if filename doesn't have any extension" in {
      val mediaType = randomMimeType()
      validator(Map(mediaType -> Set(randomString())))
        .validate(mediaType, randomString()) mustBe Right(())
    }

    "return Unit if mime type is defined for the filename extension" in {
      val mediaType = randomMimeType()
      val filename  = randomFilename()
      validator(Map(mediaType -> Set(randomString(), randomString(), filename.extension)))
        .validate(mediaType, filename.toString) mustBe Right(())
    }

    "return file's extension if mime type is not defined for the filename extension" in {
      val mediaType = randomMimeType()
      val filename  = randomFilename()
      validator(Map(mediaType -> Set(randomString(), randomString(), randomString())))
        .validate(mediaType, filename.toString) mustBe Left(filename.extension)
    }
  }

  private def randomMimeType() = MimeType(randomString())
  private def randomString()   = randomUUID().toString
  private def randomFilename() = Filename()

  private case class Filename(basename: String = randomString(), extension: String = randomString()) {
    override def toString = s"$basename.$extension"
  }

  private def validator(map: Map[MimeType, Set[String]]) =
    new FileNameValidator(Configuration.from(Map("allowedExtensions" -> map.map(kv => kv._1.value -> kv._2))))
}
