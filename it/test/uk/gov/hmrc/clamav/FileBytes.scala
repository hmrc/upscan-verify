/*
 * Copyright 2022 HM Revenue & Customs
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

package uk.gov.hmrc.clamav

import java.io.InputStream

private [clamav] object FileBytes {
  def apply(filename: String): Array[Byte] =
    read(Option(getClass.getResourceAsStream(filename)).orElse(
      throw Exception(s"Could not open stream to: $filename")).get)

  def read(stream: InputStream): Array[Byte] =
    Iterator.continually(stream.read).takeWhile(_ != -1).take(1000).map(_.toByte).toArray
}
