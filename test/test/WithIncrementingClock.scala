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

package test

import java.time.{Clock, Duration, Instant, ZoneId}

import org.scalatest.{BeforeAndAfterEach, Suite}

// Clock impl that increments the time by "tickIncrement" for each call to "Clock.instant()".
trait WithIncrementingClock extends BeforeAndAfterEach:
  this: Suite =>

  lazy val clockStart   : Instant = Instant.now()
  lazy val zoneId       : ZoneId = ZoneId.systemDefault()
  lazy val tickIncrement: Duration = Duration.ofSeconds(1)

  lazy val clock: IncrementingClock =
    IncrementingClock(Clock.fixed(clockStart, zoneId).millis(), tickIncrement)

  override protected def beforeEach(): Unit =
    clock.reset()
    super.beforeEach()
