/*
 * Copyright 2018 HM Revenue & Customs
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

import javax.inject.Inject

import akka.actor.{Actor, ActorSystem, PoisonPill, Props}
import akka.event.Logging
import config.ServiceConfiguration
import play.api.inject.ApplicationLifecycle
import services.ContinousPollingActor.Poll

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

trait PollingJob {
  def run(): Future[Unit]
}

class ContinousPoller @Inject()(job: PollingJob, serviceConfiguration: ServiceConfiguration)(
  implicit actorSystem: ActorSystem,
  applicationLifecycle: ApplicationLifecycle) {

  private val pollingActor =
    actorSystem.actorOf(ContinousPollingActor(job, serviceConfiguration.retryInterval), "Poller")
  pollingActor ! Poll

  applicationLifecycle.addStopHook { () =>
    pollingActor ! PoisonPill
    Future.successful(())
  }

}

class ContinousPollingActor(job: PollingJob, retryInterval: FiniteDuration) extends Actor {

  import context.dispatcher

  val log = Logging(context.system, this)

  override def receive: Receive = {

    case Poll =>
      log.debug("Polling")
      /*
      job.run() andThen {
        case Success(r) =>
          self ! Poll
        case Failure(f) =>
          log.error(f, "Polling failed")
          context.system.scheduler.scheduleOnce(retryInterval, self, Poll)
      }*/
  }

}

object ContinousPollingActor {

  def apply(orchestrator: PollingJob, retryInterval: FiniteDuration): Props =
    Props(new ContinousPollingActor(orchestrator, retryInterval))

  case object Poll
}
