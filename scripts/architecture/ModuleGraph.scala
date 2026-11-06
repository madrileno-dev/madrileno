package architecture

final case class ModuleGraph(nodes: List[String], edges: Map[String, Set[String]]) {
  lazy val reachable: Map[String, Set[String]] = nodes.map(node => node -> ModuleGraph.reach(Set(node), edges, Set.empty)).toMap

  lazy val cycles: List[Set[String]] =
    nodes.map(node => nodes.filter(other => reachable(node)(other) && reachable(other)(node)).toSet).distinct.filter(_.size > 1)

  def dependents(id: String): Int = edges.count((_, targets) => targets(id))

  def dependencies(id: String): Int = edges.getOrElse(id, Set.empty).size

  def pairs: List[(String, String)] = edges.toList.flatMap((from, targets) => targets.toList.map(from -> _))
}

object ModuleGraph {
  def of(
    modules: List[Module],
    wirings: List[Wiring],
    references: List[Reference]
  ): ModuleGraph = {
    val ids   = modules.map(_.id).toSet
    val edges = (references.map(r => r.from -> r.to) ++ wirings.collect { case Wiring(_, consumer, Provider.FromModule(provider)) =>
      consumer -> provider
    }).filter((from, to) => from != to && ids(from) && ids(to)).groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap
    ModuleGraph(ids.toList.sorted, edges)
  }

  def of(model: ArchModel): ModuleGraph = of(model.modules, model.wirings, model.references)

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
