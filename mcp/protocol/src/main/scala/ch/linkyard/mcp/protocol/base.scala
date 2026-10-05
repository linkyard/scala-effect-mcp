package ch.linkyard.mcp.protocol

import cats.kernel.Monoid
import io.circe.Codec
import io.circe.Decoder
import io.circe.DecodingFailure
import io.circe.Encoder
import io.circe.Json
import io.circe.JsonObject
import io.circe.derivation.Configuration
import io.circe.derivation.ConfiguredCodec
import io.circe.syntax.*

import java.time.Instant
import java.util.Base64
import scala.util.Try

/** Derivation configuration used for all protocol messages (uses the default values of missing fields). */
private[protocol] given Configuration = Configuration.default.withDefaults

extension [A](codec: ConfiguredCodec[A])
  /** Same codec but None/null values are not written. */
  private[protocol] def withoutNulls: Codec.AsObject[A] =
    Codec.AsObject.from(codec, Encoder.AsObject.instance(a => codec.encodeObject(a).filter(!_._2.isNull)))

/** Builds a json object without the null values. */
private[protocol] def obj(fields: (String, Json)*): JsonObject =
  JsonObject.fromIterable(fields.filterNot(_._2.isNull))

/** Codec for an enumeration represented as a string. */
private[protocol] def stringEnumCodec[A](name: String, values: (String, A)*): Codec[A] =
  Codec.from(
    Decoder.decodeString.emap(s => values.find(_._1 == s).map(_._2).toRight(s"Unknown $name: $s")),
    Encoder.encodeString.contramap(a => values.find(_._2 == a).map(_._1).getOrElse(a.toString)),
  )

enum RequestId:
  case IdString(id: String)
  case IdNumber(id: Long)

object RequestId:
  given Encoder[RequestId] = Encoder.instance {
    case IdString(id) => id.asJson
    case IdNumber(id) => id.asJson
  }
  given Decoder[RequestId] = Decoder.instance { c =>
    c.as[String].map(IdString.apply)
      .orElse(c.as[Long].map(IdNumber.apply))
  }

enum ProgressToken:
  case TokenString(token: String)
  case TokenNumber(token: Long)

object ProgressToken:
  given Encoder[ProgressToken] = Encoder.instance {
    case TokenString(token) => token.asJson
    case TokenNumber(token) => token.asJson
  }
  given Decoder[ProgressToken] = Decoder.instance { c =>
    c.as[String].map(TokenString.apply)
      .orElse(c.as[Long].map(TokenNumber.apply))
  }

/** The `_meta` object of requests, results and notifications. */
opaque type Meta = JsonObject
object Meta:
  object Key:
    val ProgressToken = "progressToken"
    val ProtocolVersion = "io.modelcontextprotocol/protocolVersion"
    val ClientInfo = "io.modelcontextprotocol/clientInfo"
    val ClientCapabilities = "io.modelcontextprotocol/clientCapabilities"
    val LogLevel = "io.modelcontextprotocol/logLevel"
    val ServerInfo = "io.modelcontextprotocol/serverInfo"
    val SubscriptionId = "io.modelcontextprotocol/subscriptionId"

  def apply(values: (String, Json)*): Meta = JsonObject(values*)
  def apply(obj: JsonObject): Meta = obj
  val empty: Meta = JsonObject.empty
  def withProgressToken(t: ProgressToken): Meta = JsonObject(Key.ProgressToken -> t.asJson)
  extension (m: Meta)
    def progressToken: Option[ProgressToken] = m(Key.ProgressToken).flatMap(_.as[ProgressToken].toOption)
    def get(key: String): Option[Json] = m(key)
    def getAs[A: Decoder](key: String): Option[Either[DecodingFailure, A]] = m(key).map(_.as[A])
    def add(key: String, value: Json): Meta = m.add(key, value)
    def remove(key: String): Meta = m.remove(key)
    def isEmpty: Boolean = m.isEmpty
    def asJsonObject: JsonObject = m
  given Decoder[Meta] = Decoder[Option[JsonObject]].map(_.getOrElse(JsonObject.empty))
  given Encoder[Meta] = Encoder[Option[JsonObject]].contramap(o => if o.isEmpty then None else Some(o))
  given Monoid[Meta]:
    def combine(x: Meta, y: Meta): Meta = y.toMap.foldLeft(x)((m, e) => m.add(e._1, e._2))
    def empty: Meta = Meta.empty

