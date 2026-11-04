---
role: backend
summary: Scala 3 API on Postgres (http4s, skunk), port 9000
---
* start and stop: `(cd {dir} && ./scripts/app.scala start)`, `... stop`
* produces the API contract: the router specs regenerate the client view in
  `{dir}/target/baklava/views/client/orpc/src`
* rules and skills: `{dir}/AGENTS.md`
