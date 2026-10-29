package architecture

import upickle.default.ReadWriter

final case class ArchMetrics(
  propagationCost: Double,
  largestCycle: Int,
  stabilityViolations: Int,
  cakeBypasses: Int,
  drift: Int)
    derives ReadWriter

final case class ModuleCoupling(
  id: String,
  dependents: Int,
  dependencies: Int,
  instability: Double)
    derives ReadWriter

final case class MetricsDiff(
  base: ArchMetrics,
  head: ArchMetrics,
  coupling: List[ModuleCoupling])
    derives ReadWriter

final case class MetricRow(
  name: String,
  value: ArchMetrics => Double,
  format: Double => String,
  description: String)

object ArchMetrics {
  private val percent: Double => String = value => s"${math.round(value * 100)}%"

  private val whole: Double => String = value => value.toLong.toString

  val rows: List[MetricRow] = List(
    MetricRow(
      "Propagation cost",
      _.propagationCost,
      percent,
      "How far a change can ripple: the share of other modules a change in one module can reach."
    ),
    MetricRow("Largest cycle", _.largestCycle.toDouble, whole, "Modules in the biggest group that all depend on each other; 0 means no cycles."),
    MetricRow(
      "Stable-dependency violations",
      _.stabilityViolations.toDouble,
      whole,
      "Dependencies on a module that changes more easily than the one depending on it."
    ),
    MetricRow(
      "Cake bypasses",
      _.cakeBypasses.toDouble,
      whole,
      "Types taken from another module's services, repositories, routers or gateways around the wiring."
    ),
    MetricRow("Drift", _.drift.toDouble, whole, "Warnings plus unclassified items.")
  )

  def trend(
    row: MetricRow,
    base: ArchMetrics,
    head: ArchMetrics
  ): Option[Boolean] =
    if (row.format(row.value(base)) == row.format(row.value(head))) None else Some(row.value(head) > row.value(base))

  def diff(base: ArchModel, head: ArchModel): MetricsDiff = MetricsDiff(of(base), of(head), coupling(head))

  def of(model: ArchModel): ArchMetrics = {
    val graph   = ModuleGraph.of(model)
    val ids     = graph.nodes
    val pairs   = ids.size * (ids.size - 1)
    val reached = ids.map(id => (graph.reachable(id) - id).size).sum
    val coupled = coupling(model).map(c => c.id -> c.instability).toMap
    ArchMetrics(
      propagationCost = if (pairs == 0) 0.0 else reached.toDouble / pairs,
      largestCycle = graph.cycles.map(_.size).maxOption.getOrElse(0),
      stabilityViolations = graph.pairs.count((from, to) => coupled(to) > coupled(from)),
      cakeBypasses = model.references.count(r => Layers.Internal(r.layer) && r.explainedBy.isEmpty),
      drift = model.warnings.size + model.unclassified.size
    )
  }

  def coupling(model: ArchModel): List[ModuleCoupling] = {
    val graph = ModuleGraph.of(model)
    graph.nodes.map { id =>
      val (dependents, outgoing) = (graph.dependents(id), graph.dependencies(id))
      ModuleCoupling(id, dependents, outgoing, if (dependents + outgoing == 0) 0.0 else outgoing.toDouble / (dependents + outgoing))
    }
  }
}
