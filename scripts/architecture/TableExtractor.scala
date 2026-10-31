package architecture

import scala.meta.{Member as _, *}

object TableExtractor {
  def extract(
    files: List[ParsedFile],
    migrations: List[SourceFile],
    ownership: Ownership
  ): Extracted[List[TableDecl]] = {
    val classes   = files.flatMap(_.tree.collect { case cls: Defn.Class => cls.name.value -> cls }).toMap
    val found     = files.flatMap(declarationsIn(_, classes, ownership))
    val declared  = found.collect { case Right(table) => table }
    val local     = localNames(files, declared, ownership)
    val withUsers = declared.map(table => table.copy(users = usersOf(table, declared, local, files, ownership)))

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
    val undeclared = MigrationExtractor
      .existing(migrations)
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

    Extracted(merged.sortBy(_.name), crossModule ++ duplicates ++ undeclared, found.collect { case Left(item) => item })
  }

  private def declarationsIn(
    file: ParsedFile,
    classes: Map[String, Defn.Class],
    ownership: Ownership
  ): List[Either[Unclassified, TableDecl]] =
    file.tree.collect { case obj: Defn.Object => obj }.flatMap { obj =>
      obj.templ.inits.view.flatMap(tableNameOf(_, classes, Set.empty)).headOption.map { arg =>
        arg
          .flatMap(Trees.stringLiteral)
          .map(name =>
            TableDecl(MigrationExtractor.tableName(name), obj.name.value, ownership.ownerOf(file.pkg), Nil, Trees.loc(file, obj), columnsOf(obj))
          )
          .toRight(
            Unclassified(
              s"table-name:${file.source.packagePath}:${obj.name.value}",
              s"${obj.name.value} extends Table with the name ${arg.fold("the map can't see")(_.syntax)}, not a string literal",
              Trees.loc(file, obj)
            )
          )
      }
    }

  private def tableNameOf(
    init: Init,
    classes: Map[String, Defn.Class],
    seen: Set[String]
  ): Option[Option[Term]] = {
    val args = init.argClauses.headOption.toList.flatMap(_.values)
    Trees.typeName(init.tpe) match {
      case "Table"             => Some(Trees.argument(args, "tableName", 0))
      case name if !seen(name) =>
        classes.get(name).flatMap { cls =>
          val params = cls.ctor.paramClauses.headOption.toList.flatMap(_.values).map(_.name.value)
          cls.templ.inits.view
            .flatMap(tableNameOf(_, classes, seen + name))
            .headOption
            .map(_.flatMap {
              case Term.Name(param) if params.contains(param) => Trees.argument(args, param, params.indexOf(param))
              case other                                      => Some(other)
            })
        }
      case _ => None
    }
  }

  private def columnsOf(obj: Defn.Object): List[TableColumn] =
    obj.templ.collect { case Term.Apply.After_4_6_0(Term.Name("column") | Term.ApplyType.After_4_6_0(Term.Name("column"), _), args) =>
      for {
        name  <- Trees.argument(args.values, "name", 0)
        codec <- Trees.argument(args.values, "codec", 1)
      } yield TableColumn(Trees.stringLiteral(name).getOrElse(Trees.compact(name)), Trees.compact(codec))
    }.flatten

  private def localNames(
    files: List[ParsedFile],
    declared: List[TableDecl],
    ownership: Ownership
  ): Map[Owner, Set[String]] =
    files
      .flatMap { file =>
        val owner = ownership.ownerOf(file.pkg)
        file.tree.collect {
          case d: Defn.Object if !declared.exists(t => t.objectName == d.name.value && t.owner == owner) => owner -> d.name.value
          case d: Defn.Class                                                                             => owner -> d.name.value
          case d: Defn.Enum                                                                              => owner -> d.name.value
        }
      }
      .groupMap(_._1)(_._2)
      .view
      .mapValues(_.toSet)
      .toMap

  private def usersOf(
    table: TableDecl,
    declared: List[TableDecl],
    local: Map[Owner, Set[String]],
    files: List[ParsedFile],
    ownership: Ownership
  ): List[Site] =
    files.flatMap { file =>
      val owner = ownership.ownerOf(file.pkg)
      file.tree
        .collect {
          case name: Term.Name
              if name.value == table.objectName && !isDefinitionName(name) && !shadowed(table, owner, local) && resolvesTo(table, declared, owner) =>
            Site(owner, Trees.enclosingName(file, name), Trees.loc(file, name))
        }
        .distinctBy(_.enclosing)
    }

  private def shadowed(
    table: TableDecl,
    owner: Owner,
    local: Map[Owner, Set[String]]
  ): Boolean =
    owner != table.owner && local.get(owner).exists(_(table.objectName))

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

}
