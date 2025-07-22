package ch.linkyard.mcp.jsonrpc2

import cats.effect.kernel.Resource
import ch.linkyard.mcp.jsonrpc2.JsonRpcConnection

trait JsonRpcConnectionHandler[F[_]]:
  def open(connection: JsonRpcConnection[F]): Resource[F, Unit]
