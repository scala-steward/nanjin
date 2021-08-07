package com.github.chenharryhua.nanjin.guard

import cats.effect.kernel.Resource
import com.github.chenharryhua.nanjin.guard.alert.AlertService

private[guard] trait HasAlertService[F[_], A] {
  def addAlertService(ras: Resource[F, AlertService[F]]): A

  final def addAlertService(as: AlertService[F]): A =
    addAlertService(Resource.pure[F, AlertService[F]](as))
}
