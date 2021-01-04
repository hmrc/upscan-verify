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

import org.apache.commons.io.IOUtils
import uk.gov.hmrc.clamav.config.ClamAvConfig

import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ClamAvIO @Inject()(clamAvConfig: ClamAvConfig)(implicit ec: ExecutionContext) {

  def withSocket[T](f: Connection => Future[T]): Future[T] = ClamAvSocket.withSocket(clamAvConfig)(f)

  def sendCommand(command: String)(implicit connection: Connection): Future[Unit] = Future {
    connection.out.write(command.getBytes)
  }

  def sendData(stream: InputStream, length: Int)(implicit connection: Connection): Future[Unit] =
    Future {
      connection.out.writeInt(length)
      IOUtils.copy(stream, connection.out)
      connection.out.writeInt(0)
      connection.out.flush()
    }

  def readResponse(implicit connection: Connection): Future[String] = Future {
    IOUtils.toString(connection.in, UTF_8)
  }

}
