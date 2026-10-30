package architecture

import scala.meta.{Member as _, *}

object TableExtractor {
  private val CreateTable =
    """(?i)\bcreate\s+table\s+(?:if\s+not\s+exists\s+)?((?:"[^"]+"|[A-Za-z_][A-Za-z0-9_]*)(?:\.(?:"[^"]+"|[A-Za-z_][A-Za-z0-9_]*))?)""".r

  def extract(
    files: List[ParsedFile],
    migrations: List[SourceFile],
    ownership: Ownership
  ): Extracted[(List[TableDecl], List[MigrationTable])] = {
    val found     = files.flatMap(declarationsIn(_, ownership))
    val declared  = found.collect { case Right(table) => table }
    val withUsers = declared.map(table => table.copy(users = usersOf(table, declared, files, ownership)))
    val migrated  = migrations.flatMap(migrationTablesIn)

    val crossModule = withUsers
      .flatMap { table =>
        table.users
          .flatMap(site =>
            site.owner match {
              case Owner.Module(user) if table.owner != Owner.Module(user) && !(site.owner.inUtils && table.owner.inUtils) => Some(user -> site)
              case _                                                                                                       => None
            }
          )
          .distinctBy(_._1)
          .map((user, site) =>
            Warning(
              WarningKind.CrossModuleTableUse,
              s"cross-module-table:${table.name}:$user",
              s"$user uses table ${table.name} owned by ${table.owner.label}",
              site.loc
            )
          )
      }
      .distinctBy(_.key)
    val byName     = withUsers.groupBy(_.name).values.toList.map(_.sortBy(t => (t.loc.path, t.loc.line)))
    val merged     = byName.map(same => same.head.copy(users = same.flatMap(_.users).distinct))
    val duplicates = byName.flatMap { same =>
      same.find(_.owner != same.head.owner).map { other =>
        Warning(
          WarningKind.DuplicateTable,
          s"duplicate-table:${other.name}",
          s"table ${other.name} is declared by ${same.map(t => s"${t.objectName} in ${t.owner.label}").mkString(", ")}",
          other.loc
        )
      }
    }
    val undeclared = migrated
      .filterNot(m => declared.exists(_.name == m.name))
      .distinctBy(_.name)
      .map(m =>
        Warning(
          WarningKind.MigrationTableWithoutTable,
          s"migration-table-without-table:${m.name}",
          s"table ${m.name} is created by a migration but no Table object declares it",
          m.loc
        )
      )

    Extracted((merged.sortBy(_.name), migrated), crossModule ++ duplicates ++ undeclared, found.collect { case Left(item) => item })
  }

  private def declarationsIn(file: ParsedFile, ownership: Ownership): List[Either[Unclassified, TableDecl]] =
    file.tree.collect { case obj: Defn.Object => obj }.flatMap { obj =>
      obj.templ.inits
        .collectFirst {
          case init if Trees.typeName(init.tpe) == "Table" => init.argClauses.headOption.flatMap(args => Trees.argument(args.values, "tableName", 0))
        }
        .flatten
        .map { arg =>
          Trees
            .stringLiteral(arg)
            .map(name => TableDecl(normalize(name), obj.name.value, ownership.ownerOf(file.pkg), Nil, Trees.loc(file, obj), columnsOf(obj)))
            .toRight(
              Unclassified(
                s"table-name:${file.source.packagePath}:${obj.name.value}",
                s"${obj.name.value} extends Table with the name ${arg.syntax}, not a string literal",
                Trees.loc(file, obj)
              )
            )
        }
    }

  private def columnsOf(obj: Defn.Object): List[TableColumn] =
    obj.templ.collect { case Term.Apply.After_4_6_0(Term.Name("column"), args) =>
      for {
        name  <- Trees.argument(args.values, "name", 0)
        codec <- Trees.argument(args.values, "codec", 1)
      } yield TableColumn(Trees.stringLiteral(name).getOrElse(Trees.compact(name)), Trees.compact(codec))
    }.flatten

  private def usersOf(
    table: TableDecl,
    declared: List[TableDecl],
    files: List[ParsedFile],
    ownership: Ownership
  ): List[Site] =
    files.flatMap { file =>
      val owner = ownership.ownerOf(file.pkg)
      file.tree
        .collect {
          case name: Term.Name if name.value == table.objectName && !isDefinitionName(name) && resolvesTo(table, declared, owner) =>
            Site(owner, Trees.enclosingName(name), Trees.loc(file, name))
        }
        .distinctBy(_.enclosing)
    }

  private def resolvesTo(
    table: TableDecl,
    declared: List[TableDecl],
    owner: Owner
  ): Boolean = {
    val candidates = declared.filter(_.objectName == table.objectName)
    candidates.find(_.owner == owner).orElse(candidates.headOption).contains(table)
  }

  private def isDefinitionName(name: Term.Name): Boolean = name.parent.exists {
    case obj: Defn.Object => obj.name eq name
    case _                => false
  }

  private def migrationTablesIn(file: SourceFile): List[MigrationTable] =
    CreateTable
      .findAllMatchIn(file.content)
      .map { m =>
        MigrationTable(normalize(m.group(1).split('.').last), Loc(file.projectPath, file.content.take(m.start).count(_ == '\n') + 1))
      }
      .toList

  private def normalize(name: String): String = name.stripPrefix("\"").stripSuffix("\"").toLowerCase
}
