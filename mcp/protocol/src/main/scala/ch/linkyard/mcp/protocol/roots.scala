package ch.linkyard.mcp.protocol

import io.circe.Decoder
import io.circe.Encoder
import io.circe.JsonObject
import io.circe.syntax.*

case class Root(uri: String, name: Option[String], _meta: Meta = Meta.empty)

object Root:
  given Encoder.AsObject[Root] = Encoder.AsObject.instance { root =>
    JsonObject(
      "uri" -> root.uri.asJson,
      "name" -> root.name.asJson,
      "_meta" -> root._meta.asJson,
    )
  }
  given Decoder[Root] = Decoder.instance { c =>
    for
      uri <- c.downField("uri").as[String]
      name <- c.downField("name").as[Option[String]]
      _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
    yield Root(uri, name, _meta)
  }

object Roots:
  case class ListRoots(
    _meta: Meta = Meta.empty
  ) extends Request:
    override type Response = ListRoots.Response
    override val method: RequestMethod = RequestMethod.ListRoots

  object ListRoots:
    given Encoder.AsObject[ListRoots] = Encoder.AsObject.instance { listRoots =>
      JsonObject(
        "_meta" -> listRoots._meta.asJson
      )
    }
    given Decoder[ListRoots] = Decoder.instance { c =>
      c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty)).map(ListRoots.apply)
    }

    case class Response(
      roots: List[Root],
      _meta: Meta = Meta.empty,
    ) extends McpResponse

    object Response:
      given Encoder.AsObject[Response] = Encoder.AsObject.instance { response =>
        JsonObject(
          "roots" -> response.roots.asJson,
          "_meta" -> response._meta.asJson,
        )
      }
      given Decoder[Response] = Decoder.instance { c =>
        for
          roots <- c.downField("roots").as[List[Root]]
          _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
        yield Response(roots, _meta)
      }

  case class ListChanged(
    _meta: Meta = Meta.empty
  ) extends Notification:
    override val method: NotificationMethod = NotificationMethod.RootsListChanged

  object ListChanged:
    given Encoder.AsObject[ListChanged] = Encoder.AsObject.instance { listChanged =>
      JsonObject(
        "_meta" -> listChanged._meta.asJson
      )
    }
    given Decoder[ListChanged] = Decoder.instance { c =>
      c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty)).map(ListChanged.apply)
    }
