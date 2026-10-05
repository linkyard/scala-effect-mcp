package ch.linkyard.mcp.protocol

import ch.linkyard.mcp.jsonrpc2.JsonRpc.ErrorCode
import io.circe.Codec
import io.circe.derivation.ConfiguredCodec

/** Error codes defined by the MCP specification (-32020 to -32099 are reserved for it). */
object McpErrorCode:
  /** The http headers do not match the request body (or are missing). */
  val HeaderMismatch: ErrorCode = ErrorCode.Other(-32020)

  /** The request requires a client capability that was not declared. */
  val MissingRequiredClientCapability: ErrorCode = ErrorCode.Other(-32021)

  /** The requested protocol version is not supported. */
  val UnsupportedProtocolVersion: ErrorCode = ErrorCode.Other(-32022)

  /** Resource not found in earlier protocol versions (2025-11-25 and before), now InvalidParams. */
  val LegacyResourceNotFound: ErrorCode = ErrorCode.Other(-32002)

/** `data` of the error with code UnsupportedProtocolVersion */
case class UnsupportedProtocolVersionData(supported: List[String], requested: String)
object UnsupportedProtocolVersionData:
  given Codec.AsObject[UnsupportedProtocolVersionData] =
    ConfiguredCodec.derived[UnsupportedProtocolVersionData].withoutNulls

/** `data` of the error with code MissingRequiredClientCapability */
case class MissingRequiredClientCapabilityData(requiredCapabilities: ClientCapabilities)
object MissingRequiredClientCapabilityData:
  given Codec.AsObject[MissingRequiredClientCapabilityData] =
    ConfiguredCodec.derived[MissingRequiredClientCapabilityData].withoutNulls
