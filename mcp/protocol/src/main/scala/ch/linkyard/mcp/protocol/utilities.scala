package ch.linkyard.mcp.protocol

import io.circe.Codec
import io.circe.Decoder
import io.circe.DecodingFailure
import io.circe.Encoder
import io.circe.derivation.ConfiguredCodec
import io.circe.syntax.*

/** Cancels a request (client to server), also sent by the server when it ends a subscription. */
case class Cancelled(
  requestId: RequestId,
  reason: Option[String] = None,
  _meta: Meta = Meta.empty,
)

object Cancelled:
  given Codec.AsObject[Cancelled] = ConfiguredCodec.derived[Cancelled].withoutNulls

case class ProgressNotification(
  progressToken: ProgressToken,
  progress: Double,
  total: Option[Double] = None,
  message: Option[String] = None,
  _meta: Meta = Meta.empty,
)

object ProgressNotification:
  given Codec.AsObject[ProgressNotification] = ConfiguredCodec.derived[ProgressNotification].withoutNulls

enum CompletionReference:
  case PromptReference(name: String, title: Option[String] = None)
  case ResourceTemplateReference(uri: String)

object CompletionReference:
  given Encoder[CompletionReference] = Encoder.instance {
    case PromptReference(name, title) =>
      obj("type" -> "ref/prompt".asJson, "name" -> name.asJson, "title" -> title.asJson).asJson
    case ResourceTemplateReference(uri) =>
      obj("type" -> "ref/resource".asJson, "uri" -> uri.asJson).asJson
  }
  given Decoder[CompletionReference] = Decoder.instance { c =>
    c.downField("type").as[String].flatMap {
      case "ref/prompt" =>
        for
          name <- c.downField("name").as[String]
          title <- c.downField("title").as[Option[String]]
        yield PromptReference(name, title)
      case "ref/resource" => c.downField("uri").as[String].map(ResourceTemplateReference.apply)
      case other          => Left(DecodingFailure(s"Unknown completion reference type: $other", c.history))
    }
  }

case class Completion(
  values: List[String],
  total: Option[Int] = None,
  hasMore: Option[Boolean] = None,
)

object Completion:
  given Codec.AsObject[Completion] = ConfiguredCodec.derived[Completion].withoutNulls

  case class Complete(
    ref: CompletionReference,
    argument: Complete.Argument,
    context: Option[Complete.Context] = None,
    _meta: Meta = Meta.empty,
  )

  object Complete:
    given Codec.AsObject[Complete] = ConfiguredCodec.derived[Complete].withoutNulls

    case class Argument(name: String, value: String)
    object Argument:
      given Codec.AsObject[Argument] = ConfiguredCodec.derived[Argument].withoutNulls

    case class Context(arguments: Option[Map[String, String]] = None)
    object Context:
      given Codec.AsObject[Context] = ConfiguredCodec.derived[Context].withoutNulls

    case class Response(
      completion: Completion,
      _meta: Meta = Meta.empty,
    ) extends McpResponse

    object Response:
      given Codec.AsObject[Response] = completeResultCodec(ConfiguredCodec.derived[Response])
