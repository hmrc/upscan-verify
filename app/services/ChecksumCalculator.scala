/*
 * Copyright 2018 HM Revenue & Customs
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
import java.security.MessageDigest

import javax.inject.Inject
import model.S3ObjectLocation
import org.apache.commons.codec.digest.DigestUtils

import scala.concurrent.{ExecutionContext, Future}

trait ChecksumCalculator {
  def calculateChecksum(objectLocation: S3ObjectLocation): Future[String]
}

class SHA256ChecksumCalculator @Inject()(fileManager: FileManager)(implicit ec: ExecutionContext)
    extends ChecksumCalculator {
  override def calculateChecksum(objectLocation: S3ObjectLocation): Future[String] =
    fileManager.withObjectContent(objectLocation) { content =>
      Future(computeChecksum(content.inputStream))
    }

  private def computeChecksum(objectContent: InputStream): String =
    DigestUtils.sha256Hex(objectContent)
}
