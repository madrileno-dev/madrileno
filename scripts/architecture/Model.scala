package architecture

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
  loc: Loc)
    derives ReadWriter

final case class Metrics(
  linesOfCode: Int,
  components: Int,
  tablesOwned: Int,
  eventsOwned: Int)
    derives ReadWriter

final case class Module(
  id: String,
  traitName: String,
  mixedIn: Boolean,
  consumes: List[Member],
  provides: List[Member],
  contributions: List[Contribution],
  components: List[Component],
  metrics: Metrics,
  loc: Loc)
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

final case class Reference(
  from: String,
  to: String,
  layer: String,
  symbol: String,
  loc: Loc,
  wired: Boolean = false)
    derives ReadWriter

final case class Site(
  owner: Owner,
  enclosing: String,
  loc: Loc)
    derives ReadWriter

final case class BusTopic(
  name: String,
  eventType: String,
  owner: Owner,
  publishers: List[Site],
  subscribers: List[Site],
  loc: Loc)
    derives ReadWriter

final case class OutboxSubscriber(consumer: String, site: Site) derives ReadWriter

final case class OutboxEvent(
  typeName: String,
  eventType: String,
  owner: Owner,
  publishers: List[Site],
  subscribers: List[OutboxSubscriber],
  loc: Loc)
    derives ReadWriter

final case class TableDecl(
  name: String,
  objectName: String,
  owner: Owner,
  users: List[Site],
  loc: Loc)
    derives ReadWriter

final case class MigrationTable(name: String, loc: Loc) derives ReadWriter

enum WarningKind derives ReadWriter {
  case NotMixedIn, UnprovidedMember, ModuleCycle, SubscriberWithoutPublisher, CrossModuleTableUse, MigrationTableWithoutTable
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

final case class ArchModel(
  rootPackage: String,
  modules: List[Module],
  wirings: List[Wiring],
  references: List[Reference],
  topics: List[BusTopic],
  outboxEvents: List[OutboxEvent],
  tables: List[TableDecl],
  migrationTables: List[MigrationTable],
  warnings: List[Warning],
  unclassified: List[Unclassified])
    derives ReadWriter
