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

import org.apache.commons.codec.binary.Hex

import java.io.{FilterInputStream, InputStream}
import java.security.MessageDigest

trait ChecksumComputingInputStreamFactory:
  def create(source: InputStream): InputStream with ChecksumSource

object SHA256ChecksumComputingInputStreamFactory extends ChecksumComputingInputStreamFactory:
  def create(source: InputStream): InputStream with ChecksumSource =
    MessageDigestComputingInputStream(source, "SHA-256")

trait ChecksumSource:
  def getChecksum(): String

class MessageDigestComputingInputStream(
  inputStream: InputStream,
  digestType : String
) extends FilterInputStream(inputStream)
     with ChecksumSource:

  val digest = MessageDigest.getInstance(digestType)

  override def read: Int =
    val result = this.in.read
    if result != -1 then
      digest.update(result.toByte)
    result

  override def read(buffer: Array[Byte], start: Int, end: Int): Int =
    val count = this.in.read(buffer, start, end)
    if count != -1 then
      digest.update(buffer, start, count)
    count

  override def skip(count: Long): Long =
    val readBuffer = new Array[Byte](512)

    @scala.annotation.tailrec
    def skipByReading(skippedSoFar: Long): Long =
      val remaining = count - skippedSoFar
      if remaining > 0 then
        val nowRetrieved = read(readBuffer, 0, Math.min(remaining, readBuffer.length).toInt)
        if nowRetrieved == -1L then
          skippedSoFar
        else
          skipByReading(skippedSoFar + nowRetrieved)
      else
        skippedSoFar

    skipByReading(0)

  override def reset() =
    this.in.reset()
    digest.reset()

  def getChecksum(): String =
    Hex.encodeHexString(digest.digest())
