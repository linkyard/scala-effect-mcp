package ch.linkyard.mcp.protocol

import io.circe.Codec
import io.circe.derivation.ConfiguredCodec

/** `server/discover`: the supported protocol versions, the capabilities and the instructions of the server. */
case class Discover(_meta: Meta = Meta.empty)

object Discover:
  given Codec.AsObject[Discover] = ConfiguredCodec.derived[Discover].withoutNulls

  case class Response(
    supportedVersions: List[String],
    capabilities: ServerCapabilities,
    instructions: Option[String] = None,
    ttlMs: Long = 0,
    cacheScope: CacheScope = CacheScope.Private,
    _meta: Meta = Meta.empty,
  ) extends McpResponse

  object Response:
    given Codec.AsObject[Response] = completeResultCodec(ConfiguredCodec.derived[Response])
