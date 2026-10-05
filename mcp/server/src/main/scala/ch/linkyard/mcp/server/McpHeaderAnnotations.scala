package ch.linkyard.mcp.server

import ch.linkyard.mcp.protocol.JsonSchema
import io.circe.Json
import io.circe.JsonObject

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.util.Try

/** Checks the `x-mcp-header` annotations of the input schema of a tool: the parameters that are copied into the http
  * header `Mcp-Param-{name}` (see the section on custom headers of the Streamable HTTP transport).
  */
private[server] object McpHeaderAnnotations:
  private val Key = "x-mcp-header"
  private val Primitive = Set("string", "integer", "boolean")
  private val TokenChars = "!#$%&'*+-.^_`|~"

  /** The header names of the annotations, or why the annotations are not valid. */
  def validate(schema: JsonSchema): Either[String, List[String]] = parameters(schema).map(_.map(_._2))

  /** The annotated parameters: the path of the property in the arguments and the header name part. */
  def parameters(schema: JsonSchema): Either[String, List[(List[String], String)]] =
    for
      reachable <- reachableAnnotations(schema, Nil)
      _ <- Either.cond(
        countAnnotations(Json.fromJsonObject(schema)) == reachable.size,
        (),
        s"$Key is only allowed on properties that are reachable through `properties`",
      )
      _ <- reachable.map(_._2).groupBy(_.toLowerCase).collectFirst { case (name, list) if list.size > 1 => name }
        .toLeft(()).left.map(name => s"$Key '$name' is used more than once")
    yield reachable

  private def isToken(name: String): Boolean =
    name.nonEmpty && name.forall(c => c.isLetterOrDigit && c < 128 || TokenChars.contains(c))

  /** The annotations on the properties that can be reached through a chain of `properties` only */
  private def reachableAnnotations(
    schema: JsonObject,
    prefix: List[String],
  ): Either[String, List[(List[String], String)]] =
    val properties = schema("properties").flatMap(_.asObject).map(_.toList).getOrElse(Nil)
    properties.foldLeft[Either[String, List[(List[String], String)]]](Right(Nil)) {
      case (acc, (property, definition)) =>
        val path = prefix :+ property
        for
          found <- acc
          own <- definition.asObject.fold[Either[String, List[String]]](Right(Nil))(annotation(property, _))
          nested <- definition.asObject.fold[Either[String, List[(List[String], String)]]](Right(Nil))(
            reachableAnnotations(_, path)
          )
        yield found ++ own.map(path -> _) ++ nested
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

  private val ParamPrefix = "mcp-param-"
  private val Base64Prefix = "=?base64?"
  private val Base64Suffix = "?="

  /** Validates the `Mcp-Param-*` headers of a request against the arguments of a tool call: the header of every
    * parameter that has a value has to be present and, after decoding, equal to the value. Parameters without a value
    * (absent or null) are not expected in a header.
    *
    * @param headers
    *   the headers by their lower case name
    * @return
    *   why the request has to be rejected
    */
  def validateRequest(
    schema: JsonSchema,
    arguments: JsonObject,
    headers: Map[String, String],
  ): Either[String, Unit] =
    // invalid schemas are not listed, a call of such a tool is not validated
    parameters(schema).getOrElse(Nil).foldLeft[Either[String, Unit]](Right(())) { case (acc, (path, name)) =>
      acc.flatMap(_ =>
        valueAt(Json.fromJsonObject(arguments), path).filterNot(_.isNull).fold[Either[String, Unit]](Right(())) {
          value =>
            val header = s"Mcp-Param-$name"
            for
              raw <- headers.get(ParamPrefix + name.toLowerCase).toRight(s"Header mismatch: missing $header header")
              decoded <- decode(raw).left.map(reason => s"Header mismatch: $header header: $reason")
              _ <- Either.cond(
                matches(value, decoded),
                (),
                s"Header mismatch: $header header value '$decoded' does not match the body value",
              )
            yield ()
        }
      )
    }

  private def valueAt(json: Json, path: List[String]): Option[Json] =
    path.foldLeft(Option(json))((current, key) => current.flatMap(_.asObject).flatMap(_(key)))

  /** Decodes the Base64 sentinel `=?base64?<value>?=`, other values have to be plain ASCII (visible characters, space
    * and tab).
    */
  private def decode(raw: String): Either[String, String] =
    if raw.length >= Base64Prefix.length + Base64Suffix.length && raw.startsWith(Base64Prefix) &&
      raw.endsWith(Base64Suffix)
    then
      val encoded = raw.substring(Base64Prefix.length, raw.length - Base64Suffix.length)
      Either.cond(encoded.length % 4 == 0, encoded, "invalid Base64 padding")
        .flatMap(e =>
          Try(new String(Base64.getDecoder.decode(e), StandardCharsets.UTF_8)).toEither.left.map(_ => "invalid Base64")
        )
    else Either.cond(raw.forall(c => c == ' ' || c == '\t' || (c >= 0x21 && c <= 0x7e)), raw, "invalid characters")

  /** Strings are compared as they are, integers numerically (`42.0` equals `42`), booleans as `true` or `false`. */
  private def matches(value: Json, header: String): Boolean =
    value.fold(
      false,
      _.toString == header,
      n => Try(BigDecimal(header)).toOption.exists(h => n.toBigDecimal.exists(_.compare(h) == 0)),
      _ == header,
      _ => false,
      _ => false,
    )

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
