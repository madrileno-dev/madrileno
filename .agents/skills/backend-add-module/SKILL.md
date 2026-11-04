---
name: backend-add-module
description: Add a new module to this backend, an aggregate with its own table, repository, service, router and specs. Use when a task introduces a new domain concept that gets its own table or endpoints.
---

# Add a module

1. From the backend root, generate the slice instead of writing it by hand:

   ```bash
   ./scripts/scaffold-module.scala <Aggregate> <plural>    # e.g. Loan loans
   sbt --client "compile; scalafmtAll; scalafixAll"
   ```

   It writes the module trait, domain, repository, service and router stubs,
   DTOs, a `V<next>__<aggregate>.sql` migration and domain, repository and
   router specs, wires the module into `ApplicationLoader`, adds
   `TestData.random<Aggregate>Id()`, and compiles green. The generated files
   are the house patterns: extend them, don't rebuild their shape.
2. Schema first: shape the generated migration, then the domain, then the
   repository, as in `backend-schema-change`.
3. Domain: every meaningful value is an opaque type with `validate`. State
   changes are methods on the aggregate returning
   `Either[<Rejection>, <Aggregate>]` that bump `updatedAt` themselves, never
   loose functions in the service. Replace the scaffold's `rename` example and
   `RenameRejection` with the aggregate's real transitions.
4. Service: one method per use case, taking a command and returning a result
   ADT; atomic transitions go through the repository's `update[E]`. The
   service passes `now: Instant` down; repositories never take a `Clock`.
5. Endpoints: replace the scaffold's single `GET /<plural>/{id}` with the real
   ones, following `backend-add-endpoint`. Where rules depend on the caller,
   thread `authContext.userId` into the service; the scaffold's
   `val _ = authContext` is a placeholder.
6. Every `// SCAFFOLD-TODO` is gone when you are done.

Done when:

* `grep -rn SCAFFOLD-TODO src` prints nothing
* `sbt --client "testOnly <pkg>.<aggregate>.*"` passes
* `./scripts/architecture.scala --diff main --mermaid` lists no new warnings

Detail: doc `adding-a-module` (the eight steps and the sharp edges), doc
`module-anatomy`; `madrileno_module("user")` shows a finished module.
