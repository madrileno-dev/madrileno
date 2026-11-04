package workspace

import scala.util.Try

final case class McpServer(name: String, config: ujson.Value)

object McpServers {
  def read(dir: String, json: String): Either[String, List[McpServer]] =
    Try(ujson.read(json)).toEither.left.map(e => s"$dir/.mcp.json: ${e.getMessage}").flatMap { doc =>
      doc.objOpt.flatMap(_.get("mcpServers")) match {
        case None                    => Right(Nil)
        case Some(ujson.Obj(values)) => Right(values.toList.map((name, config) => McpServer(name, config)))
        case Some(_)                 => Left(s"$dir/.mcp.json: mcpServers is not an object")
      }
    }

  def combine(perMember: List[List[McpServer]]): Either[String, List[McpServer]] = {
    val all     = perMember.flatten.distinct
    val clashes = all.groupBy(_.name).collect { case (name, configs) if configs.size > 1 => name }
    if (clashes.isEmpty) Right(all)
    else Left(s"members declare different MCP servers under the same name: ${clashes.toList.sorted.mkString(", ")}")
  }

  def mergeJson(existing: Option[String], servers: List[McpServer]): Either[String, String] =
    Try(existing.map(ujson.read(_)).getOrElse(ujson.Obj())).toEither.left
      .map(e => s".mcp.json at the root: ${e.getMessage}")
      .map { doc =>
        val current = doc.obj.getOrElseUpdate("mcpServers", ujson.Obj())
        servers.foreach(server => current(server.name) = server.config)
        ujson.write(doc, indent = 2) + "\n"
      }

  def mergeToml(existing: Option[String], servers: List[McpServer]): String = {
    val tables   = split(existing.getOrElse(""))
    val managed  = servers.map(server => header(server.name) -> table(server)).toMap
    val replaced = tables.map((head, lines) => managed.getOrElse(head, lines))
    val added    = servers.filterNot(server => tables.exists(_._1 == header(server.name))).map(table)
    (replaced ++ added).map(_.mkString("\n")).mkString("\n\n") + "\n"
  }

  private def split(text: String): List[(String, List[String])] =
    text.linesIterator
      .foldLeft(List.empty[(String, List[String])]) {
        case (acc, line) if line.trim.startsWith("[") => acc :+ (line.trim        -> List(line))
        case (Nil, line)                              => List("" -> List(line))
        case (acc, line)                              => acc.init :+ (acc.last._1 -> (acc.last._2 :+ line))
      }
      .map((head, lines) => head -> lines.reverse.dropWhile(_.trim.isEmpty).reverse)
      .filter((_, lines) => lines.nonEmpty)

  private val BareKey = "[A-Za-z0-9_-]+".r

  private def header(name: String): String =
    if (BareKey.matches(name)) s"[mcp_servers.$name]" else s"[mcp_servers.${ujson.write(ujson.Str(name))}]"

  private def table(server: McpServer): List[String] = {
    val fields = server.config.objOpt.getOrElse(ujson.Obj().value)
    val values =
      fields.get("url").map(url => s"url = ${ujson.write(url)}").toList ++
        fields.get("command").map(command => s"command = ${ujson.write(command)}") ++
        fields.get("args").map(args => s"args = [${args.arr.map(ujson.write(_)).mkString(", ")}]")
    header(server.name) :: values
  }
}
