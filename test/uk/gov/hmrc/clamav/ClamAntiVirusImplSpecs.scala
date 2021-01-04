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

import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import uk.gov.hmrc.clamav.model.ClamAvStats

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ClamAntiVirusImplSpecs extends AnyWordSpecLike with Matchers with ScalaFutures with IdiomaticMockito {

  "stats" must {
    "parse response from clamav" in new Setup {
      mockClamAvIO.sendCommand("zSTATS\u0000") returns Future.unit

      mockClamAvIO.readResponse returns Future.successful(
        """
          |POOLS: 1
          |
          |STATE: VALID PRIMARY
          |THREADS: live 4  idle 8 max 12 idle-timeout 30
          |QUEUE: 5 items
          |	STATS 0.000110
          |
          |MEMSTATS: heap 4.422M mmap 0.129M used 3.238M free 1.184M releasable 0.125M pools 1 pools_used 1189.622M pools_total 1189.667M
          |END """.stripMargin)

      mockClamAvIO.withSocket[ClamAvStats](*) answers { (f: Connection => Future[ClamAvStats]) =>
        f(mockConnection)
      }

      clamAntiVirus.stats.futureValue mustBe ClamAvStats(5, 8, 4, 12)
    }

    "return error if response from clamav is unpareseable" in new Setup {
      mockClamAvIO.sendCommand("zSTATS\u0000") returns Future.unit

      mockClamAvIO.readResponse returns Future.successful("an unparseable response")

      mockClamAvIO.withSocket[ClamAvStats](*) answers { (f: Connection => Future[ClamAvStats]) =>
        f(mockConnection)
      }

      val thrown = the[RuntimeException] thrownBy clamAntiVirus.stats.futureValue

      thrown.getMessage mustBe "The future returned an exception of type: java.lang.RuntimeException, with message: Unparseable stats response from ClamAV: [an unparseable response]."
    }
  }

  trait Setup {
    val mockClamAvIO                        = mock[ClamAvIO]
    implicit val mockConnection: Connection = mock[Connection]
    val clamAntiVirus                       = new ClamAntiVirusImpl(mockClamAvIO)
  }
}
