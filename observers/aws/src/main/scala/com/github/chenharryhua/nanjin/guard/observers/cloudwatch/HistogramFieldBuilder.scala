package com.github.chenharryhua.nanjin.guard.observers.cloudwatch

final class HistogramFieldBuilder private[cloudwatch] (fields: List[HistogramField]) {
  private def add(hf: HistogramField): HistogramFieldBuilder =
    new HistogramFieldBuilder(hf :: fields)
  def withMin: HistogramFieldBuilder    = add(HistogramField.Min)
  def withMax: HistogramFieldBuilder    = add(HistogramField.Max)
  def withMean: HistogramFieldBuilder   = add(HistogramField.Mean)
  def withStdDev: HistogramFieldBuilder = add(HistogramField.StdDev)
  def withP50: HistogramFieldBuilder    = add(HistogramField.P50)
  def withP75: HistogramFieldBuilder    = add(HistogramField.P75)
  def withP95: HistogramFieldBuilder    = add(HistogramField.P95)
  def withP98: HistogramFieldBuilder    = add(HistogramField.P98)
  def withP99: HistogramFieldBuilder    = add(HistogramField.P99)
  def withP999: HistogramFieldBuilder   = add(HistogramField.P999)

  private[cloudwatch] def build: List[HistogramField] = fields.distinct

}
