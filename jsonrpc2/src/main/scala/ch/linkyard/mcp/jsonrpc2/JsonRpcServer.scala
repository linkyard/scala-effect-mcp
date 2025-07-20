package ch.linkyard.mcp.jsonrpc2

import cats.effect.Concurrent
import cats.effect.Resource
import ch.linkyard.mcp.jsonrpc2.JsonRpc

trait JsonRpcServer[F[_]]:
  def handler: fs2.Pipe[F, JsonRpc.Message, JsonRpc.Message]
  def out: fs2.Stream[F, JsonRpc.Message]
end JsonRpcServer

object JsonRpcServer:
  def start[F[_]: Concurrent](
    server: JsonRpcServer[F],
    connection: Resource[F, JsonRpcConnection[F]],
  ): Resource[F, Unit] =
    connection.flatMap { conn =>
      val in = conn.in
        .through(server.handler)
        .through(conn.out)
      val out = server.out
        .through(conn.out)
      val merged = in.merge[F, Unit](out)
      merged.compile.resource.drain
    }
