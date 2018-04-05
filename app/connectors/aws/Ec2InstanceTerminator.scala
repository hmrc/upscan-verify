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

import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.ec2.model.TerminateInstancesRequest
import com.amazonaws.util.EC2MetadataUtils
import javax.inject.Inject
import play.api.Logger
import services.InstanceTerminator

import scala.collection.JavaConverters._
import scala.concurrent.Future
class Ec2InstanceTerminator @Inject()(amazonEC2: AmazonEC2) extends InstanceTerminator {

  override def terminate() = {
    val maybeInstanceId: Option[String] = Option(EC2MetadataUtils.getInstanceId)

    maybeInstanceId match {
      case Some(instanceId) =>
        amazonEC2.terminateInstances(new TerminateInstancesRequest(List(instanceId).asJava))
      case None =>
        Logger.warn("Not running on AWS. Not terminating the instance")
    }

    Future.successful(())
  }
}
