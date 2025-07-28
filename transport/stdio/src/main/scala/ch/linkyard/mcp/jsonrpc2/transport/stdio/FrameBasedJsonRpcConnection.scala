package ch.linkyard.mcp.jsonrpc2.transport

import cats.effect.kernel.Async
import ch.linkyard.mcp.jsonrpc2.JsonRpc
import ch.linkyard.mcp.jsonrpc2.JsonRpcConnection
import ch.linkyard.mcp.jsonrpc2.JsonRpcConnection.Info
import fs2.Pipe
import fs2.Stream
import io.circe.parser.decode
import io.circe.syntax.*

class StreamBasedJsonRpcConnection[F[_]: Async](
  input: Stream[F, Byte],
  output: Pipe[F, Byte, Unit],
  val info: JsonRpcConnection.Info,
) extends JsonRpcConnection[F]:
  override def in: Stream[F, JsonRpc.MessageEnvelope] = input
    .through(HeaderBasedFraming.parseFrames)
    .evalMap(s => Async[F].fromEither(decode[JsonRpc.Message](s).left.map(err => new Exception(err))))
    .map(_.withoutAuth)

  override def out: Pipe[F, JsonRpc.Message, Unit] = _
    .map(_.asJson.noSpaces)
    .through(HeaderBasedFraming.writeFrames)
    .through(output)
