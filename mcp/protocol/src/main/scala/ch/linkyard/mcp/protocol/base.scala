package ch.linkyard.mcp.protocol

import io.circe.Decoder
import io.circe.Encoder
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
  case Text(text: String, annotations: Option[Resource.Annotations] = None, _meta: Option[JsonObject] = None)
  case Image(
    data: Array[Byte],
    mimeType: String,
    annotations: Option[Resource.Annotations] = None,
    _meta: Option[JsonObject] = None,
  )
  case Audio(
    data: Array[Byte],
    mimeType: String,
    annotations: Option[Resource.Annotations] = None,
    _meta: Option[JsonObject] = None,
  )
  case ResourceLink(
    uri: String,
    name: Option[String],
    description: Option[String],
    mimeType: Option[String],
    size: Option[Long],
    annotations: Option[Resource.Annotations] = None,
    _meta: Option[JsonObject] = None,
  )
  case EmbeddedResource(
    resource: Resource.Embedded,
    annotations: Option[Resource.Annotations] = None,
    _meta: Option[JsonObject] = None,
  )

object Content:
  given Encoder[Content] = Encoder.instance {
    case Text(text, annotations, _meta) =>
      val base = JsonObject(
        "type" -> "text".asJson,
        "text" -> text.asJson,
      )
      val withAnnotations = annotations.map(ann => base.add("annotations", ann.asJson)).getOrElse(base)
      val withMeta = _meta.map(meta => withAnnotations.add("_meta", meta.toJson)).getOrElse(withAnnotations)
      withMeta.asJson

    case Image(data, mimeType, annotations, _meta) =>
      val base = JsonObject(
        "type" -> "image".asJson,
        "data" -> Base64.getEncoder.encodeToString(data).asJson,
        "mimeType" -> mimeType.asJson,
      )
      val withAnnotations = annotations.map(ann => base.add("annotations", ann.asJson)).getOrElse(base)
      val withMeta = _meta.map(meta => withAnnotations.add("_meta", meta.toJson)).getOrElse(withAnnotations)
      withMeta.asJson

    case Audio(data, mimeType, annotations, _meta) =>
      val base = JsonObject(
        "type" -> "audio".asJson,
        "data" -> Base64.getEncoder.encodeToString(data).asJson,
        "mimeType" -> mimeType.asJson,
      )
      val withAnnotations = annotations.map(ann => base.add("annotations", ann.asJson)).getOrElse(base)
      val withMeta = _meta.map(meta => withAnnotations.add("_meta", meta.toJson)).getOrElse(withAnnotations)
      withMeta.asJson

    case ResourceLink(uri, name, description, mimeType, size, annotations, _meta) =>
      val base = JsonObject(
        "type" -> "resource_link".asJson,
        "uri" -> uri.asJson,
        "name" -> name.asJson,
        "description" -> description.asJson,
        "mimeType" -> mimeType.asJson,
        "size" -> size.asJson,
      )
      val withAnnotations = annotations.map(ann => base.add("annotations", ann.asJson)).getOrElse(base)
      val withMeta = _meta.map(meta => withAnnotations.add("_meta", meta.toJson)).getOrElse(withAnnotations)
      withMeta.asJson

    case EmbeddedResource(resource, annotations, _meta) =>
      val base = JsonObject(
        "type" -> "resource".asJson,
        "resource" -> resource.asJson,
      )
      val withAnnotations = annotations.map(ann => base.add("annotations", ann.asJson)).getOrElse(base)
      val withMeta = _meta.map(meta => withAnnotations.add("_meta", meta.toJson)).getOrElse(withAnnotations)
      withMeta.asJson
  }

private def base64Decoder: Decoder[Array[Byte]] =
  Decoder.decodeString.emap(str => Try(Base64.getDecoder.decode(str)).toEither.left.map(_ => s"Invalid base64"))

