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

package config

import com.typesafe.config.ConfigFactory
import org.scalatest.{Assertions, GivenWhenThen}
import play.api.Configuration
import test.UnitSpec

class PlayBasedServiceConfigurationSpec
  extends UnitSpec
     with Assertions
     with GivenWhenThen:

  "PlayBasedServiceConfiguration" should:
    "parse consuming service configuration" in:
      val config = parseConfig(
        """
          |fileTypesFilter.allowedMimeTypes = [
          |  { user-agent: "test-user-agent-one", mime-types: "pdf,jpg" },
          |  { user-agent: "test-user-agent-two", mime-types: "docx,odt" },
          |  { user-agent: "test-user-agent-three", mime-types: "png" }
          |]
          |""".stripMargin)

      config.allowedMimeTypes("test-user-agent-one")   shouldBe Some(List("pdf", "jpg"))
      config.allowedMimeTypes("test-user-agent-two")   shouldBe Some(List("docx", "odt"))
      config.allowedMimeTypes("test-user-agent-three") shouldBe Some(List("png"))

    "parse empty consuming service configuration" in:
      val config = parseConfig("fileTypesFilter.allowedMimeTypes = []")
      config.allowedMimeTypes("anything") shouldBe None

    "parse missing consuming service configuration" in:
      parseConfig("").allowedMimeTypes("anything") shouldBe None

    /*
     * This is a change of behaviour introduced during the Play 2.6 upgrade.
     * Previously we would have thrown the exception:
     * 'Configuration error[String: 1: Configuration key 'fileTypesFilter.allowedMimeTypes' is set to null but expected LIST]'.
     * We now interpret 'null' the same as 'empty' or 'missing'.
     */
    "parse null consuming service configuration" in:
      parseConfig("fileTypesFilter.allowedMimeTypes = null").allowedMimeTypes("anything") shouldBe None

    "parse defaultAllowedMimeTypes if specified" in:
      val config = parseConfig(
        """
          |fileTypesFilter.defaultAllowedMimeTypes = "docx,odt"
          |""".stripMargin
      )

      config.defaultAllowedMimeTypes shouldBe List("docx", "odt")

    "parse empty defaultAllowedMimeTypes" in:
      val config = parseConfig("")
      config.defaultAllowedMimeTypes shouldBe Nil

    "throw an error for badly formatted consuming service configuration (upon initialisation of the class)" in:
      val config = Configuration(ConfigFactory.parseString(
        """
          |fileTypesFilter.allowedMimeTypes = [
          |  { user-agent: "test-user-agent-one", mime-types: "pdf,jpg" },
          |  { user-agent: "test-user-agent-two", mime-types: "docx,odt" },
          |  { something-else: "Just a badly configured object" }
          |]
          |""".stripMargin
      ))

      val result =
        intercept[Exception]:
          new PlayBasedServiceConfiguration(config)

      result.getMessage shouldBe "Configuration key not correctly configured: fileTypesFilter.allowedMimeTypes, " +
        "errors: Could not parse config object for services configuration: Map(something-else -> Just a badly configured object)"

  def parseConfig(s: String) =
    new PlayBasedServiceConfiguration(Configuration(ConfigFactory.parseString(s)))
