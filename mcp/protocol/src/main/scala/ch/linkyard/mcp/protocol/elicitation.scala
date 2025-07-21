package ch.linkyard.mcp.protocol

import cats.syntax.option.*
import io.circe.Decoder
import io.circe.Encoder
import io.circe.JsonObject
import io.circe.syntax.*

object Elicitation:
  case class Create(
    message: String,
    /** only object with {[name: string] -> int | string | boolean} is allowed */
    requestedSchema: JsonSchema,
    _meta: Meta = Meta.empty,
  ) extends Request:
    override type Response = Create.Response
    override val method: RequestMethod = RequestMethod.ElicitCreate

  object Create:
    given Encoder.AsObject[Create] = Encoder.AsObject.instance { create =>
      JsonObject(
        "message" -> create.message.asJson,
        "requestedSchema" -> create.requestedSchema.asJson,
        "_meta" -> create._meta.asJson,
      )
    }
    given Decoder[Create] = Decoder.instance { c =>
      for
        message <- c.downField("message").as[String]
        requestedSchema <- c.downField("requestedSchema").as[JsonSchema]
        _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
      yield Create(message, requestedSchema, _meta)
    }

    case class Response(
      action: Action,
      content: Option[JsonObject],
      _meta: Meta = Meta.empty,
    ) extends McpResponse

    object Response:
      given Encoder.AsObject[Response] = Encoder.AsObject.instance { response =>
        JsonObject(
          "action" -> response.action.asJson,
          "content" -> response.content.asJson,
          "_meta" -> response._meta.asJson,
        )
      }
      given Decoder[Response] = Decoder.instance { c =>
        for
          action <- c.downField("action").as[Action]
          content <- c.downField("content").as[Option[JsonObject]]
          _meta <- c.downField("_meta").as[Option[Meta]].map(_.getOrElse(Meta.empty))
        yield Response(action, content, _meta)
      }

  enum Action:
    case Accept
    case Decline
    case Cancel

  object Action:
    def fromString(s: String): Option[Action] = s match
      case "accept"  => Accept.some
      case "decline" => Decline.some
      case "cancel"  => Cancel.some
      case _         => None

    given Encoder[Action] = Encoder.instance {
      case Accept  => "accept".asJson
      case Decline => "decline".asJson
      case Cancel  => "cancel".asJson
    }

    given Decoder[Action] = Decoder.instance { c =>
      c.as[String].flatMap { s =>
        fromString(s).toRight(io.circe.DecodingFailure(s"Unknown action: $s", c.history))
      }
    }
