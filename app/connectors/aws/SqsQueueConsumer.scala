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

package connectors.aws

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

import java.time.Clock

import javax.inject.Inject
import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.model.{DeleteMessageRequest, ReceiveMessageRequest, ReceiveMessageResult}
import config.ServiceConfiguration
import model.Message
import play.api.Logger
import services.QueueConsumer

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

class SqsQueueConsumer @Inject()(sqsClient: AmazonSQS, configuration: ServiceConfiguration, clock: Clock)(
  implicit ec: ExecutionContext)
    extends QueueConsumer {

  override def poll(): Future[List[Message]] = {
    val receiveMessageRequest = new ReceiveMessageRequest(configuration.inboundQueueUrl)
      .withMaxNumberOfMessages(configuration.processingBatchSize)
      .withWaitTimeSeconds(20)
      .withAttributeNames("All")

    val receiveMessageResult: Future[ReceiveMessageResult] =
      Future(sqsClient.receiveMessage(receiveMessageRequest))

    receiveMessageResult map { result =>
      val receivedAt = clock.instant()

      result.getMessages.asScala.toList.map { sqsMessage =>
        if (Logger.isDebugEnabled) {
          Logger.debug(
            s"Received message with id: [${sqsMessage.getMessageId}] and receiptHandle: [${sqsMessage.getReceiptHandle}], message details:\n "
              + sqsMessage.toString)
        }

        Message(sqsMessage.getMessageId, sqsMessage.getBody, sqsMessage.getReceiptHandle, receivedAt)
      }
    }
  }

  override def confirm(message: Message): Future[Unit] = {
    val deleteMessageRequest = new DeleteMessageRequest(configuration.inboundQueueUrl, message.receiptHandle)
    Future {
      sqsClient.deleteMessage(deleteMessageRequest)
      Logger.debug(
        s"Deleted message from Queue: [${configuration.inboundQueueUrl}], for receiptHandle: [${message.receiptHandle}].")
    }
  }
}
