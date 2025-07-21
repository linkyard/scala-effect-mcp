package ch.linkyard.mcp.protocol

import cats.kernel.Monoid
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*

import java.time.Instant
import java.util.Base64
import scala.util.Try

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

opaque type Meta = JsonObject
object Meta:
  def apply(values: (String, Json)*): Meta = JsonObject(values*)
  def apply(obj: JsonObject): Meta = obj
  val empty: Meta = JsonObject.empty
  def withRequestRelation(req: RequestId): Meta = JsonObject("relatesTo" -> req.asJson)
  def withProgressToken(t: ProgressToken): Meta = JsonObject("progressToken" -> t.asJson)
  extension (m: Meta)
    def progressToken: Option[ProgressToken] = m("progressToken").flatMap(_.as[ProgressToken].toOption)
    def relatesTo: Option[RequestId] = m("relatesTo").flatMap(_.as[RequestId].toOption)
    def get(key: String): Option[Json] = m(key)
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
  given Encoder[Role] = Encoder.instance {
    case User      => "user".asJson
    case Assistant => "assistant".asJson
  }
  given Decoder[Role] = Decoder.instance { c =>
    c.as[String].flatMap {
      case "user"      => Right(User)
      case "assistant" => Right(Assistant)
      case other       => Left(io.circe.DecodingFailure(s"Unknown role: $other", c.history))
    }
  }

type Cursor = String

type JsonSchema = JsonObject

enum Content:
  case Text(text: String, annotations: Option[Resource.Annotations] = None, _meta: Meta = Meta.empty)
  case Image(
    data: Array[Byte],
    mimeType: String,
    annotations: Option[Resource.Annotations] = None,
    _meta: Meta = Meta.empty,
  )
  case Audio(
    data: Array[Byte],
    mimeType: String,
    annotations: Option[Resource.Annotations] = None,
    _meta: Meta = Meta.empty,
  )
  case ResourceLink(
    uri: String,
    name: Option[String],
    description: Option[String],
    mimeType: Option[String],
    size: Option[Long],
    annotations: Option[Resource.Annotations] = None,
    _meta: Meta = Meta.empty,
  )
  case EmbeddedResource(
    resource: Resource.Embedded,
    annotations: Option[Resource.Annotations] = None,
    _meta: Meta = Meta.empty,
  )

object Content:
  given Encoder[Content] = Encoder.instance {
    case Text(text, annotations, _meta) => Json.obj(
        "type" -> "text".asJson,
        "text" -> text.asJson,
        "annotations" -> annotations.asJson,
        "_meta" -> _meta.asJson,
      )

    case Image(data, mimeType, annotations, _meta) => Json.obj(
        "type" -> "image".asJson,
        "data" -> Base64.getEncoder.encodeToString(data).asJson,
        "mimeType" -> mimeType.asJson,
        "annotations" -> annotations.asJson,
        "_meta" -> _meta.asJson,
      )

    case Audio(data, mimeType, annotations, _meta) => Json.obj(
        "type" -> "audio".asJson,
        "data" -> Base64.getEncoder.encodeToString(data).asJson,
        "mimeType" -> mimeType.asJson,
        "annotations" -> annotations.asJson,
        "_meta" -> _meta.asJson,
      )

    case ResourceLink(uri, name, description, mimeType, size, annotations, _meta) => Json.obj(
        "type" -> "resource_link".asJson,
        "uri" -> uri.asJson,
        "name" -> name.asJson,
        "description" -> description.asJson,
        "mimeType" -> mimeType.asJson,
        "size" -> size.asJson,
        "annotations" -> annotations.asJson,
        "_meta" -> _meta.asJson,
      )

    case EmbeddedResource(resource, annotations, _meta) => Json.obj(
        "type" -> "resource".asJson,
        "resource" -> resource.asJson,
        "annotations" -> annotations.asJson,
        "_meta" -> _meta.asJson,
      )
  }

private def base64Decoder: Decoder[Array[Byte]] =
  Decoder.decodeString.emap(str => Try(Base64.getDecoder.decode(str)).toEither.left.map(_ => s"Invalid base64"))

