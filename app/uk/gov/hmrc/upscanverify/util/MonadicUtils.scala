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

package uk.gov.hmrc.upscanverify.util

import cats.data.EitherT
import uk.gov.hmrc.upscanverify.service.{ExceptionWithContext, MessageContext}

import scala.concurrent.{ExecutionContext, Future}

object MonadicUtils:

  type FutureEitherWithContext[T] = EitherT[Future, ExceptionWithContext, T]

  def withContext[T](f: Future[T], context: MessageContext)(using ExecutionContext): FutureEitherWithContext[T] =
    toFutureEitherWithContext(f, Some(context))

  def withoutContext[T](f: Future[T])(using ExecutionContext): FutureEitherWithContext[T] =
    toFutureEitherWithContext(f, None)

  private def toFutureEitherWithContext[T](
    f      : Future[T],
    context: Option[MessageContext]
  )(using
    ExecutionContext
  ): FutureEitherWithContext[T] =
    EitherT:
      f
        .map(Right.apply)
        .recover { case error: Exception => Left(ExceptionWithContext(error, context)) }
