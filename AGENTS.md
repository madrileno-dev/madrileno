You are an expert backend engineer on a Madrileño service: Scala 3, http4s +
stir, skunk on Postgres, cats-effect.

# Where things are

* `src/main/scala/<pkg>/<module>/`: one vertical slice per module, with
  `domain/`, `repositories/`, `services/`, `routers/` (DTOs in `routers/dto/`)
  and `<Module>Module.scala`, wired in `main/ApplicationLoader.scala`
* `src/main/scala/<pkg>/utils/`: the shared platform (http, db, auth, mailer,
  storage, scheduler, outbox, ...)
* `src/main/resources/db/migration/`: Flyway migrations, `V<n>__<name>.sql`
* `src/test/scala/` mirrors main; factories live in `support/TestData.scala`
* docs: "doc `<name>`" means `madrileno_doc("<name>")` on the madrileno MCP
  server (`madrileno_overview` lists them), or `docs/<name>.md` if this project
  kept its docs. Read the doc for the area you touch, not all of them
* running it in production (health and readiness probes, backups and restore,
  pool size, log format) is doc `deployment`; the database timeouts and what
  answers 503 are in doc `database`
* the router specs (Baklava) generate the API contract; the client view the
  frontend and mobile apps sync is `target/baklava/views/client/orpc/src`

# Procedures

Use these skills instead of rebuilding the steps from existing modules:
`backend-add-module` (new aggregate with its table and endpoints),
`backend-schema-change` (migration, table object, repository),
`backend-add-endpoint` (route, DTOs, error codes, router spec).

# Build, run, test

* compile and test through the Metals MCP tools when they are connected
  (`compile-module`, `test`; `import-build` after editing `build.sbt`,
  `find-dep`, `inspect`); otherwise `sbt --client ...`, never a bare `sbt`
* NEVER run commands that block until interrupted: `~reStart`, `~test`,
  `~compile`, `sbt run`, a bare `sbt` shell
* run the app with `./scripts/app.scala`, in the foreground: `start` compiles,
  applies pending migrations and returns once the health check answers, or
  exits 1 with the log tail; `restart` after a code or `.env` change; `stop`,
  `status`, `logs`, `migrate`. If `start` reports an app it did not start, a
  developer's `~reStart` loop owns the port: use that app and leave it alone
* while working, run only the specs for what you changed:
  `sbt --client "testOnly <pkg>.<module>.*"`. Before committing, once:
  `sbt --client "scalafmtAll; scalafixAll; Test/verifyAll"` (format check,
  compile, every suite, error codes in the contract)
* after adding or moving modules, services, repositories, events or tables:
  `./scripts/architecture.scala --diff main --mermaid`; fix the new warnings
  and unclassified items, and paste the output into the PR description
* write long command output to a file under `target/agent-logs/`, not `/tmp`:
  sessions in other worktrees use the same `/tmp` names
* stop what you started: the app `./scripts/app.scala stop`, the sbt server
  `sbt --client shutdown`, the compose stack `docker compose stop`

# Code rules

* functional style: immutable data and collections, ADTs, pattern matching,
  pure functions that take and return state; mutable state only local to a
  method, never shared
* braces `{}`; no non-local returns; top-level imports, not FQNs
  (`QualifiedNamesSpec` fails otherwise)
* name things for their responsibility; restrict visibility to sub-packages
  where possible; track who owns each resource and release in order
* in hand-written `sql"..."`, name tables and columns through their `Table`
  object (`HandWrittenSqlSpec` fails otherwise; exceptions go in its
  `exemptions`, with the reason)
* comments only for what the code cannot say; most files have none
