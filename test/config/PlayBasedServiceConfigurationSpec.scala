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

package config

import com.typesafe.config.ConfigFactory
import model.{AllowedMimeTypes, ConsumingServicesConfiguration}
import org.scalatest.{Assertions, GivenWhenThen, Matchers}
import play.api.Configuration
import uk.gov.hmrc.play.test.UnitSpec

class PlayBasedServiceConfigurationSpec extends UnitSpec with Matchers with Assertions with GivenWhenThen {
  "PlayBasedServiceConfiguration" should {
    "parse consuming service configuration" in {
      Given("a Play configuration file with configured services")
      val configuration = Configuration(ConfigFactory.load("test-one.conf"))

      When("the configuration is parsed")
      val playBasedServiceConfiguration = new PlayBasedServiceConfiguration(configuration)

      Then("the consumer service configuration should be configured correctly")
      val expectedConsumerConfiguration = ConsumingServicesConfiguration(
        List(
          AllowedMimeTypes("test-user-agent-one", List("pdf", "jpg")),
          AllowedMimeTypes("test-user-agent-two", List("docx", "odt")),
          AllowedMimeTypes("test-user-agent-three", List("png"))
        ))

      playBasedServiceConfiguration.consumingServicesConfiguration.serviceConfigurations shouldEqual
        expectedConsumerConfiguration.serviceConfigurations
    }

    "parse empty consuming service configuration" in {
      Given("a Play configuration file with no configured services")
      val configuration = Configuration(ConfigFactory.load("test-two.conf"))

      When("the configuration is parsed")
      val playBasedServiceConfiguration = new PlayBasedServiceConfiguration(configuration)

      Then("the consumer service configuration should be an empty set")
      val expectedConsumerConfiguration = ConsumingServicesConfiguration(List.empty)
      playBasedServiceConfiguration.consumingServicesConfiguration.serviceConfigurations shouldEqual
        expectedConsumerConfiguration.serviceConfigurations
    }
  }

  "throw an error for badly formatted consuming service configuration" in {
    Given("a Play configuration file with a badly formatted configured service")
    val configuration = Configuration(ConfigFactory.load("test-three.conf"))

    When("the configuration is parsed")
    val playBasedServiceConfiguration = new PlayBasedServiceConfiguration(configuration)

    Then("an Exception with details of the problem should be thrown")
    val result = intercept[Exception] {
      playBasedServiceConfiguration.consumingServicesConfiguration.serviceConfigurations
    }
    result.getMessage shouldBe "Configuration key not correctly configured: fileTypesFilter.allowedMimeTypes, " +
      "errors: Could not parse config object for services configuration: Map(something-else -> Just a badly configured object)"

  }
}
