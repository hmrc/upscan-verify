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

import java.io.{FilterInputStream, InputStream}
import java.security.MessageDigest

import org.apache.commons.codec.binary.Hex

class MessageDigestComputingInputStream(inputStream: InputStream, digestType: String)
    extends FilterInputStream(inputStream) {

  val digest = MessageDigest.getInstance(digestType)

  override def read: Int = {
    val result = this.in.read
    if (result != -1) {
      digest.update(result.toByte)
    }
    result
  }

  override def read(buffer: Array[Byte], start: Int, end: Int): Int = {
    val count = this.in.read(buffer, start, end)
    if (count != -1) {
      digest.update(buffer, start, count)
    }
    count
  }

  override def skip(count: Long): Long = {
    val buf            = new Array[Byte](512)
    var totalRetrieved = 0L
    var eof            = false
    while (totalRetrieved < count && !eof) {
      val toRetrieve   = count - totalRetrieved
      val nowRetrieved = read(buf, 0, if (toRetrieve < buf.length.toLong) toRetrieve.toInt else buf.length).toLong
      if (nowRetrieved == -1L) {
        eof = true
      } else {
        totalRetrieved += toRetrieve
      }
    }
    totalRetrieved
  }

  override def reset() = {
    this.in.reset()
    digest.reset()
  }

  def getChecksum(): String =
    Hex.encodeHexString(digest.digest())
}
