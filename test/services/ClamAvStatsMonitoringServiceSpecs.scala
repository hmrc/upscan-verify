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

package services

import com.codahale.metrics.{MetricFilter, MetricRegistry}
import com.miguno.akka.testing.VirtualTime
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.OptionValues
import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.scalatest.matchers.must.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.inject.DefaultApplicationLifecycle
import uk.gov.hmrc.clamav.config.ClamAvConfig
import uk.gov.hmrc.clamav.model.ClamAvStats
import uk.gov.hmrc.clamav.{ClamAntiVirus, ClamAntiVirusFactory}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.{FiniteDuration, _}

class ClamAvStatsMonitoringServiceSpecs
    extends AnyWordSpecLike
    with Matchers
    with IdiomaticMockito
    with Eventually
    with IntegrationPatience
    with OptionValues
    with TableDrivenPropertyChecks {

  "ClamAvStatsMonitoringService" must {

    val metrics = Table[String, ClamAvStats => Int](
      ("metricName", "statsForMetric"),
      ("clamAv.queueLength", _.queueLength),
      ("clamAv.idleThreads",_.idleThreads),
      ("clamAv.liveThreads",_.liveThreads),
      ("clamAv.maxThreads",_.maxThreads)
    )

    val clamAvStatsPolledValues = Table(
      "stats",
      ClamAvStats(0, 11, 1, 12),
      ClamAvStats(0, 7, 5, 12),
      ClamAvStats(0, 1, 11, 12),
      ClamAvStats(3, 0, 12, 12),
    )

    "schedule pulling clamav stats and update gauges" in {
      forAll(metrics) { (metricName, statsForMetric) =>
        new Setup {
          getGauge(metricName) mustBe empty
          forAll(clamAvStatsPolledValues) { stats =>
            mockClamAvClient.stats returns Future.successful(stats)
            time.advance(clamAvConfig.statsPollInterval)
            eventually {
              getGauge(metricName).value mustBe statsForMetric(stats)
            }
          }
        }
      }
    }
  }

  trait Setup {
    private val mockClamAvFactory = mock[ClamAntiVirusFactory]
    private val metricRegistry = new MetricRegistry()

    val mockClamAvClient = mock[ClamAntiVirus]
    mockClamAvFactory.getClient() returns mockClamAvClient

    val clamAvConfig = new ClamAvConfig {
      override lazy val host: String                   = ???
      override lazy val port: Int                      = ???
      override lazy val timeout: Int                   = ???
      override val statsPollInterval: FiniteDuration   = 1.minute
      override val statsPollStartDelay: FiniteDuration = 1.minute
    }

    private implicit val applicationLifeCycle = new DefaultApplicationLifecycle()

    val time               = new VirtualTime
    implicit val scheduler = time.scheduler

    val service = new ClamAvStatsMonitoringService(mockClamAvFactory, metricRegistry, clamAvConfig)

    def getGauge(name: String): Option[Any] =
      metricRegistry.getGauges(MetricFilter.endsWith(name)).asScala.headOption.map(_._2.getValue)
  }

}
