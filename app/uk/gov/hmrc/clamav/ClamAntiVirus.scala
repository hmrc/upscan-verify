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

package uk.gov.hmrc.clamav

import uk.gov.hmrc.clamav.model._
import uk.gov.hmrc.http.logging.LoggingDetails

import java.io.{ByteArrayInputStream, InputStream}
import scala.concurrent.{ExecutionContext, Future}

trait ClamAntiVirus:
  def sendAndCheck(
    objectKey  : String,
    inputStream: InputStream,
    length     : Int
  )(using
    LoggingDetails,
    ExecutionContext
  ): Future[ScanningResult]

  def sendAndCheck(
    objectKey: String,
    bytes    : Array[Byte]
  )(using
    LoggingDetails,
    ExecutionContext
  ): Future[ScanningResult] =
    sendAndCheck(objectKey, ByteArrayInputStream(bytes), bytes.length)
