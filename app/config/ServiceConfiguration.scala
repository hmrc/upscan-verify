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

import javax.inject.Inject

import com.typesafe.config.ConfigObject
import play.api.Configuration

import scala.collection.JavaConversions._
import scala.concurrent.duration._

trait ServiceConfiguration {
  def quarantineBucket: String

  def retryInterval: FiniteDuration

  def inboundQueueUrl: String

  def accessKeyId: String

  def secretAccessKey: String

  def sessionToken: Option[String]

  def outboundBucket: String

  def awsRegion: String

  def useContainerCredentials: Boolean

  def processingBatchSize: Int

  def allowedMimeTypes(serviceName: String): Option[List[String]]

  def defaultAllowedMimeTypes: List[String]

}

class PlayBasedServiceConfiguration @Inject()(configuration: Configuration) extends ServiceConfiguration {

  override def inboundQueueUrl: String =
    getRequired(configuration.getString(_), "aws.sqs.queue.inbound")

  override def awsRegion = getRequired(configuration.getString(_), "aws.s3.region")

  override def useContainerCredentials = configuration.getBoolean("aws.useContainerCredentials").getOrElse(true)

  override def accessKeyId = getRequired(configuration.getString(_), "aws.accessKeyId")

  override def secretAccessKey = getRequired(configuration.getString(_), "aws.secretAccessKey")

  override def sessionToken = configuration.getString("aws.sessionToken")

  override def retryInterval = getRequired(configuration.getMilliseconds, "aws.sqs.retry.interval").milliseconds

  def getRequired[T](function: String => Option[T], key: String) =
    function(key).getOrElse(throw new IllegalStateException(s"Configuration key not found: $key"))

  override def outboundBucket = getRequired(configuration.getString(_), "aws.s3.bucket.outbound")

  override def quarantineBucket: String = getRequired(configuration.getString(_), "aws.s3.bucket.quarantine")

  override def processingBatchSize: Int = getRequired(configuration.getInt, "processingBatchSize")

  override def allowedMimeTypes(serviceName: String): Option[List[String]] = {
    consumingServicesConfiguration.serviceConfigurations
      .find(_.serviceName == serviceName)
      .map(_.allowedMimeTypes)
  }

  private case class AllowedMimeTypes(serviceName: String, allowedMimeTypes: List[String])
  private case class ConsumingServicesConfiguration(serviceConfigurations: List[AllowedMimeTypes])

  private val consumingServicesConfiguration: ConsumingServicesConfiguration = {

    def toPerServiceConfiguration(consumingServiceConfig: ConfigObject): Either[String, AllowedMimeTypes] = {
      val serviceAsMap = consumingServiceConfig.unwrapped.toMap
      (serviceAsMap.get("user-agent"), serviceAsMap.get("mime-types")) match {
        case (Some(service: String), Some(mimeTypes: String)) =>
          Right(AllowedMimeTypes(service, mimeTypes.split(",").toList))
        case _ => Left(s"Could not parse config object for services configuration: $serviceAsMap")
      }
    }

    val key = "fileTypesFilter.allowedMimeTypes"
    val servicesConfigArray = configuration
      .getObjectList(key)
      .map(_.toList)
      .getOrElse(Nil)

    val serviceAllowedMimeTypes = servicesConfigArray.map(toPerServiceConfiguration)

    serviceAllowedMimeTypes.collect({ case Left(error) => error }) match {
      case Nil =>
        val allowed = serviceAllowedMimeTypes.collect({ case Right(allowedMimeTypes) => allowedMimeTypes })
        ConsumingServicesConfiguration(allowed)
      case errors =>
        throw new Exception(s"Configuration key not correctly configured: $key, errors: ${errors.mkString(", ")}")
    }
  }

  override def defaultAllowedMimeTypes: List[String] =
    configuration.getString("fileTypesFilter.defaultAllowedMimeTypes")
      .map(_.split(",").toList)
      .getOrElse(Nil)
}
