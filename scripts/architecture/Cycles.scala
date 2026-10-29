package architecture

object Cycles {
  def warnings(
    modules: List[Module],
    wirings: List[Wiring],
    references: List[Reference]
  ): List[Warning] = {
    ModuleGraph
      .of(modules, wirings, references)
      .cycles
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
