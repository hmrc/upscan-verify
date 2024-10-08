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

import org.apache.tika.metadata.{Metadata, TikaCoreProperties}
import org.apache.tika.mime.MediaType
import org.scalatest.GivenWhenThen
import uk.gov.hmrc.upscanverify.service.tika.detectors.XmlDetector
import uk.gov.hmrc.upscanverify.test.UnitSpec

import java.io.{BufferedReader, ByteArrayInputStream, InputStream, InputStreamReader}

class XmlDetectorSpec
  extends UnitSpec
     with GivenWhenThen:

  "XMLDetector" should:
    val xmlDetector = XmlDetector()

    "detect XML file with XML declaration as XML" in:
      val xml = """<?xml version="1.0" encoding="UTF-8"?><test></test>"""
      val is  = ByteArrayInputStream(xml.getBytes)
      xmlDetector.detect(is, Metadata()) shouldBe MediaType.APPLICATION_XML

    "detect XML file without XML declaration as XML" in:
      val xml = """<test></test>"""
      val is  = ByteArrayInputStream(xml.getBytes)
      xmlDetector.detect(is, Metadata()) shouldBe MediaType.APPLICATION_XML

    "detect non-XML file as octet stream" in:
      val xml = """NOT XML"""
      val is  = ByteArrayInputStream(xml.getBytes)
      xmlDetector.detect(is, Metadata()) shouldBe MediaType.OCTET_STREAM

    "detect file with .html extension as octet stream" in:
      val metadata = Metadata()
      metadata.add(TikaCoreProperties.RESOURCE_NAME_KEY, "test.html")
      xmlDetector.detect(ByteArrayInputStream("""<html></html>""".getBytes), metadata) shouldBe MediaType.OCTET_STREAM

    "detect file with .htm extension as octet stream" in:
      val metadata = Metadata()
      metadata.add(TikaCoreProperties.RESOURCE_NAME_KEY, "test.htm")
      xmlDetector.detect(ByteArrayInputStream("""<html></html>""".getBytes), metadata) shouldBe MediaType.OCTET_STREAM

    "reset input stream after processing" in:
      val xml = """<test></test>"""
      val is  = ByteArrayInputStream(xml.getBytes)

      When("Detector analyzed the file")

      xmlDetector.detect(is, Metadata())

      Then("it still should be possible to read the file from the beginning")

      val retrievedFileContent = readAll(is)
      retrievedFileContent shouldBe xml

  private def readAll(is : InputStream): String =
    val reader = BufferedReader(InputStreamReader(is))
    LazyList.continually(reader.readLine()).takeWhile(_ != null).mkString("\n")
