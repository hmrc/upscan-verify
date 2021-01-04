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

package uk.gov.hmrc.clamav

import uk.gov.hmrc.clamav.model._

import java.io.{ByteArrayInputStream, InputStream}
import scala.concurrent.Future

trait ClamAntiVirus {
  def scanInputStream(inputStream: InputStream, length: Int): Future[ScanningResult]

  def scanBytes(bytes: Array[Byte]): Future[ScanningResult] =
    scanInputStream(new ByteArrayInputStream(bytes), bytes.length)

  def stats: Future[ClamAvStats]
}
