/*
 * Copyright 2020 HM Revenue & Customs
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

import java.io.ByteArrayInputStream

import org.apache.commons.codec.digest.DigestUtils
import org.scalatest.GivenWhenThen
import uk.gov.hmrc.play.test.UnitSpec

import scala.collection.mutable

class MessageDigestComputingInputStreamSpec extends UnitSpec with GivenWhenThen {

  val bytes = "0123456789".getBytes

  "MessageDigestComputingInputStream" should {
    "allow to read the data from the source stream" in {
      val is = new MessageDigestComputingInputStream(new ByteArrayInputStream(bytes), "SHA-256")

      var b      = is.read
      val buffer = mutable.Buffer[Byte]()
      while (b != -1) {
        buffer.append(b.toByte)
        b = is.read
      }

      buffer.toArray should be(bytes)
    }

    "allow to bulk read data from the source stream" in {

      val is = new MessageDigestComputingInputStream(new ByteArrayInputStream(bytes), "SHA-256")

      val buffer = new Array[Byte](100)

      is.read(buffer, 0, 5)  shouldBe 5
      is.read(buffer, 5, 20) shouldBe 5
      is.read(buffer, 5, 10) shouldBe -1

      buffer.slice(0, bytes.length) should be(bytes)
    }

    "allow to reset the stream in source stream allows to do that" in {

      val is = new MessageDigestComputingInputStream(new ByteArrayInputStream(bytes), "SHA-256")

      val buffer = new Array[Byte](10)

      is.read(buffer, 0, 5)
      is.reset()

      is.read(buffer, 0, 10) shouldBe 10

      buffer.slice(0, bytes.length) should be(bytes)

    }

    "allow to skip some part of the stream" in {

      val is = new MessageDigestComputingInputStream(new ByteArrayInputStream(bytes), "SHA-256")

      val buffer = new Array[Byte](5)

      is.skip(5)
      is.read(buffer, 0, 5) shouldBe 5

      buffer should be(bytes.slice(5, 10))
    }

    "properly compute checksum when reading byte by byte" in {
      val is = new MessageDigestComputingInputStream(new ByteArrayInputStream(bytes), "SHA-256")

      var b      = is.read
      val buffer = mutable.Buffer[Byte]()
      while (b != -1) {
        buffer.append(b.toByte)
        b = is.read
      }

      is.getChecksum() shouldBe DigestUtils.sha256Hex(bytes)
    }

    "properly compute checksum when bulk reading" in {
      val is = new MessageDigestComputingInputStream(new ByteArrayInputStream(bytes), "SHA-256")

      val buffer = new Array[Byte](100)

      is.read(buffer, 0, 5)
      is.read(buffer, 5, 20)

      is.getChecksum() shouldBe DigestUtils.sha256Hex(bytes)
    }

    "properly compute checksum when skipping and reading" in {
      val is = new MessageDigestComputingInputStream(new ByteArrayInputStream(bytes), "SHA-256")

      val buffer = new Array[Byte](5)

      is.skip(5)
      is.read(buffer, 0, 5)

      is.getChecksum() shouldBe DigestUtils.sha256Hex(bytes)
    }

    "should honour resetting when computing checksum" in {
      val is = new MessageDigestComputingInputStream(new ByteArrayInputStream(bytes), "SHA-256")

      val buffer = new Array[Byte](10)

      is.read(buffer, 0, 5)
      is.reset()

      is.read(buffer, 0, 10)

      is.getChecksum() shouldBe DigestUtils.sha256Hex(bytes)
    }

    //TODO What about getting checksum when stream wasn't fully read
  }

}
