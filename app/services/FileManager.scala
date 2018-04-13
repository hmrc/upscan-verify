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

import java.io.InputStream

import model.S3ObjectLocation

import scala.concurrent.Future

case class ObjectMetadata(items: Map[String, String])

case class ObjectContent(inputStream: InputStream, length: Long)

trait FileManager {
  def copyToOutboundBucket(file: S3ObjectLocation): Future[Unit]
  def writeToQuarantineBucket(file: S3ObjectLocation, content: InputStream, metadata: ObjectMetadata): Future[Unit]
  def delete(file: S3ObjectLocation): Future[Unit]
  def getObjectContent(file: S3ObjectLocation): Future[ObjectContent]
  def getObjectMetadata(file: S3ObjectLocation): Future[ObjectMetadata]
}
