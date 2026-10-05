package ch.linkyard.mcp.server

import ch.linkyard.mcp.jsonrpc2.JsonRpc
import ch.linkyard.mcp.jsonrpc2.JsonRpc.ErrorCode
import ch.linkyard.mcp.protocol.McpErrorCode
import ch.linkyard.mcp.server.McpError.McpErrorException

private[server] object Errors:
  /** The error response for a failed request, None for the errors that are not part of the protocol (internal). */
  def protocolError(id: JsonRpc.Id, error: Throwable, legacy: Boolean): Option[JsonRpc.Response.Error] = error match
    case McpErrorException(e) =>
      val code = if legacy && e.isResourceNotFound then McpErrorCode.LegacyResourceNotFound else e.errorCode
      Some(JsonRpc.Response.Error(id, code, e.message, e.data))
    case _ => None

  def internal(id: JsonRpc.Id): JsonRpc.Response.Error =
    JsonRpc.Response.Error(id, ErrorCode.InternalError, "Internal error", None)
