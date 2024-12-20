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

package uk.gov.hmrc.upscanverify.config

import play.api.Configuration
import uk.gov.hmrc.upscanverify.test.UnitSpec
import scala.concurrent.duration.DurationInt

class PlayClamAvConfigSpec extends UnitSpec:

  "PlayClamAvConfig" should:
    "load the configuration values from the play application config if all values are present" in:
      val configuration: Configuration =
        Configuration.from(Map(
          "clam.antivirus.host"    -> "avscan",
          "clam.antivirus.port"    -> 3310,
          "clam.antivirus.timeout" -> "5.seconds"
        ))

      val clamAvConfig = PlayClamAvConfig(configuration)
      clamAvConfig.host    shouldBe "avscan"
      clamAvConfig.port    shouldBe 3310
      clamAvConfig.timeout shouldBe 5.seconds

    "throw an exception if there is no clam host" in:
      val configuration: Configuration =
        Configuration.from(Map(
          "clam.antivirus.port"    -> 3310,
          "clam.antivirus.timeout" -> "5.seconds"
        ))

      intercept[Exception]:
        PlayClamAvConfig(configuration)

    "throw an exception if there is no clam port" in:
      val configuration: Configuration =
        Configuration.from(Map(
          "clam.antivirus.host"    -> "avscan",
          "clam.antivirus.timeout" -> "5.seconds"
        ))

      intercept[Exception]:
        PlayClamAvConfig(configuration)

    "throw an exception if there is no clam timeeout" in:
      val configuration: Configuration =
        Configuration.from(Map(
          "clam.antivirus.host" -> "avscan",
          "clam.antivirus.port" -> 3310
        ))

      intercept[Exception]:
        PlayClamAvConfig(configuration)