enum Role:
  case User
  case Assistant

object Role:
  given Codec[Role] = stringEnumCodec("role", "user" -> User, "assistant" -> Assistant)

type Cursor = String

/** A json schema (an object). */
type JsonSchema = JsonObject

enum IconTheme:
  case Light
  case Dark
object IconTheme:
  given Codec[IconTheme] = stringEnumCodec("icon theme", "light" -> Light, "dark" -> Dark)

case class Icon(
  src: String,
  mimeType: Option[String] = None,
  sizes: Option[List[String]] = None,
  theme: Option[IconTheme] = None,
)
object Icon:
  given Codec.AsObject[Icon] = ConfiguredCodec.derived[Icon].withoutNulls

/** Name and version of a client or server. */
case class Implementation(
  name: String,
  version: String,
  title: Option[String] = None,
  description: Option[String] = None,
  websiteUrl: Option[String] = None,
  icons: Option[List[Icon]] = None,
)
object Implementation:
  given Codec.AsObject[Implementation] = ConfiguredCodec.derived[Implementation].withoutNulls

case class Annotations(
  audience: Option[List[Role]] = None,
  priority: Option[Double] = None,
  lastModified: Option[Instant] = None,
)
object Annotations:
  given Codec.AsObject[Annotations] = ConfiguredCodec.derived[Annotations].withoutNulls

enum Content:
  case Text(text: String, annotations: Option[Annotations] = None, _meta: Meta = Meta.empty)
  case Image(
    data: Array[Byte],
    mimeType: String,
    annotations: Option[Annotations] = None,
    _meta: Meta = Meta.empty,
  )
  case Audio(
    data: Array[Byte],
    mimeType: String,
    annotations: Option[Annotations] = None,
    _meta: Meta = Meta.empty,
  )
  case ResourceLink(
    uri: String,
    name: String,
    title: Option[String] = None,
    description: Option[String] = None,
    mimeType: Option[String] = None,
    size: Option[Long] = None,
    annotations: Option[Annotations] = None,
    icons: Option[List[Icon]] = None,
    _meta: Meta = Meta.empty,
  )
  case EmbeddedResource(
    resource: Resource.Contents,
    annotations: Option[Annotations] = None,
    _meta: Meta = Meta.empty,
  )

object Content:
  given Encoder[Content] = Encoder.instance {
    case Text(text, annotations, _meta) => obj(
        "type" -> "text".asJson,
        "text" -> text.asJson,
        "annotations" -> annotations.asJson,
        "_meta" -> _meta.asJson,
      ).asJson

    case Image(data, mimeType, annotations, _meta) => obj(
        "type" -> "image".asJson,
        "data" -> Base64.getEncoder.encodeToString(data).asJson,
        "mimeType" -> mimeType.asJson,
        "annotations" -> annotations.asJson,
        "_meta" -> _meta.asJson,
      ).asJson

    case Audio(data, mimeType, annotations, _meta) => obj(
        "type" -> "audio".asJson,
        "data" -> Base64.getEncoder.encodeToString(data).asJson,
        "mimeType" -> mimeType.asJson,
        "annotations" -> annotations.asJson,
        "_meta" -> _meta.asJson,
      ).asJson

    case ResourceLink(uri, name, title, description, mimeType, size, annotations, icons, _meta) => obj(
        "type" -> "resource_link".asJson,
        "uri" -> uri.asJson,
        "name" -> name.asJson,
        "title" -> title.asJson,
        "description" -> description.asJson,
        "mimeType" -> mimeType.asJson,
        "size" -> size.asJson,
        "annotations" -> annotations.asJson,
        "icons" -> icons.asJson,
        "_meta" -> _meta.asJson,
      ).asJson

    case EmbeddedResource(resource, annotations, _meta) => obj(
        "type" -> "resource".asJson,
        "resource" -> resource.asJson,
        "annotations" -> annotations.asJson,
        "_meta" -> _meta.asJson,
      ).asJson
  }

  private val base64Decoder: Decoder[Array[Byte]] =
    Decoder.decodeString.emap(str => Try(Base64.getDecoder.decode(str)).toEither.left.map(_ => "Invalid base64"))

  given Decoder[Content] = Decoder.instance { c =>
    c.downField("type").as[String].flatMap {
      case "text" =>
        for
          text <- c.downField("text").as[String]
          annotations <- c.downField("annotations").as[Option[Annotations]]
          _meta <- c.downField("_meta").as[Meta]
        yield Content.Text(text, annotations, _meta)

      case "image" =>
        for
          data <- c.downField("data").as[Array[Byte]](using base64Decoder)
          mimeType <- c.downField("mimeType").as[String]
          annotations <- c.downField("annotations").as[Option[Annotations]]
          _meta <- c.downField("_meta").as[Meta]
        yield Content.Image(data, mimeType, annotations, _meta)

      case "audio" =>
        for
          data <- c.downField("data").as[Array[Byte]](using base64Decoder)
          mimeType <- c.downField("mimeType").as[String]
          annotations <- c.downField("annotations").as[Option[Annotations]]
          _meta <- c.downField("_meta").as[Meta]
        yield Content.Audio(data, mimeType, annotations, _meta)

      case "resource_link" =>
        for
          uri <- c.downField("uri").as[String]
          name <- c.downField("name").as[String]
          title <- c.downField("title").as[Option[String]]
          description <- c.downField("description").as[Option[String]]
          mimeType <- c.downField("mimeType").as[Option[String]]
          size <- c.downField("size").as[Option[Long]]
          annotations <- c.downField("annotations").as[Option[Annotations]]
          icons <- c.downField("icons").as[Option[List[Icon]]]
          _meta <- c.downField("_meta").as[Meta]
        yield Content.ResourceLink(uri, name, title, description, mimeType, size, annotations, icons, _meta)

      case "resource" =>
        for
          resource <- c.downField("resource").as[Resource.Contents]
          annotations <- c.downField("annotations").as[Option[Annotations]]
          _meta <- c.downField("_meta").as[Meta]
        yield Content.EmbeddedResource(resource, annotations, _meta)

      case other =>
        Left(DecodingFailure(s"Unknown content type: $other", c.history))
    }
  }

