package ch.linkyard.mcp.jsonrpc2.transport

import cats.effect.kernel.Async
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.JsonRpc
import ch.linkyard.mcp.jsonrpc2.JsonRpcConnection
import fs2.Pipe
import fs2.Stream
import fs2.text
import io.circe.syntax.*

class LineBasedJsonRpcConnection[F[_]: Async](
  input: Stream[F, Byte],
  output: Pipe[F, Byte, Unit],
) extends JsonRpcConnection[F]:
  override def in: Stream[F, JsonRpc.MessageEnvelope] = input
    .through(text.utf8.decode)
    .through(text.lines)
    .filter(_.nonEmpty)
    .evalMap { line =>
      io.circe.parser.decode[JsonRpc.Message](line) match
        case Right(a) => a.withoutAuth.pure
        case Left(e)  => Async[F].raiseError(e)
    }

  override def out: Pipe[F, JsonRpc.Message, Unit] = _
    .map(_.asJson.noSpaces + "\r\n")
    .through(text.utf8.encode)
    .through(output)
