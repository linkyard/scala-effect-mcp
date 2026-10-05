package ch.linkyard.mcp.server

import ch.linkyard.mcp.protocol.JsonSchema
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*

/** A field of a form that the user is asked to fill in (elicitation). */
enum ElicitationField:
  case Text(name: String, required: Boolean, title: Option[String] = None, description: Option[String] = None)
  case YesNo(name: String, required: Boolean, title: Option[String] = None, description: Option[String] = None)
  case Number(name: String, required: Boolean, title: Option[String] = None, description: Option[String] = None)

  /** One of the options (the value is the option itself) */
  case Choice(
    name: String,
    required: Boolean,
    options: List[String],
    title: Option[String] = None,
    description: Option[String] = None,
  )

  def name: String
  def title: Option[String]
  def description: Option[String]
  def required: Boolean

  private[server] def toJsonSchema: Json =
    val kind = this match
      case _: Text   => "type" -> "string".asJson
      case _: YesNo  => "type" -> "boolean".asJson
      case _: Number => "type" -> "number".asJson
      case _: Choice => "type" -> "string".asJson
    val options = this match
      case Choice(_, _, options, _, _) => Some("enum" -> options.asJson)
      case _                           => None
    Json.obj(
      (List(kind, "title" -> title.getOrElse(name).asJson) ++ description.map("description" -> _.asJson) ++ options)*
    )

object ElicitationField:
  extension (fields: Seq[ElicitationField])
    def toJsonSchema: JsonSchema = JsonObject(
      "type" -> "object".asJson,
      "properties" -> Json.obj(fields.map(f => f.name -> f.toJsonSchema)*),
      "required" -> fields.filter(_.required).map(_.name).asJson,
    )
