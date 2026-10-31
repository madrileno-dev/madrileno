package architecture

import upickle.implicits.serializeDefaults

import upickle.default.ReadWriter

final case class Loc(path: String, line: Int) derives ReadWriter

enum Owner derives ReadWriter {
  case Module(id: String)
  case Platform(id: String)
  case CompositionRoot
}

final case class Member(
  name: String,
  tpe: String,
  loc: Loc)
    derives ReadWriter

enum Registry derives ReadWriter {
  case Routes, AuthRoutes, WsRoutes, AuthWsRoutes, RecurringTasks, OneTimeTasks, CustomTasks, OutboxSubscriptions, MailPreviews, Lifecycles
}

final case class Contribution(
  registry: Registry,
  items: List[String],
  loc: Loc)
    derives ReadWriter

enum ComponentKind derives ReadWriter {
  case Service, Repository, Router, Gateway, Other
}

final case class Component(
  name: String,
  kind: ComponentKind,
  dependencies: List[String],
  loc: Loc,
  file: String)
    derives ReadWriter

final case class Metrics(
  linesOfCode: Int,
  components: Int,
  tablesOwned: Int,
  eventsOwned: Int)
    derives ReadWriter

final case class FileDigest(path: String, digest: String) derives ReadWriter

@serializeDefaults(true)
final case class Module(
  id: String,
  traitName: String,
  mixedIn: Boolean,
  consumes: List[Member],
  provides: List[Member],
  contributions: List[Contribution],
  components: List[Component],
  metrics: Metrics,
  loc: Loc,
  files: List[FileDigest] = Nil)
    derives ReadWriter

enum Provider derives ReadWriter {
  case FromModule(id: String)
  case FromPlatform
}

final case class Wiring(
  member: String,
  consumer: String,
  provider: Provider)
    derives ReadWriter

@serializeDefaults(true)
final case class Reference(
  from: String,
  to: String,
  layer: String,
  symbol: String,
  loc: Loc,
  explainedBy: Option[Explanation] = None)
    derives ReadWriter

enum Explanation derives ReadWriter {
  case Wiring, Event
}

final case class Site(
  owner: Owner,
  enclosing: String,
  loc: Loc)
    derives ReadWriter

@serializeDefaults(true)
final case class BusTopic(
  name: String,
  eventType: String,
  owner: Owner,
  publishers: List[Site],
  subscribers: List[Site],
  loc: Loc,
  definedIn: Option[String] = None)
    derives ReadWriter

final case class OutboxSubscriber(consumer: String, site: Site) derives ReadWriter

@serializeDefaults(true)
final case class OutboxEvent(
  typeName: String,
  eventType: String,
  owner: Owner,
  publishers: List[Site],
  subscribers: List[OutboxSubscriber],
  loc: Loc,
  definedIn: Option[String] = None)
    derives ReadWriter

enum TaskKind derives ReadWriter {
  case OneTime, Recurring, Custom

  def label: String = this match {
    case OneTime   => "one-time"
    case Recurring => "recurring"
    case Custom    => "custom"
  }
}

final case class TaskDecl(
  name: String,
  kind: TaskKind,
  schedule: Option[String],
  owner: Owner,
  scheduledFrom: List[Site],
  loc: Loc)
    derives ReadWriter

final case class TableColumn(name: String, codec: String) derives ReadWriter

@serializeDefaults(true)
final case class TableDecl(
  name: String,
  objectName: String,
  owner: Owner,
  users: List[Site],
  loc: Loc,
  columns: List[TableColumn] = Nil)
    derives ReadWriter

@serializeDefaults(true)
final case class Migration(
  path: String,
  digest: String,
  creates: List[String] = Nil,
  alters: List[String] = Nil,
  drops: List[String] = Nil,
  indexes: List[String] = Nil,
  writes: List[String] = Nil)
    derives ReadWriter {
  def tables: List[String] = (creates ++ alters ++ drops ++ indexes ++ writes).distinct

  def summary(name: String => String): String =
    List("creates" -> creates, "alters" -> alters, "drops" -> drops, "indexes" -> indexes, "writes" -> writes)
      .collect { case (verb, tables) if tables.nonEmpty => s"$verb ${tables.map(name).mkString(", ")}" }
      .mkString(", ")
}

final case class MigrationTable(name: String, loc: Loc) derives ReadWriter

enum WarningKind derives ReadWriter {
  case NotMixedIn, UnprovidedMember, ModuleCycle, SubscriberWithoutPublisher, CrossModuleTableUse, DuplicateTable, DuplicateTopic, DuplicateEvent,
    DuplicateTask, MigrationTableWithoutTable
}

final case class Warning(
  kind: WarningKind,
  key: String,
  message: String,
  loc: Loc)
    derives ReadWriter

final case class Unclassified(
  key: String,
  description: String,
  loc: Loc)
    derives ReadWriter

final case class Extracted[A](
  value: A,
  warnings: List[Warning],
  unclassified: List[Unclassified])

@serializeDefaults(true)
final case class ArchModel(
  rootPackage: String,
  modules: List[Module],
  wirings: List[Wiring],
  references: List[Reference],
  topics: List[BusTopic],
  outboxEvents: List[OutboxEvent],
  tasks: List[TaskDecl],
  tables: List[TableDecl],
  warnings: List[Warning],
  unclassified: List[Unclassified],
  migrations: List[Migration] = Nil,
  outsideModules: List[FileDigest] = Nil)
    derives ReadWriter
