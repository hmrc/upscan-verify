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

package uk.gov.hmrc.clamav

import play.api.Logging
import uk.gov.hmrc.clamav.config.ClamAvConfig

import java.io.{DataOutputStream, InputStream}
import java.net.{InetSocketAddress, Socket}
import scala.concurrent.{ExecutionContext, Future}

private[clamav] class ClamAvSocket(
  socket           : Socket,
  override val in  : InputStream,
  override val out : DataOutputStream
) extends Connection
     with Logging:

  private def terminate()(using ExecutionContext): Future[Unit] =
    Future
      .apply:
        socket.close()
        out.close()
      .recover:
        case e: Throwable =>
          logger.error("Error closing socket to clamd", e)

private[clamav] trait Connection:
  def in : InputStream
  def out: DataOutputStream

private[clamav] object ClamAvSocket:

  private def openSocket(
    config: ClamAvConfig
  )(using
    ExecutionContext
  ): Future[ClamAvSocket] =
    Future:
      val sock = Socket()
      sock.setSoTimeout(config.timeout.toMillis.toInt)
      sock.setKeepAlive(true)

      val address: InetSocketAddress = InetSocketAddress(config.host, config.port)
      sock.connect(address)

      ClamAvSocket(
        sock,
        in  = sock.getInputStream,
        out = DataOutputStream(sock.getOutputStream)
      )

  def withSocket[T](
    config: ClamAvConfig
  )(
    function: Connection => Future[T]
  )(using
    ExecutionContext
  ): Future[T] =
    for
      socket <- openSocket(config)
      result <- { val functionResult = function(socket)
                  functionResult.onComplete(_ => socket.terminate())
                  functionResult
                }
    yield result
