package architecture

object Extractor {
  def extract(input: ProjectInput): ArchModel = {
    val parsed     = Parsing.parseAll(input)
    val files      = parsed.value
    val ownership  = Ownership.of(files)
    val modules    = ModuleExtractor.extract(files, ownership)
    val wiring     = WiringExtractor.extract(modules.value, files, ownership)
    val events     = EventExtractor.extract(files, ownership)
    val eventTypes = events.value._1.map(_.eventType).toSet ++ events.value._2.map(_.typeName).toSet
    val references = ExplainedReferences.mark(
      ReferenceExtractor.extract(files, ownership, input.rootPackage),
      wiring.value,
      modules.value,
      files,
      ownership,
      eventTypes
    )
    val cycles                        = Cycles.warnings(modules.value, wiring.value, references)
    val tables                        = TableExtractor.extract(files, input.migrations, ownership)
    val tasks                         = TaskExtractor.extract(files, ownership)
    val (topics, outboxEvents)        = events.value
    val (tableDecls, migrationTables) = tables.value

    ArchModel(
      rootPackage = input.rootPackage,
      modules = modules.value.map(module =>
        withOwnedCounts(module, topics, outboxEvents, tableDecls).copy(files = digestsOf(module, files, ownership, input.rootPackage))
      ),
      wirings = wiring.value,
      references = references,
      topics = topics,
      outboxEvents = outboxEvents,
      tasks = tasks.value,
      tables = tableDecls,
      migrationTables = migrationTables,
      warnings = (modules.warnings ++ wiring.warnings ++ cycles ++ events.warnings ++ tables.warnings).sortBy(w => (w.kind.ordinal, w.key)),
      unclassified = (parsed.unclassified ++ modules.unclassified ++ wiring.unclassified ++ events.unclassified ++ tables.unclassified).sortBy(_.key)
    )
  }

  private def digestsOf(
    module: Module,
    files: List[ParsedFile],
    ownership: Ownership,
    rootPackage: String
  ): List[FileDigest] =
    files
      .filter(file => ownership.ownerOf(file.pkg) == Owner.Module(module.id))
      .map(file => FileDigest(file.source.packagePath, Trees.codeDigest(file.tree, rootPackage)))
      .sortBy(_.path)

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
