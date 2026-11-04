---
name: backend-schema-change
description: Change the database schema of this backend, a new table or column, an index, a constraint or a data migration, together with the Row, Table and repository code that read it. Use before writing any Scala that depends on the new shape.
---

# Change the schema

1. Write the migration first:
   `src/main/resources/db/migration/V<next>__<what>.sql`, where `<next>` is
   the highest existing `V<n>` plus one. It fixes the column names and types
   that every later step follows.
   * never edit a migration that is on `main`: Flyway checksums applied
     scripts, so add a new one, forward-only
   * `TIMESTAMPTZ` for times, `NUMERIC` for money, a nullable `deleted_at` for
     soft delete, partial indexes `WHERE deleted_at IS NULL` for lookups,
     `CHECK` constraints for values the domain validates (rows are read with
     the unvalidated constructor)
   * a new `NOT NULL` column on an existing table needs a default or a
     backfill in the same migration
2. Change the domain, the `Row`, the `RowTable` columns and the DTOs together
   in one change: a column that exists in one of them only does not ship.
   `Row`, `RowTable`, `RowFilter` and `Row.apply` stay `private[repositories]`.
3. Repository methods take typed arguments (`status: Option[Status]`) and
   build the row filter inside; mutating methods take `now: Instant`.
4. Hand-written SQL names every table and column through its `Table` object:

   ```scala
   sql"""SELECT ${LoanRowTable.*("l")} FROM ${LoanRowTable.n} l
         WHERE ${LoanRowTable.dueAt.n("l")} < $timestamptz"""
   ```

5. Apply it to the dev database with `./scripts/app.scala migrate` (or
   `restart`, which migrates first). Tests migrate their own containers.

Done when:

* `./scripts/app.scala migrate` applies it
* `sbt --client "testOnly <pkg>.<module>.repositories.*"` passes
* `sbt --client "testOnly <pkg>.sourcechecks.HandWrittenSqlSpec"` passes

Detail: doc `database` (tables, filters, updating, hand-written SQL,
migrations).
