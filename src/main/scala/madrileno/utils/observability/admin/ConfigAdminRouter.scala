package madrileno.utils.observability.admin

import cats.effect.IO
import com.typesafe.config.{Config, ConfigFactory, ConfigList, ConfigObject, ConfigValue, ConfigValueType}
import io.circe.Json
import madrileno.utils.http.BaseRouter
import pl.iterators.stir.marshalling.ToResponseMarshallable
import pl.iterators.stir.server.Route

import java.util.Locale
import scala.jdk.CollectionConverters.*

class ConfigAdminRouter(merged: Config, redactedPaths: Set[String]) extends BaseRouter {

  private val declaredKeys: Set[String] =
    ConfigFactory.parseResourcesAnySyntax("application").root().keySet().asScala.toSet

  private lazy val redactedTree: Json = ConfigAdminRouter.redact(merged, declaredKeys, redactedPaths)

  val routes: Route =
    (get & pathPrefix("config") & pathEndOrSingleSlash) {
      complete(IO.pure[ToResponseMarshallable](Ok -> redactedTree))
    }
}

object ConfigAdminRouter {
  private val Redacted                     = Json.fromString("[REDACTED]")
  private val DefaultKeywords: Set[String] =
    Set("password", "secret", "credential", "access-key", "api-key", "private-key", "token", "passphrase")

  private[admin] def redact(
    config: Config,
    declaredKeys: Set[String],
    redactedPaths: Set[String]
  ): Json =
    walkObject(config.root(), "", redactedPaths, keyFilter = Some(declaredKeys))

  private def shouldRedact(
    key: String,
    path: String,
    redactedPaths: Set[String]
  ): Boolean = {
    val lower = key.toLowerCase(Locale.ROOT)
    DefaultKeywords.exists(lower.contains) || redactedPaths.contains(path)
  }

  private def walkObject(
    obj: ConfigObject,
    path: String,
    redactedPaths: Set[String],
    keyFilter: Option[Set[String]] = None
  ): Json = {
    val entries = obj.asScala.toList
      .filter { case (k, _) => keyFilter.forall(_.contains(k)) }
      .sortBy(_._1)
      .map { case (k, v) =>
        val childPath  = if (path.isEmpty) k else s"$path.$k"
        val keyRedacts = shouldRedact(k, childPath, redactedPaths)
        val rendered   = v.valueType match {
          case ConfigValueType.OBJECT => walk(v, childPath, redactedPaths, parentRedactsPrimitives = false)
          case ConfigValueType.LIST   => walk(v, childPath, redactedPaths, parentRedactsPrimitives = keyRedacts)
          case _                      => if (keyRedacts) Redacted else walk(v, childPath, redactedPaths, parentRedactsPrimitives = false)
        }
        k -> rendered
      }
    Json.obj(entries*)
  }

  private def walk(
    value: ConfigValue,
    path: String,
    redactedPaths: Set[String],
    parentRedactsPrimitives: Boolean
  ): Json = value.valueType match {
    case ConfigValueType.OBJECT =>
      value match {
        case o: ConfigObject => walkObject(o, path, redactedPaths)
        case other           => throw new IllegalStateException(s"expected ConfigObject for OBJECT, got ${other.getClass.getName}")
      }
    case ConfigValueType.LIST =>
      val list = value match {
        case l: ConfigList => l
        case other         => throw new IllegalStateException(s"expected ConfigList for LIST, got ${other.getClass.getName}")
      }
      Json.fromValues(list.asScala.toList.zipWithIndex.map { case (v, i) =>
        walk(v, s"$path[$i]", redactedPaths, parentRedactsPrimitives)
      })
    case _ if parentRedactsPrimitives => Redacted
    case ConfigValueType.STRING       => Json.fromString(String.valueOf(value.unwrapped()))
    case ConfigValueType.BOOLEAN      =>
      value.unwrapped() match {
        case b: java.lang.Boolean => Json.fromBoolean(b)
        case other                => Json.fromString(String.valueOf(other))
      }
    case ConfigValueType.NUMBER =>
      value.unwrapped() match {
        case n: java.lang.Long   => Json.fromLong(n)
        case n: Integer          => Json.fromInt(n)
        case n: java.lang.Double => Json.fromDoubleOrString(n)
        case other               => Json.fromString(String.valueOf(other))
      }
    case ConfigValueType.NULL => Json.Null
  }
}
