package com.github.chenharryhua.nanjin.guard.action

private[guard] object ActionException {

  case object PostConditionUnsatisfied extends Exception("action post condition unsatisfied")
  case object ActionCanceledInternally extends Exception("action was canceled internally")
  case object ActionCanceledExternally extends Exception("action was canceled externally")
  case object UnexpectedlyTerminated extends Exception("action was terminated unexpectedly")

}
