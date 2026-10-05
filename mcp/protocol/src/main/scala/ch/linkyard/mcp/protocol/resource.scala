package ch.linkyard.mcp.protocol

import io.circe.Codec
import io.circe.derivation.ConfiguredCodec

object Resources:
  case class ListResources(
    cursor: Option[Cursor] = None,
    _meta: Meta = Meta.empty,
  )

  object ListResources:
    given Codec.AsObject[ListResources] = ConfiguredCodec.derived[ListResources].withoutNulls

    case class Response(
      resources: List[Resource],
      nextCursor: Option[Cursor] = None,
      ttlMs: Long = 0,
      cacheScope: CacheScope = CacheScope.Private,
      _meta: Meta = Meta.empty,
    ) extends McpResponse

    object Response:
      given Codec.AsObject[Response] = completeResultCodec(ConfiguredCodec.derived[Response])

  case class ListResourceTemplates(
    cursor: Option[Cursor] = None,
    _meta: Meta = Meta.empty,
  )

  object ListResourceTemplates:
    given Codec.AsObject[ListResourceTemplates] = ConfiguredCodec.derived[ListResourceTemplates].withoutNulls

    case class Response(
      resourceTemplates: List[Resource.Template],
      nextCursor: Option[Cursor] = None,
      ttlMs: Long = 0,
      cacheScope: CacheScope = CacheScope.Private,
      _meta: Meta = Meta.empty,
    ) extends McpResponse

    object Response:
      given Codec.AsObject[Response] = completeResultCodec(ConfiguredCodec.derived[Response])

  case class ReadResource(
    uri: String,
    inputResponses: Option[InputResponses] = None,
    requestState: Option[String] = None,
    _meta: Meta = Meta.empty,
  )

  object ReadResource:
    given Codec.AsObject[ReadResource] = ConfiguredCodec.derived[ReadResource].withoutNulls

    case class Response(
      contents: List[Resource.Contents],
      ttlMs: Long = 0,
      cacheScope: CacheScope = CacheScope.Private,
      _meta: Meta = Meta.empty,
    ) extends McpResponse

    object Response:
      given Codec.AsObject[Response] = completeResultCodec(ConfiguredCodec.derived[Response])

  case class Updated(
    uri: String,
    _meta: Meta = Meta.empty,
  )

  object Updated:
    given Codec.AsObject[Updated] = ConfiguredCodec.derived[Updated].withoutNulls

  case class ListChanged(
    _meta: Meta = Meta.empty
  )

  object ListChanged:
    given Codec.AsObject[ListChanged] = ConfiguredCodec.derived[ListChanged].withoutNulls
