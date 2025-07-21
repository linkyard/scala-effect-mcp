package ch.linkyard.mcp.protocol

import io.circe.Decoder
import io.circe.Encoder
import io.circe.JsonObject
import io.circe.syntax.*

object Resources:
  case class ListResources(
    cursor: Option[Cursor],
    _meta: Meta = Meta.empty,
  ) extends Request:
    override type Response = ListResources.Response
    override val method: RequestMethod = RequestMethod.ListResources

  object ListResources:
    given Encoder.AsObject[ListResources] = Encoder.AsObject.instance { listResources =>
      JsonObject(
        "cursor" -> listResources.cursor.asJson,
        "_meta" -> listResources._meta.asJson,
      )
    }
    given Decoder[ListResources] = Decoder.instance { c =>
      for
        cursor <- c.downField("cursor").as[Option[Cursor]]
        _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
      yield ListResources(cursor, _meta)
    }

    case class Response(
      resources: List[Resource],
      nextCursor: Option[Cursor],
      _meta: Meta = Meta.empty,
    ) extends McpResponse

    object Response:
      given Encoder.AsObject[Response] = Encoder.AsObject.instance { response =>
        JsonObject(
          "resources" -> response.resources.asJson,
          "nextCursor" -> response.nextCursor.asJson,
          "_meta" -> response._meta.asJson,
        )
      }
      given Decoder[Response] = Decoder.instance { c =>
        for
          resources <- c.downField("resources").as[List[Resource]]
          nextCursor <- c.downField("nextCursor").as[Option[Cursor]]
          _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
        yield Response(resources, nextCursor, _meta)
      }

  case class ListResourceTemplates(
    cursor: Option[Cursor],
    _meta: Meta = Meta.empty,
  ) extends Request:
    override type Response = ListResourceTemplates.Response
    override val method: RequestMethod = RequestMethod.ListResourceTemplates

  object ListResourceTemplates:
    given Encoder.AsObject[ListResourceTemplates] = Encoder.AsObject.instance { listTemplates =>
      JsonObject(
        "cursor" -> listTemplates.cursor.asJson,
        "_meta" -> listTemplates._meta.asJson,
      )
    }
    given Decoder[ListResourceTemplates] = Decoder.instance { c =>
      for
        cursor <- c.downField("cursor").as[Option[Cursor]]
        _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
      yield ListResourceTemplates(cursor, _meta)
    }

    case class Response(
      resourceTemplates: List[Resource.Template],
      nextCursor: Option[Cursor],
      _meta: Meta = Meta.empty,
    ) extends McpResponse

    object Response:
      given Encoder.AsObject[Response] = Encoder.AsObject.instance { response =>
        JsonObject(
          "resourceTemplates" -> response.resourceTemplates.asJson,
          "nextCursor" -> response.nextCursor.asJson,
          "_meta" -> response._meta.asJson,
        )
      }
      given Decoder[Response] = Decoder.instance { c =>
        for
          resourceTemplates <- c.downField("resourceTemplates").as[List[Resource.Template]]
          nextCursor <- c.downField("nextCursor").as[Option[Cursor]]
          _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
        yield Response(resourceTemplates, nextCursor, _meta)
      }

  case class ReadResource(
    uri: String,
    _meta: Meta = Meta.empty,
  ) extends Request:
    override type Response = ReadResource.Response
    override val method: RequestMethod = RequestMethod.ReadResource

  object ReadResource:
    given Encoder.AsObject[ReadResource] = Encoder.AsObject.instance { readResource =>
      JsonObject(
        "uri" -> readResource.uri.asJson,
        "_meta" -> readResource._meta.asJson,
      )
    }
    given Decoder[ReadResource] = Decoder.instance { c =>
      for
        uri <- c.downField("uri").as[String]
        _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
      yield ReadResource(uri, _meta)
    }

    case class Response(
      contents: List[Resource.Contents],
      _meta: Meta = Meta.empty,
    ) extends McpResponse

    object Response:
      given Encoder.AsObject[Response] = Encoder.AsObject.instance { response =>
        JsonObject(
          "contents" -> response.contents.asJson,
          "_meta" -> response._meta.asJson,
        )
      }
      given Decoder[Response] = Decoder.instance { c =>
        for
          contents <- c.downField("contents").as[List[Resource.Contents]]
          _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
        yield Response(contents, _meta)
      }

  case class Subscribe(
    uri: String,
    _meta: Meta = Meta.empty,
  ) extends Request:
    override type Response = Subscribe.Response
    override val method: RequestMethod = RequestMethod.Subscribe

  object Subscribe:
    given Encoder.AsObject[Subscribe] = Encoder.AsObject.instance { subscribe =>
      JsonObject(
        "uri" -> subscribe.uri.asJson,
        "_meta" -> subscribe._meta.asJson,
      )
    }
    given Decoder[Subscribe] = Decoder.instance { c =>
      for
        uri <- c.downField("uri").as[String]
        _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
      yield Subscribe(uri, _meta)
    }

    case class Response(
      _meta: Meta = Meta.empty
    ) extends McpResponse

    object Response:
      given Encoder.AsObject[Response] = Encoder.AsObject.instance { response =>
        JsonObject(
          "_meta" -> response._meta.asJson
        )
      }
      given Decoder[Response] = Decoder.instance { c =>
        c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty)).map(Response.apply)
      }

  case class Unsubscribe(
    uri: String,
    _meta: Meta = Meta.empty,
  ) extends Request:
    override type Response = Unsubscribe.Response
    override val method: RequestMethod = RequestMethod.Unsubscribe

  object Unsubscribe:
    given Encoder.AsObject[Unsubscribe] = Encoder.AsObject.instance { unsubscribe =>
      JsonObject(
        "uri" -> unsubscribe.uri.asJson,
        "_meta" -> unsubscribe._meta.asJson,
      )
    }
    given Decoder[Unsubscribe] = Decoder.instance { c =>
      for
        uri <- c.downField("uri").as[String]
        _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
      yield Unsubscribe(uri, _meta)
    }

    case class Response(
      _meta: Meta = Meta.empty
    ) extends McpResponse

    object Response:
      given Encoder.AsObject[Response] = Encoder.AsObject.instance { response =>
        JsonObject(
          "_meta" -> response._meta.asJson
        )
      }
      given Decoder[Response] = Decoder.instance { c =>
        c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty)).map(Response.apply)
      }

  case class Updated(
    uri: String,
    _meta: Meta = Meta.empty,
  ) extends Notification:
    override val method: NotificationMethod = NotificationMethod.ResourceUpdated

  object Updated:
    given Encoder.AsObject[Updated] = Encoder.AsObject.instance { updated =>
      JsonObject(
        "uri" -> updated.uri.asJson,
        "_meta" -> updated._meta.asJson,
      )
    }
    given Decoder[Updated] = Decoder.instance { c =>
      for
        uri <- c.downField("uri").as[String]
        _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
      yield Updated(uri, _meta)
    }

  case class ListChanged(
    _meta: Meta = Meta.empty
  ) extends Notification:
    override val method: NotificationMethod = NotificationMethod.ResourceListChanged

  object ListChanged:
    given Encoder.AsObject[ListChanged] = Encoder.AsObject.instance { listChanged =>
      JsonObject(
        "_meta" -> listChanged._meta.asJson
      )
    }
    given Decoder[ListChanged] = Decoder.instance { c =>
      c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty)).map(ListChanged.apply)
    }
