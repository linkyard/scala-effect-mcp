package ch.linkyard.mcp.jsonrpc2

import cats.effect.kernel.Resource

/** Provides the [[JsonRpcHandler]]s for the transports. */
trait JsonRpcHandlerFactory[F[_]]:
  /** Handler for the messages that are not part of a connection (http without a session). */
  def stateless: JsonRpcHandler[F]

  /** Handler for the messages of one connection (a stdio process or a http session). Released when the connection is
    * closed.
    */
  def connection(info: JsonRpcConnection.Info): Resource[F, JsonRpcHandler[F]]

  /** Whether http sessions can be opened (for clients that do not support stateless requests). */
  def supportsSessions: Boolean
