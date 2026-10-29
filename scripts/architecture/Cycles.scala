package architecture

object Cycles {
  def warnings(
    modules: List[Module],
    wirings: List[Wiring],
    references: List[Reference]
  ): List[Warning] = {
    val edges = (references.map(r => r.from -> r.to) ++ wirings.collect { case Wiring(_, consumer, Provider.FromModule(provider)) =>
      consumer -> provider
    }).filterNot { case (from, to) => from == to }.groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap
    val nodes     = (modules.map(_.id) ++ edges.keys ++ edges.values.flatten).distinct.sorted
    val reachable = nodes.map(node => node -> reach(Set(node), edges, Set.empty)).toMap
    nodes
      .map(node => nodes.filter(other => reachable(node)(other) && reachable(other)(node)).toSet)
      .distinct
      .filter(_.size > 1)
      .map { cycle =>
        val ids = cycle.toList.sorted
        Warning(
          WarningKind.ModuleCycle,
          s"cycle:${ids.mkString(",")}",
          s"modules ${ids.mkString(", ")} depend on each other",
          locOf(cycle, modules, wirings, references)
        )
      }
      .sortBy(_.key)
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

  private def locOf(
    cycle: Set[String],
    modules: List[Module],
    wirings: List[Wiring],
    references: List[Reference]
  ): Loc =
    references
      .find(r => cycle(r.from) && cycle(r.to))
      .map(_.loc)
      .orElse(wirings.collectFirst {
        case Wiring(member, consumer, Provider.FromModule(provider)) if cycle(consumer) && cycle(provider) =>
          modules.find(_.id == consumer).flatMap(_.consumes.find(_.name == member)).map(_.loc)
      }.flatten)
      .orElse(modules.find(m => cycle(m.id)).map(_.loc))
      .getOrElse(Loc("", 0))
}
