package ch.linkyard.mcp.protocol

import io.circe.Codec
import io.circe.Decoder
import io.circe.DecodingFailure
import io.circe.Encoder
import io.circe.Json
import io.circe.JsonObject
import io.circe.derivation.ConfiguredCodec
import io.circe.syntax.*

/** Multi round-trip requests: a server that needs more information to complete a request answers with an
  * [[InputRequiredResult]], the client then retries the request with the [[InputResponses]].
  */
enum ElicitAction:
  case Accept
  case Decline
  case Cancel

object ElicitAction:
  given Codec[ElicitAction] =
    stringEnumCodec("elicit action", "accept" -> Accept, "decline" -> Decline, "cancel" -> Cancel)

/** The result of an elicitation, as sent by the client. */
case class ElicitResult(
  action: ElicitAction,
  content: Option[JsonObject] = None,
  _meta: Meta = Meta.empty,
)
object ElicitResult:
  given Codec.AsObject[ElicitResult] = ConfiguredCodec.derived[ElicitResult].withoutNulls

enum ElicitParams:
  /** Ask for structured data, `requestedSchema` is a flat object of primitive properties. */
  case Form(message: String, requestedSchema: JsonSchema)

  /** Send the user to a url for an out-of-band interaction. */
  case Url(message: String, url: String)

  def message: String

object ElicitParams:
  given Encoder[ElicitParams] = Encoder.instance {
    case Form(message, requestedSchema) => obj(
        "mode" -> "form".asJson,
        "message" -> message.asJson,
        "requestedSchema" -> requestedSchema.asJson,
      ).asJson
    case Url(message, url) => obj(
        "mode" -> "url".asJson,
        "message" -> message.asJson,
        "url" -> url.asJson,
      ).asJson
  }
  given Decoder[ElicitParams] = Decoder.instance { c =>
    c.downField("mode").as[Option[String]].flatMap {
      case None | Some("form") =>
        for
          message <- c.downField("message").as[String]
          requestedSchema <- c.downField("requestedSchema").as[JsonSchema]
        yield Form(message, requestedSchema)
      case Some("url") =>
        for
          message <- c.downField("message").as[String]
          url <- c.downField("url").as[String]
        yield Url(message, url)
      case Some(other) => Left(DecodingFailure(s"Unknown elicitation mode: $other", c.history))
    }
  }

/** A request of the server to the client (as part of an [[InputRequiredResult]]) */
enum InputRequest:
  case Elicit(params: ElicitParams)

object InputRequest:
  val ElicitMethod = "elicitation/create"

  given Encoder[InputRequest] = Encoder.instance {
    case Elicit(params) => Json.obj("method" -> ElicitMethod.asJson, "params" -> params.asJson)
  }
  given Decoder[InputRequest] = Decoder.instance { c =>
    c.downField("method").as[String].flatMap {
      case ElicitMethod => c.downField("params").as[ElicitParams].map(Elicit.apply)
      case other        => Left(DecodingFailure(s"Unsupported input request method: $other", c.history))
    }
  }

/** Requests of the server by their (server assigned) key. */
type InputRequests = Map[String, InputRequest]

/** The answers of the client by the key of the [[InputRequests]], the values are the results of the requests. */
type InputResponses = Map[String, Json]

extension (responses: InputResponses)
  /** The answer to an elicitation request */
  def elicitResult(key: String): Option[Either[DecodingFailure, ElicitResult]] =
    responses.get(key).map(_.as[ElicitResult])

/** Interim result, the client has to retry the request with the answers to the requests (`inputRequests`) and the
  * `requestState` (if any).
  */
case class InputRequiredResult(
  inputRequests: Option[InputRequests] = None,
  requestState: Option[String] = None,
  _meta: Meta = Meta.empty,
) extends McpResponse

object InputRequiredResult:
  private val codec = ConfiguredCodec.derived[InputRequiredResult]
  given Codec.AsObject[InputRequiredResult] = Codec.AsObject.from(
    codec,
    Encoder.AsObject.instance(a =>
      codec.encodeObject(a).filter(!_._2.isNull).add("resultType", "input_required".asJson)
    ),
  )
