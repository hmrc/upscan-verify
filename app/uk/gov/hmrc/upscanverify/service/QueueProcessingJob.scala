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

import com.codahale.metrics.MetricRegistry
import play.api.Logging
import uk.gov.hmrc.upscanverify.model.Message
import uk.gov.hmrc.upscanverify.util.logging.LoggingUtils

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class QueueProcessingJob @Inject()(
  consumer        : QueueConsumer,
  messageProcessor: MessageProcessor,
  metricRegistry  : MetricRegistry
)(using
  ExecutionContext
) extends PollingJob
     with Logging:

  def run(): Future[Unit] =
    val outcomes =
      for
        messages        <- consumer.poll()
        messageOutcomes <- Future.traverse(messages)(processMessage)
      yield messageOutcomes

    outcomes.map(_ => ())

  private def processMessage(message: Message): Future[Unit] =
    messageProcessor.processMessage(message)
      .flatMap: context =>
        LoggingUtils
          .withMdc(context):
            for
              _ <- consumer.confirm(message)
              _ =  metricRegistry.meter("verifyThroughput").mark()
            yield ()
          .recover:
            case exception =>
              logger.error(s"Failed to process message '${message.id}' for Key=[${context.fileReference}], cause ${exception.getMessage}", exception)
      .recover:
        case exception =>
          logger.error(s"Failed to process message '${message.id}', cause ${exception.getMessage}", exception)