given Decoder[Content] = Decoder.instance { c =>
  c.downField("type").as[String].flatMap {
    case "text" =>
      for
        text <- c.downField("text").as[String]
        annotations <- c.downField("annotations").as[Option[Resource.Annotations]]
        _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
      yield Content.Text(text, annotations, _meta)

    case "image" =>
      for
        data <- c.downField("data").as[Array[Byte]](using base64Decoder)
        mimeType <- c.downField("mimeType").as[String]
        annotations <- c.downField("annotations").as[Option[Resource.Annotations]]
        _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
      yield Content.Image(data, mimeType, annotations, _meta)

    case "audio" =>
      for
        data <- c.downField("data").as[Array[Byte]](using base64Decoder)
        mimeType <- c.downField("mimeType").as[String]
        annotations <- c.downField("annotations").as[Option[Resource.Annotations]]
        _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
      yield Content.Audio(data, mimeType, annotations, _meta)

    case "resource_link" =>
      for
        uri <- c.downField("uri").as[String]
        name <- c.downField("name").as[Option[String]]
        description <- c.downField("description").as[Option[String]]
        mimeType <- c.downField("mimeType").as[Option[String]]
        size <- c.downField("size").as[Option[Long]]
        annotations <- c.downField("annotations").as[Option[Resource.Annotations]]
        _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
      yield Content.ResourceLink(uri, name, description, mimeType, size, annotations, _meta)

    case "resource" =>
      for
        resource <- c.downField("resource").as[Resource.Embedded]
        annotations <- c.downField("annotations").as[Option[Resource.Annotations]]
        _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
      yield Content.EmbeddedResource(resource, annotations, _meta)

    case other =>
      Left(io.circe.DecodingFailure(s"Unknown content type: $other", c.history))
  }
}

case class Resource(
  uri: String,
  name: String,
  title: Option[String],
  description: Option[String],
  mimeType: Option[String],
  size: Option[Long],
  annotations: Option[Resource.Annotations] = None,
  _meta: Meta = Meta.empty,
)

