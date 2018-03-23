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

import javax.inject.Inject

import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.model.S3Object
import com.amazonaws.util.IOUtils
import config.ServiceConfiguration
import model.S3ObjectLocation
import services.FileManager

import scala.concurrent.{ExecutionContext, Future}

class S3FileManager @Inject()(s3Client: AmazonS3, config: ServiceConfiguration)(implicit ec: ExecutionContext)
    extends FileManager {
  override def copyToOutboundBucket(file: S3ObjectLocation) =
    Future(
      s3Client.copyObject(file.bucket, file.objectKey, config.outboundBucket, file.objectKey)
    )

  override def delete(file: S3ObjectLocation) =
    Future(
      s3Client.deleteObject(file.bucket, file.objectKey)
    )

  override def getBytes(file: S3ObjectLocation): Future[Array[Byte]] = {
    Future {
      val fileFromLocation: S3Object = s3Client.getObject(file.bucket, file.objectKey)
      IOUtils.toByteArray(fileFromLocation.getObjectContent)
    }
  }
}
