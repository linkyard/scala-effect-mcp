package ch.linkyard.mcp.jsonrpc2

trait JsonRpcConnection[F[_]]:
  def out: fs2.Pipe[F, JsonRpc.Message, Unit]
  def in: fs2.Stream[F, JsonRpc.MessageEnvelope]
