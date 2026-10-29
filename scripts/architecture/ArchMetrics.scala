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
  private val InternalLayers = Set("services", "repositories", "routers", "gateways")

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
    val ids       = model.modules.map(_.id).sorted
    val edges     = dependencies(model)
    val reachable = ids.map(id => id -> reach(Set(id), edges, Set.empty)).toMap
    val pairs     = ids.size * (ids.size - 1)
    val reached   = ids.map(id => (reachable(id) - id).size).sum
    val cycles    = ids.map(id => ids.filter(other => reachable(id)(other) && reachable(other)(id)).size).maxOption.getOrElse(0)
    val coupled   = coupling(model).map(c => c.id -> c.instability).toMap
    ArchMetrics(
      propagationCost = if (pairs == 0) 0.0 else reached.toDouble / pairs,
      largestCycle = if (cycles > 1) cycles else 0,
      stabilityViolations = edges.toList.flatMap((from, tos) => tos.toList.map(from -> _)).count((from, to) => coupled(to) > coupled(from)),
      cakeBypasses = model.references.count(r => InternalLayers(r.layer) && r.explainedBy.isEmpty),
      drift = model.warnings.size + model.unclassified.size
    )
  }

  def coupling(model: ArchModel): List[ModuleCoupling] = {
    val edges = dependencies(model)
    model.modules.map(_.id).sorted.map { id =>
      val dependents = edges.count((_, tos) => tos(id))
      val outgoing   = edges.getOrElse(id, Set.empty).size
      ModuleCoupling(id, dependents, outgoing, if (dependents + outgoing == 0) 0.0 else outgoing.toDouble / (dependents + outgoing))
    }
  }

  private def dependencies(model: ArchModel): Map[String, Set[String]] = {
    val ids = model.modules.map(_.id).toSet
    (model.references.map(r => r.from -> r.to) ++ model.wirings.collect { case Wiring(_, consumer, Provider.FromModule(provider)) =>
      consumer -> provider
    }).filter((from, to) => from != to && ids(from) && ids(to)).groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap
  }

  private def reach(
    frontier: Set[String],
    edges: Map[String, Set[String]],
    seen: Set[String]
  ): Set[String] =
    if (frontier.isEmpty) seen
    else {
      val next = frontier.flatMap(edges.getOrElse(_, Set.empty)) -- seen
      reach(next, edges, seen ++ next)
    }
}