object Resource:
  given Encoder.AsObject[Resource] = Encoder.AsObject.instance { resource =>
    JsonObject(
      "uri" -> resource.uri.asJson,
      "name" -> resource.name.asJson,
      "title" -> resource.title.asJson,
      "description" -> resource.description.asJson,
      "mimeType" -> resource.mimeType.asJson,
      "size" -> resource.size.asJson,
      "annotations" -> resource.annotations.asJson,
      "_meta" -> resource._meta.asJson,
    )
  }
  given Decoder[Resource] = Decoder.instance { c =>
    for
      uri <- c.downField("uri").as[String]
      name <- c.downField("name").as[String]
      title <- c.downField("title").as[Option[String]]
      description <- c.downField("description").as[Option[String]]
      mimeType <- c.downField("mimeType").as[Option[String]]
      size <- c.downField("size").as[Option[Long]]
      annotations <- c.downField("annotations").as[Option[Annotations]]
      _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
    yield Resource(uri, name, title, description, mimeType, size, annotations, _meta)
  }

  enum Embedded:
    case Text(uri: String, title: Option[String], mimeType: Option[String], text: String)
    case Blob(uri: String, title: Option[String], mimeType: Option[String], blob: String)
    def uri: String
    def title: Option[String]
    def mimeType: Option[String]

  object Embedded:
    given Encoder[Embedded] = Encoder.instance {
      case Text(uri, title, mimeType, text) =>
        JsonObject(
          "uri" -> uri.asJson,
          "title" -> title.asJson,
          "mimeType" -> mimeType.asJson,
          "text" -> text.asJson,
        ).asJson
      case Blob(uri, title, mimeType, blob) =>
        JsonObject(
          "uri" -> uri.asJson,
          "title" -> title.asJson,
          "mimeType" -> mimeType.asJson,
          "blob" -> blob.asJson,
        ).asJson
    }

    given Decoder[Embedded] = Decoder.instance { c =>
      // Try to decode as Text first (has text field)
      c.downField("text").as[String].map { text =>
        for
          uri <- c.downField("uri").as[String]
          title <- c.downField("title").as[Option[String]]
          mimeType <- c.downField("mimeType").as[Option[String]]
        yield Text(uri, title, mimeType, text)
      }.getOrElse {
        // If no text field, try as Blob
        for
          uri <- c.downField("uri").as[String]
          title <- c.downField("title").as[Option[String]]
          mimeType <- c.downField("mimeType").as[Option[String]]
          blob <- c.downField("blob").as[String]
        yield Blob(uri, title, mimeType, blob)
      }
    }

  case class Template(
    uriTemplate: String,
    name: String,
    title: Option[String],
    description: Option[String],
    mimeType: Option[String],
    annotations: Option[Resource.Annotations] = None,
    _meta: Meta = Meta.empty,
  )
  object Template:
    given Encoder.AsObject[Template] = Encoder.AsObject.instance { template =>
      JsonObject(
        "uriTemplate" -> template.uriTemplate.asJson,
        "name" -> template.name.asJson,
        "title" -> template.title.asJson,
        "description" -> template.description.asJson,
        "mimeType" -> template.mimeType.asJson,
        "annotations" -> template.annotations.asJson,
        "_meta" -> template._meta.asJson,
      )
    }
    given Decoder[Template] = Decoder.instance { c =>
      for
        uriTemplate <- c.downField("uriTemplate").as[String]
        name <- c.downField("name").as[String]
        title <- c.downField("title").as[Option[String]]
        description <- c.downField("description").as[Option[String]]
        mimeType <- c.downField("mimeType").as[Option[String]]
        annotations <- c.downField("annotations").as[Option[Annotations]]
        _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
      yield Template(uriTemplate, name, title, description, mimeType, annotations, _meta)
    }

  case class Annotations(
    audience: Option[List[Role]],
    priority: Option[Double],
    lastModified: Option[Instant],
  )

  object Annotations:
    given Encoder[Annotations] = Encoder.instance { ann =>
      JsonObject(
        "audience" -> ann.audience.asJson,
        "priority" -> ann.priority.asJson,
        "lastModified" -> ann.lastModified.asJson,
      ).asJson
    }

    given Decoder[Annotations] = Decoder.instance { c =>
      for
        audience <- c.downField("audience").as[Option[List[Role]]]
        priority <- c.downField("priority").as[Option[Double]]
        lastModified <- c.downField("lastModified").as[Option[Instant]]
      yield Annotations(audience, priority, lastModified)
    }

  enum Contents:
    case Text(uri: String, mimeType: Option[String], text: String, _meta: Meta = Meta.empty)
    case Blob(uri: String, mimeType: Option[String], blob: String, _meta: Meta = Meta.empty)

  object Contents:
    given Encoder[Contents] = Encoder.instance {
      case Text(uri, mimeType, text, _meta) => Json.obj(
          "uri" -> uri.asJson,
          "mimeType" -> mimeType.asJson,
          "text" -> text.asJson,
          "_meta" -> _meta.asJson,
        )
      case Blob(uri, mimeType, blob, _meta) => Json.obj(
          "uri" -> uri.asJson,
          "mimeType" -> mimeType.asJson,
          "blob" -> blob.asJson,
          "_meta" -> _meta.asJson,
        )
    }

    given Decoder[Contents] = Decoder.instance { c =>
      // Try to decode as Text first (has text field)
      c.downField("text").as[String].map { text =>
        for
          uri <- c.downField("uri").as[String]
          mimeType <- c.downField("mimeType").as[Option[String]]
          _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
        yield Text(uri, mimeType, text, _meta)
      }.getOrElse {
        // If no text field, try as Blob
        for
          uri <- c.downField("uri").as[String]
          mimeType <- c.downField("mimeType").as[Option[String]]
          blob <- c.downField("blob").as[String]
          _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
        yield Blob(uri, mimeType, blob, _meta)
      }
    }

enum LoggingLevel extends Ordered[LoggingLevel]:
  case Debug
  case Info
  case Notice
  case Warning
  case Error
  case Critical
  case Alert
  case Emergency

  override def compare(that: LoggingLevel): Int = ordinal.compare(that.ordinal)
end LoggingLevel
object LoggingLevel:
  given Encoder[LoggingLevel] = Encoder.instance {
    case Debug     => "debug".asJson
    case Info      => "info".asJson
    case Notice    => "notice".asJson
    case Warning   => "warning".asJson
    case Error     => "error".asJson
    case Critical  => "critical".asJson
    case Alert     => "alert".asJson
    case Emergency => "emergency".asJson
  }

  given Decoder[LoggingLevel] = Decoder.instance { c =>
    c.as[String].flatMap {
      case "debug"     => Right(Debug)
      case "info"      => Right(Info)
      case "notice"    => Right(Notice)
      case "warning"   => Right(Warning)
      case "error"     => Right(Error)
      case "critical"  => Right(Critical)
      case "alert"     => Right(Alert)
      case "emergency" => Right(Emergency)
      case other       => Left(io.circe.DecodingFailure(s"Unknown logging level: $other", c.history))
    }
  }
