package ch.linkyard.mcp.example.simpleEcho

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.kernel.Resource
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.transport.StdioJsonRpcConnection
import ch.linkyard.mcp.protocol.Initialize.PartyInfo
import ch.linkyard.mcp.server.*
import ch.linkyard.mcp.server.ToolFunction.Effect
import com.melvinlow.json.schema.generic.auto.given
import io.circe.generic.auto.given

object SimpleEchoServer extends IOApp:
  // Define the input/output types for your tool
  case class EchoInput(text: String)

  // Create the echo tool function
  private def echoTool: ToolFunction[IO] = ToolFunction.text(
    ToolFunction.Info(
      "echo",
      Some("Echo"),
      Some("Repeats the input text back to you"),
      Effect.ReadOnly,
      isOpenWorld = false,
    ),
    (input: EchoInput, _) => IO(input.text),
  )

  // Define your server session
  private class Session extends McpServer.Session[IO] with McpServer.ToolProvider[IO]:
    override val serverInfo: PartyInfo = PartyInfo(
      "Simple Echo MCP",
      "1.0.0",
    )
    override def instructions: IO[Option[String]] = None.pure
    override val tools: IO[List[ToolFunction[IO]]] = List(echoTool).pure

  // Define your server
  private class Server extends McpServer[IO]:
    override def connect(client: McpServer.Client[IO]): Resource[IO, McpServer.Session[IO]] =
      Resource.pure(Session())

  override def run(args: List[String]): IO[ExitCode] =
    // run with stdio transport
    Server().start(
      StdioJsonRpcConnection.resource[IO],
      e => IO(System.err.println(s"Error: $e")),
    ).useForever.as(ExitCode.Success)
  end run
end SimpleEchoServer
