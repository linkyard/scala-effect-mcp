package ch.linkyard.mcp.server

import ch.linkyard.mcp.protocol.JsonSchema
import io.circe.Json
import io.circe.JsonObject

/** Checks the `x-mcp-header` annotations of the input schema of a tool: the parameters that are copied into the http
  * header `Mcp-Param-{name}` (see the section on custom headers of the Streamable HTTP transport).
  */
private[server] object McpHeaderAnnotations:
  private val Key = "x-mcp-header"
  private val Primitive = Set("string", "integer", "boolean")
  private val TokenChars = "!#$%&'*+-.^_`|~"

  /** The header names of the annotations, or why the annotations are not valid. */
  def validate(schema: JsonSchema): Either[String, List[String]] =
    for
      reachable <- reachableAnnotations(schema)
      _ <- Either.cond(
        countAnnotations(Json.fromJsonObject(schema)) == reachable.size,
        (),
        s"$Key is only allowed on properties that are reachable through `properties`",
      )
      _ <- reachable.groupBy(_.toLowerCase).collectFirst { case (name, list) if list.size > 1 => name }
        .toLeft(()).left.map(name => s"$Key '$name' is used more than once")
    yield reachable

  private def isToken(name: String): Boolean =
    name.nonEmpty && name.forall(c => c.isLetterOrDigit && c < 128 || TokenChars.contains(c))

  /** The annotations on the properties that can be reached through a chain of `properties` only */
  private def reachableAnnotations(schema: JsonObject): Either[String, List[String]] =
    val properties = schema("properties").flatMap(_.asObject).map(_.toList).getOrElse(Nil)
    properties.foldLeft[Either[String, List[String]]](Right(Nil)) { case (acc, (property, definition)) =>
      for
        found <- acc
        own <- definition.asObject.fold[Either[String, List[String]]](Right(Nil))(annotation(property, _))
        nested <- definition.asObject.fold[Either[String, List[String]]](Right(Nil))(reachableAnnotations)
      yield found ++ own ++ nested
    }

  private def annotation(property: String, definition: JsonObject): Either[String, List[String]] =
    definition(Key) match
      case None        => Right(Nil)
      case Some(value) =>
        for
          name <- value.asString.toRight(s"$Key of '$property' has to be a string")
          _ <- Either.cond(isToken(name), (), s"$Key '$name' is not a valid header name part")
          _ <- Either.cond(
            definition("type").flatMap(_.asString).exists(Primitive.contains),
            (),
            s"$Key '$name' is only allowed on parameters of type string, integer or boolean",
          )
        yield List(name)

  /** All annotations in the schema, wherever they are (the names of properties are not keywords). */
  private def countAnnotations(json: Json): Int =
    json.fold(
      0,
      _ => 0,
      _ => 0,
      _ => 0,
      _.map(countAnnotations).sum,
      obj =>
        obj.toList.map {
          case ("properties", properties) =>
            properties.asObject.map(_.values.map(countAnnotations).sum).getOrElse(countAnnotations(properties))
          case (Key, _)   => 1
          case (_, value) => countAnnotations(value)
        }.sum,
    )