given Decoder[Content] = Decoder.instance { c =>
  c.downField("type").as[String].flatMap {
    case "text" =>
      for
        text <- c.downField("text").as[String]
        annotations <- c.downField("annotations").as[Option[Resource.Annotations]]
        _meta <- c.downField("_meta").as[Option[JsonObject]]
      yield Content.Text(text, annotations, _meta)

    case "image" =>
      for
        data <- c.downField("data").as[Array[Byte]](using base64Decoder)
        mimeType <- c.downField("mimeType").as[String]
        annotations <- c.downField("annotations").as[Option[Resource.Annotations]]
        _meta <- c.downField("_meta").as[Option[JsonObject]]
      yield Content.Image(data, mimeType, annotations, _meta)

    case "audio" =>
      for
        data <- c.downField("data").as[Array[Byte]](using base64Decoder)
        mimeType <- c.downField("mimeType").as[String]
        annotations <- c.downField("annotations").as[Option[Resource.Annotations]]
        _meta <- c.downField("_meta").as[Option[JsonObject]]
      yield Content.Audio(data, mimeType, annotations, _meta)

    case "resource_link" =>
      for
        uri <- c.downField("uri").as[String]
        name <- c.downField("name").as[Option[String]]
        description <- c.downField("description").as[Option[String]]
        mimeType <- c.downField("mimeType").as[Option[String]]
        size <- c.downField("size").as[Option[Long]]
        annotations <- c.downField("annotations").as[Option[Resource.Annotations]]
        _meta <- c.downField("_meta").as[Option[JsonObject]]
      yield Content.ResourceLink(uri, name, description, mimeType, size, annotations, _meta)

    case "resource" =>
      for
        resource <- c.downField("resource").as[Resource.Embedded]
        annotations <- c.downField("annotations").as[Option[Resource.Annotations]]
        _meta <- c.downField("_meta").as[Option[JsonObject]]
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
  _meta: Option[JsonObject] = None,
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
    ).deepMerge(
      resource._meta.map(meta => JsonObject("_meta" -> meta.asJson)).getOrElse(JsonObject.empty)
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
      _meta <- c.downField("_meta").as[Option[JsonObject]]
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
    _meta: Option[JsonObject] = None,
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
      ).deepMerge(
        template._meta.map(meta => JsonObject("_meta" -> meta.asJson)).getOrElse(JsonObject.empty)
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
        _meta <- c.downField("_meta").as[Option[JsonObject]]
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
    case Text(uri: String, mimeType: Option[String], text: String, _meta: Option[JsonObject] = None)
    case Blob(uri: String, mimeType: Option[String], blob: String, _meta: Option[JsonObject] = None)

  object Contents:
    given Encoder[Contents] = Encoder.instance {
      case Text(uri, mimeType, text, _meta) =>
        val base = JsonObject(
          "uri" -> uri.asJson,
          "mimeType" -> mimeType.asJson,
          "text" -> text.asJson,
        )
        val withMeta = _meta.map(meta => base.add("_meta", meta.toJson)).getOrElse(base)
        withMeta.asJson
      case Blob(uri, mimeType, blob, _meta) =>
        val base = JsonObject(
          "uri" -> uri.asJson,
          "mimeType" -> mimeType.asJson,
          "blob" -> blob.asJson,
        )
        val withMeta = _meta.map(meta => base.add("_meta", meta.toJson)).getOrElse(base)
        withMeta.asJson
    }

    given Decoder[Contents] = Decoder.instance { c =>
      // Try to decode as Text first (has text field)
      c.downField("text").as[String].map { text =>
        for
          uri <- c.downField("uri").as[String]
          mimeType <- c.downField("mimeType").as[Option[String]]
          _meta <- c.downField("_meta").as[Option[JsonObject]]
        yield Text(uri, mimeType, text, _meta)
      }.getOrElse {
        // If no text field, try as Blob
        for
          uri <- c.downField("uri").as[String]
          mimeType <- c.downField("mimeType").as[Option[String]]
          blob <- c.downField("blob").as[String]
          _meta <- c.downField("_meta").as[Option[JsonObject]]
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
