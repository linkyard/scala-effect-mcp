package ch.linkyard.mcp.server

import ch.linkyard.mcp.jsonrpc2.Authentication
import ch.linkyard.mcp.jsonrpc2.JsonRpcConnection
import ch.linkyard.mcp.protocol.ClientCapabilities
import ch.linkyard.mcp.protocol.ElicitResult
import ch.linkyard.mcp.protocol.Implementation
import ch.linkyard.mcp.protocol.InputResponses
import ch.linkyard.mcp.protocol.Meta
import ch.linkyard.mcp.protocol.elicitResult

/** What the client declared for the request. */
case class ClientInfo(
  /** Self reported by the client (not verified, do not use for security decisions) */
  info: Option[Implementation],
  capabilities: ClientCapabilities,
)

/** The answers of a client to the input requests of an earlier attempt of the same request. */
case class InputContext(
  responses: InputResponses,
  /** The state that was returned together with the input requests */
  state: Option[String],
):
  /** The answer to an elicitation, None when the client did not answer (or the answer was not valid). */
  def elicit(key: String): Option[ElicitResult] = responses.elicitResult(key).flatMap(_.toOption)

object InputContext:
  val empty: InputContext = InputContext(Map.empty, None)

/** Everything a handler knows about the request. */
trait RequestContext[F[_]]:
  def client: ClientInfo
  def authentication: Authentication

  /** How the request arrived (stdio, http with the client address, ...) */
  def transport: JsonRpcConnection.Info

  /** The `_meta` of the request */
  def meta: Meta

  /** The answers of the client when this is the retry of a request that required input */
  def input: InputContext

  /** Reports the progress to the client (does nothing when the client did not ask for progress). */
  def reportProgress(progress: Double, total: Option[Double] = None, message: Option[String] = None): F[Unit]