case class Resource(
  uri: String,
  name: String,
  title: Option[String] = None,
  description: Option[String] = None,
  mimeType: Option[String] = None,
  size: Option[Long] = None,
  annotations: Option[Annotations] = None,
  icons: Option[List[Icon]] = None,
  _meta: Meta = Meta.empty,
)

object Resource:
  given Codec.AsObject[Resource] = ConfiguredCodec.derived[Resource].withoutNulls

  case class Template(
    uriTemplate: String,
    name: String,
    title: Option[String] = None,
    description: Option[String] = None,
    mimeType: Option[String] = None,
    annotations: Option[Annotations] = None,
    icons: Option[List[Icon]] = None,
    _meta: Meta = Meta.empty,
  )
  object Template:
    given Codec.AsObject[Template] = ConfiguredCodec.derived[Template].withoutNulls

  /** Contents of a resource (text or binary). */
  enum Contents:
    case Text(uri: String, mimeType: Option[String], text: String, _meta: Meta = Meta.empty)
    case Blob(uri: String, mimeType: Option[String], blob: String, _meta: Meta = Meta.empty)
    def uri: String
    def mimeType: Option[String]

  object Contents:
    given Encoder[Contents] = Encoder.instance {
      case Text(uri, mimeType, text, _meta) => obj(
          "uri" -> uri.asJson,
          "mimeType" -> mimeType.asJson,
          "text" -> text.asJson,
          "_meta" -> _meta.asJson,
        ).asJson
      case Blob(uri, mimeType, blob, _meta) => obj(
          "uri" -> uri.asJson,
          "mimeType" -> mimeType.asJson,
          "blob" -> blob.asJson,
          "_meta" -> _meta.asJson,
        ).asJson
    }

    given Decoder[Contents] = Decoder.instance { c =>
      for
        uri <- c.downField("uri").as[String]
        mimeType <- c.downField("mimeType").as[Option[String]]
        _meta <- c.downField("_meta").as[Meta]
        text <- c.downField("text").as[Option[String]]
        contents <- text match
          case Some(text) => Right(Text(uri, mimeType, text, _meta))
          case None       => c.downField("blob").as[String].map(Blob(uri, mimeType, _, _meta))
      yield contents
    }

/** Marker for the results of the server. */
trait McpResponse

/** A result that is complete (`resultType` is "complete" when written, ignored when read). */
private[protocol] def completeResultCodec[A](codec: ConfiguredCodec[A]): Codec.AsObject[A] =
  Codec.AsObject.from(
    codec,
    Encoder.AsObject.instance(a =>
      codec.encodeObject(a).filter(!_._2.isNull).add("resultType", "complete".asJson)
    ),
  )
