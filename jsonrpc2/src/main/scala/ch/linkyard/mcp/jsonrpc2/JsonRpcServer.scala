package ch.linkyard.mcp.jsonrpc2

import cats.effect.Concurrent
import cats.effect.Resource
import ch.linkyard.mcp.jsonrpc2.JsonRpc

trait JsonRpcServer[F[_]]:
  def handler: fs2.Pipe[F, JsonRpc.MessageEnvelope, JsonRpc.Message]
  def out: fs2.Stream[F, JsonRpc.Message]
end JsonRpcServer

object JsonRpcServer:
  def start[F[_]: Concurrent](
    server: JsonRpcServer[F],
    connection: JsonRpcConnection[F],
  ): Resource[F, Unit] =
    val in = connection.in
      .through(server.handler)
      .through(connection.out)
    val out = server.out
      .through(connection.out)
    val merged = in.merge[F, Unit](out)
    merged.compile.resource.drain
