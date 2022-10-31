package com.github.chenharryhua.nanjin

import cats.effect.Temporal
import cats.syntax.all.*
import fs2.Stream
import retry.{PolicyDecision, RetryPolicy, RetryStatus}

package object guard {

  def awakeEvery[F[_]](policy: RetryPolicy[F])(implicit F: Temporal[F]): Stream[F, Int] =
    Stream.unfoldEval[F, RetryStatus, Int](RetryStatus.NoRetriesYet)(status =>
      policy.decideNextRetry(status).flatMap {
        case PolicyDecision.GiveUp => F.pure(None)
        case PolicyDecision.DelayAndRetry(delay) =>
          F.sleep(delay).as(Some((status.retriesSoFar, status.addRetry(delay))))
      })
}
