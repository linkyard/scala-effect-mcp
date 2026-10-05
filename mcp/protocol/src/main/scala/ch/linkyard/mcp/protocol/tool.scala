package ch.linkyard.mcp.protocol

import io.circe.Codec
import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.JsonObject
import io.circe.derivation.ConfiguredCodec
import io.circe.syntax.*

case class Tool(
  name: String,
  title: Option[String] = None,
  description: Option[String] = None,
  inputSchema: JsonSchema,
  outputSchema: Option[JsonSchema] = None,
  annotations: Option[Tool.Annotations] = None,
  icons: Option[List[Icon]] = None,
  _meta: Meta = Meta.empty,
)

object Tool:
  given Codec.AsObject[Tool] = ConfiguredCodec.derived[Tool].withoutNulls

  case class ListTools(
    cursor: Option[Cursor] = None,
    _meta: Meta = Meta.empty,
  )

  object ListTools:
    given Codec.AsObject[ListTools] = ConfiguredCodec.derived[ListTools].withoutNulls

    case class Response(
      tools: List[Tool],
      nextCursor: Option[Cursor] = None,
      ttlMs: Long = 0,
      cacheScope: CacheScope = CacheScope.Private,
      _meta: Meta = Meta.empty,
    ) extends McpResponse

    object Response:
      given Codec.AsObject[Response] = completeResultCodec(ConfiguredCodec.derived[Response])

  case class CallTool(
    name: String,
    arguments: Option[JsonObject] = None,
    inputResponses: Option[InputResponses] = None,
    requestState: Option[String] = None,
    _meta: Meta = Meta.empty,
  )

  object CallTool:
    given Codec.AsObject[CallTool] = ConfiguredCodec.derived[CallTool].withoutNulls

    enum Response extends McpResponse:
      case Success(
        content: List[Content],
        structuredContent: Option[Json] = None,
        _meta: Meta = Meta.empty,
      )
      case Error(
        content: List[Content],
        structuredContent: Option[Json] = None,
        _meta: Meta = Meta.empty,
      )

    object Response:
      given Encoder.AsObject[Response] = Encoder.AsObject.instance {
        case Success(content, structuredContent, _meta) => obj(
            "resultType" -> "complete".asJson,
            "content" -> content.asJson,
            "structuredContent" -> structuredContent.asJson,
            "_meta" -> _meta.asJson,
          )
        case Error(content, structuredContent, _meta) => obj(
            "resultType" -> "complete".asJson,
            "content" -> content.asJson,
            "structuredContent" -> structuredContent.asJson,
            "isError" -> true.asJson,
            "_meta" -> _meta.asJson,
          )
      }

      given Decoder[Response] = Decoder.instance { c =>
        for
          isError <- c.downField("isError").as[Option[Boolean]]
          structuredContent <- c.downField("structuredContent").as[Option[Json]]
          content <- c.downField("content").as[List[Content]]
          _meta <- c.downField("_meta").as[Meta]
        yield
          if isError.contains(true) then Error(content, structuredContent, _meta)
          else Success(content, structuredContent, _meta)
      }

  case class ListChanged(
    _meta: Meta = Meta.empty
  )

  object ListChanged:
    given Codec.AsObject[ListChanged] = ConfiguredCodec.derived[ListChanged].withoutNulls

  case class Annotations(
    title: Option[String] = None,
    readOnlyHint: Option[Boolean] = None,
    destructiveHint: Option[Boolean] = None,
    idempotentHint: Option[Boolean] = None,
    openWorldHint: Option[Boolean] = None,
  )

  object Annotations:
    given Codec.AsObject[Annotations] = ConfiguredCodec.derived[Annotations].withoutNulls
