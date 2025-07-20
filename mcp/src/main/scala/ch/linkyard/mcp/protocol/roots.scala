package ch.linkyard.mcp.protocol

import io.circe.Decoder
import io.circe.Encoder
import io.circe.JsonObject
import io.circe.syntax.*

case class Root(uri: String, name: Option[String], _meta: Option[JsonObject] = None)

object Root:
  given Encoder.AsObject[Root] = Encoder.AsObject.instance { root =>
    JsonObject(
      "uri" -> root.uri.asJson,
      "name" -> root.name.asJson,
    ).deepMerge(
      root._meta.map(meta => JsonObject("_meta" -> meta.asJson)).getOrElse(JsonObject.empty)
    )
  }
  given Decoder[Root] = Decoder.instance { c =>
    for
      uri <- c.downField("uri").as[String]
      name <- c.downField("name").as[Option[String]]
      _meta <- c.downField("_meta").as[Option[JsonObject]]
    yield Root(uri, name, _meta)
  }

object Roots:
  case class ListRoots(
    _meta: Option[JsonObject] = None
  ) extends Request:
    override type Response = ListRoots.Response
    override val method: RequestMethod = RequestMethod.ListRoots

  object ListRoots:
    given Encoder.AsObject[ListRoots] = Encoder.AsObject.instance { listRoots =>
      listRoots._meta.map(meta => JsonObject("_meta" -> meta.asJson)).getOrElse(JsonObject.empty)
    }
    given Decoder[ListRoots] = Decoder.instance { c =>
      c.downField("_meta").as[Option[JsonObject]].map(ListRoots.apply)
    }

    case class Response(
      roots: List[Root],
      _meta: Option[JsonObject] = None,
    ) extends McpResponse

    object Response:
      given Encoder.AsObject[Response] = Encoder.AsObject.instance { response =>
        JsonObject(
          "roots" -> response.roots.asJson
        ).deepMerge(
          response._meta.map(meta => JsonObject("_meta" -> meta.asJson)).getOrElse(JsonObject.empty)
        )
      }
      given Decoder[Response] = Decoder.instance { c =>
        for
          roots <- c.downField("roots").as[List[Root]]
          _meta <- c.downField("_meta").as[Option[JsonObject]]
        yield Response(roots, _meta)
      }

  case class ListChanged(
    _meta: Option[JsonObject] = None
  ) extends Notification:
    override val method: NotificationMethod = NotificationMethod.RootsListChanged

  object ListChanged:
    given Encoder.AsObject[ListChanged] = Encoder.AsObject.instance { listChanged =>
      listChanged._meta.map(meta => JsonObject("_meta" -> meta.asJson)).getOrElse(JsonObject.empty)
    }
    given Decoder[ListChanged] = Decoder.instance { c =>
      c.downField("_meta").as[Option[JsonObject]].map(ListChanged.apply)
    }
