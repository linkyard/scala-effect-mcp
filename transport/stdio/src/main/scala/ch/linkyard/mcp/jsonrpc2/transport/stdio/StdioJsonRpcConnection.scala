package ch.linkyard.mcp.jsonrpc2.transport

import cats.effect.IO
import cats.effect.kernel.Async
import cats.effect.kernel.Resource
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.JsonRpc.Message
import ch.linkyard.mcp.jsonrpc2.JsonRpc.MessageEnvelope
import ch.linkyard.mcp.jsonrpc2.JsonRpcConnection
import fs2.Pipe
import fs2.io.stdin
import fs2.io.stdout

object StdioJsonRpcConnection:
  def resource[F[_]: Async]: Resource[F, JsonRpcConnection[F]] =
    new LineBasedJsonRpcConnection[F](
      stdin[F](4096),
      stdout[F],
    ).pure

  def logRequestsToSyserr(conn: JsonRpcConnection[IO]): JsonRpcConnection[IO] =
    new JsonRpcConnection[IO]:
      override def in: fs2.Stream[IO, MessageEnvelope] =
        conn.in.evalTap(m => IO(System.err.println(s"<< " + m.message)))
      override def out: Pipe[IO, Message, Unit] =
        _.evalTap(m => IO(System.err.println(s">> " + m))).through(conn.out)
