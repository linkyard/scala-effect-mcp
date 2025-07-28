package ch.linkyard.mcp.jsonrpc2

import com.comcast.ip4s.Host
import com.comcast.ip4s.IpAddress
import com.comcast.ip4s.Port
import io.circe.Json

trait JsonRpcConnection[F[_]]:
  def info: JsonRpcConnection.Info
  def out: fs2.Pipe[F, JsonRpc.Message, Unit]
  def in: fs2.Stream[F, JsonRpc.MessageEnvelope]

object JsonRpcConnection:
  enum Info:
    case Stdio(additional: Map[String, Json])
    case Http(server: Option[(Host, Port)], client: Option[IpAddress], additional: Map[String, Json])
    case Other(additional: Map[String, Json])
    def additional: Map[String, Json]
