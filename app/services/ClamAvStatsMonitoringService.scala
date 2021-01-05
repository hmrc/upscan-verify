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

import akka.actor.{ActorSystem, Scheduler}
import com.codahale.metrics.{Gauge, MetricRegistry}
import com.kenshoo.play.metrics.MetricsDisabledException
import play.api.Logger
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.clamav.ClamAntiVirusFactory
import uk.gov.hmrc.clamav.config.ClamAvConfig
import uk.gov.hmrc.clamav.model.ClamAvStats

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class ClamAvStatsMonitoringService(
  clamClientFactory: ClamAntiVirusFactory,
  metricsRegistry: MetricRegistry,
  clamAvConfig: ClamAvConfig)(
  implicit ec: ExecutionContext,
  scheduler: Scheduler,
  applicationLifecycle: ApplicationLifecycle) {

  @Inject()
  def this(clamAvConfig: ClamAvConfig, clamClientFactory: ClamAntiVirusFactory, metricsRegistry: MetricRegistry)(
    implicit ec: ExecutionContext,
    system: ActorSystem,
    applicationLifecycle: ApplicationLifecycle) =
    this(clamClientFactory, metricsRegistry, clamAvConfig)(ec, system.scheduler, applicationLifecycle)

  private val antivirusClient = clamClientFactory.getClient()
  private val logger          = Logger(getClass)

  private val clamAvQueueLengthMetricKey = "clamAv.queueLength"
  private val clamAvIdleThreadsMetricKey = "clamAv.idleThreads"
  private val clamAvLiveThreadsMetricKey = "clamAv.liveThreads"
  private val clamAvMaxThreadsMetricKey  = "clamAv.maxThreads"

  val cancellable = scheduler.schedule(clamAvConfig.statsPollStartDelay, clamAvConfig.statsPollInterval) {
    antivirusClient.stats.flatMap(updateClamAvMetrics).recover {
      case ex =>
        logger.error("Fetching clamav stats failed", ex)
    }
  }

  applicationLifecycle.addStopHook { () =>
    logger.info("Stopping ClamAvStatsMonitoringService scheduler")
    cancellable.cancel()
    Future.unit
  }

  private def updateClamAvMetrics(clamAvStats: ClamAvStats): Future[Unit] =
    Future.fromTry(Try {
      updateGauge(clamAvQueueLengthMetricKey, clamAvStats.queueLength)
      updateGauge(clamAvIdleThreadsMetricKey, clamAvStats.idleThreads)
      updateGauge(clamAvLiveThreadsMetricKey, clamAvStats.liveThreads)
      updateGauge(clamAvMaxThreadsMetricKey, clamAvStats.maxThreads)
    }.recover {
      case _: MetricsDisabledException =>
    })

  private def updateGauge[T](gaugeName: String, value: T): Unit = {
    metricsRegistry.remove(gaugeName)
    metricsRegistry.register(gaugeName, new Gauge[T] {
      override val getValue: T = value
    })
  }

}
