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

package utils

import cats.data.EitherT
import services.{ExceptionWithContext, MessageContext}

import scala.concurrent.{ExecutionContext, Future}

object MonadicUtils {

  type FutureEitherWithContext[T] = EitherT[Future, ExceptionWithContext, T]

  def withContext[T](f: Future[T], context: MessageContext)(implicit ec: ExecutionContext): FutureEitherWithContext[T] =
    toFutureEitherWithContext(f, Some(context))

  def withoutContext[T](f: Future[T])(implicit ec: ExecutionContext): FutureEitherWithContext[T] =
    toFutureEitherWithContext(f, None)

  private def toFutureEitherWithContext[T](f: Future[T], context: Option[MessageContext])(
    implicit ec: ExecutionContext): FutureEitherWithContext[T] = {
    val futureEither: Future[Either[ExceptionWithContext, T]] =
      f.map(Right(_))
        .recover { case error: Exception => Left(ExceptionWithContext(error, context)) }
    EitherT(futureEither)
  }
}
