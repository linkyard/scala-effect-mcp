package ch.linkyard.mcp.protocol

import io.circe.Codec
import io.circe.derivation.ConfiguredCodec

case class Prompt(
  name: String,
  title: Option[String] = None,
  description: Option[String] = None,
  arguments: Option[List[PromptArgument]] = None,
  icons: Option[List[Icon]] = None,
  _meta: Meta = Meta.empty,
)

object Prompt:
  given Codec.AsObject[Prompt] = ConfiguredCodec.derived[Prompt].withoutNulls

case class PromptArgument(
  name: String,
  title: Option[String] = None,
  description: Option[String] = None,
  required: Option[Boolean] = None,
)

object PromptArgument:
  given Codec.AsObject[PromptArgument] = ConfiguredCodec.derived[PromptArgument].withoutNulls

case class PromptMessage(
  role: Role,
  content: Content,
)

object PromptMessage:
  given Codec.AsObject[PromptMessage] = ConfiguredCodec.derived[PromptMessage].withoutNulls

object Prompts:
  case class ListPrompts(
    cursor: Option[Cursor] = None,
    _meta: Meta = Meta.empty,
  )

  object ListPrompts:
    given Codec.AsObject[ListPrompts] = ConfiguredCodec.derived[ListPrompts].withoutNulls

    case class Response(
      prompts: List[Prompt],
      nextCursor: Option[Cursor] = None,
      ttlMs: Long = 0,
      cacheScope: CacheScope = CacheScope.Private,
      _meta: Meta = Meta.empty,
    ) extends McpResponse

    object Response:
      given Codec.AsObject[Response] = completeResultCodec(ConfiguredCodec.derived[Response])

  case class GetPrompt(
    name: String,
    arguments: Option[Map[String, String]] = None,
    inputResponses: Option[InputResponses] = None,
    requestState: Option[String] = None,
    _meta: Meta = Meta.empty,
  )

  object GetPrompt:
    given Codec.AsObject[GetPrompt] = ConfiguredCodec.derived[GetPrompt].withoutNulls

    case class Response(
      messages: List[PromptMessage],
      description: Option[String] = None,
      _meta: Meta = Meta.empty,
    ) extends McpResponse

    object Response:
      given Codec.AsObject[Response] = completeResultCodec(ConfiguredCodec.derived[Response])

  case class ListChanged(
    _meta: Meta = Meta.empty
  )

  object ListChanged:
    given Codec.AsObject[ListChanged] = ConfiguredCodec.derived[ListChanged].withoutNulls
