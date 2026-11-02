package architecture

object Extractor {
  def extract(input: ProjectInput): ArchModel = {
    val parsed                 = Parsing.parseAll(input)
    val files                  = parsed.value
    val ownership              = Ownership.of(files)
    val modules                = ModuleExtractor.extract(files, ownership)
    val wiring                 = WiringExtractor.extract(modules.value, files, ownership)
    val events                 = EventExtractor.extract(files, ownership, input.rootPackage)
    val (topics, outboxEvents) = events.value
    val eventTypes             = (topics.map(_.eventType) ++ outboxEvents.map(_.typeName)).flatMap(name => name :: outerType(name).toList).toSet
    val references             = ExplainedReferences.mark(
      ReferenceExtractor.extract(files, ownership, input.rootPackage),
      wiring.value,
      modules.value,
      files,
      ownership,
      eventTypes
    )
    val cycles     = Cycles.warnings(modules.value, wiring.value, references)
    val tables     = TableExtractor.extract(files, input.migrations, ownership)
    val tasks      = TaskExtractor.extract(files, ownership)
    val tableDecls = tables.value
    val imports    = ReferenceExtractor.outsideImports(files, ownership, input.rootPackage)

    ArchModel(
      rootPackage = input.rootPackage,
      modules = modules.value.map(module =>
        withOwnedCounts(module, topics, outboxEvents, tableDecls)
          .copy(
            files = digestsOf(files, ownership, input.rootPackage)(_ == Owner.Module(module.id))(_ => Map.empty),
            outsideImports = imports.getOrElse(module.id, Nil)
          )
      ),
      wirings = wiring.value,
      references = references,
      topics = topics,
      outboxEvents = outboxEvents,
      tasks = tasks.value,
      tables = tableDecls,
      warnings = (modules.warnings ++ wiring.warnings ++ cycles ++ events.warnings ++ tables.warnings ++ tasks.warnings)
        .distinctBy(_.key)
        .sortBy(w => (w.kind.ordinal, w.key)),
      unclassified =
        (parsed.unclassified ++ modules.unclassified ++ wiring.unclassified ++ events.unclassified ++ tables.unclassified ++ tasks.unclassified)
          .distinctBy(_.key)
          .sortBy(_.key),
      migrations = MigrationExtractor.extract(input.migrations),
      outsideModules = digestsOf(files, ownership, input.rootPackage) {
        case Owner.Module(_) => false
        case _               => true
      }(definitionDigests(_, input.rootPackage))
    )
  }

  private def outerType(typeName: String): Option[String] = typeName.takeWhile(_ != '[').split('.').find(_.headOption.exists(_.isUpper))

  private def digestsOf(
    files: List[ParsedFile],
    ownership: Ownership,
    rootPackage: String
  )(
    owned: Owner => Boolean
  )(
    definitions: ParsedFile => Map[String, String]
  ): List[FileDigest] =
    files
      .filter(file => owned(ownership.ownerOf(file.pkg)))
      .map(file => FileDigest(file.source.packagePath, Trees.codeDigest(file.tree, rootPackage), definitions(file)))
      .sortBy(_.path)

  private def definitionDigests(file: ParsedFile, rootPackage: String): Map[String, String] =
    Trees
      .topLevelDefinitions(file.tree)
      .groupMap(_._1)(_._2)
      .view
      .mapValues(stats => Trees.hash(stats.map(Trees.codeDigest(_, rootPackage)).mkString))
      .toMap

  private def withOwnedCounts(
    module: Module,
    topics: List[BusTopic],
    events: List[OutboxEvent],
    tables: List[TableDecl]
  ): Module = {
    val owner = Owner.Module(module.id)
    module.copy(metrics =
      module.metrics.copy(tablesOwned = tables.count(_.owner == owner), eventsOwned = topics.count(_.owner == owner) + events.count(_.owner == owner))
    )
  }
}
