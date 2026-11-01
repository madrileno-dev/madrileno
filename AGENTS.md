You are an expert backend software engineer and architect.

# Scala projects

* ALWAYS use tools to compile and run tests instead of relying on bash commands
* after adding a dependency to `build.sbt`, ALWAYS run the `import-build` tool
* to lookup a dependency or the latest version, use the `find-dep` tool
* to lookup the API of a class, use the `inspect` tool
* use `sbt --client` instead of `sbt` to connect to a running sbt server for
  faster execution
* NEVER run commands that block until interrupted: watch commands (`~reStart`,
  `~test`, `~compile`), `sbt run`, or a bare `sbt` shell
* run the app through `./scripts/app.scala`: `start` compiles, applies pending
  migrations, runs the app in its own process and returns once
  `/v1/health-check` answers, or exits 1 with the log tail; `restart` after a
  code or `.env` change; `stop`, `status`, `logs`. Run them in the foreground:
  the first `start` in a fresh checkout compiles everything and takes about a
  minute. Never `reStart`, `sbt run` or a watch loop
* if `start` reports an app it did not start, a developer's `~reStart` loop
  owns the port: use that app and leave it alone
* background processes and their stop commands: the app
  `./scripts/app.scala stop`, the sbt server `sbt --client shutdown`, the
  compose stack `docker compose stop` (`down -v` also wipes data)
* before committing, ALWAYS format all changed Scala files using the sbt
  `scalafmt` plugin: `sbt --client scalafmtAll` and run `scalafix` with
  `sbt --client scalafixAll`
* after a change that adds or moves modules, services, repositories, events or
  tables, run `./scripts/architecture.scala --diff main --mermaid` to check the
  architecture: fix the new warnings and unclassified items it lists. When
  opening a PR, paste the same output into the PR description
* pipe every command's output to tmp file for easier analysis
* prefer using braces `{}`
* prefer top-level (or scope-level) imports over FQNs: `QualifiedNamesSpec`
  fails on a name written out from its top-level package
* NEVER use non-local returns
* ALWAYS use functional programming: immutable data types, pattern matching,
  immutable collections, higher order functions, algebraic data types
* instead of mutable state, ALWAYS prefer writing testable, pure functions that
  accept and return a state data type. Use mutable state only locally at the
  top-level

# Coding style

* take care of good naming; responsibilities in code should be segregated
  between appropriately named entities
* when dealing with resources, properly track who owns which resources, and
  ensure proper ordering on cleanup
* when possible, restrict visibility of classes and top-level constructs to
  appropriate sub-packages. No need to restrict visibility to the main package.
* it's fine to create multiple classes in one file, especially if they are used
  only by that class
* AVOID using global mutable state. Instead use immutable state that is passed &
  returned from functions. Local mutable state, such a mutable variables tightly
  scoped in a method are fine
* AVOID shared mutable state at any cost
* AVOID using mutable collections
* comment on any aspects that aren't obvious from the implementation, but are
  important to know when reading the code
* in hand-written `sql"..."`, name tables and columns through their `Table`
  object (`${XTable.n}`, `${XTable.column.n}`), never as text:
  `HandWrittenSqlSpec` fails otherwise. A query that must name them by hand
  goes in its `exemptions`, with the reason
