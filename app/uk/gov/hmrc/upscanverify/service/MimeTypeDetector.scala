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

package uk.gov.hmrc.upscanverify.service

import java.io.InputStream

case class MimeType(value: String) extends AnyVal

trait MimeTypeDetector:
  def detect(inputStream: InputStream, fileName: Option[String]): DetectedMimeType

enum DetectedMimeType:
  case Detected   (val value: MimeType) extends DetectedMimeType
  case EmptyLength(val value: MimeType) extends DetectedMimeType
  case Failed     (message: String)     extends DetectedMimeType
