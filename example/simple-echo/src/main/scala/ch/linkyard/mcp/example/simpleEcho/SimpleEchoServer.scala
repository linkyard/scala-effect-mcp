package ch.linkyard.mcp.example.simpleEcho

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.transport.StdioJsonRpcConnection
import ch.linkyard.mcp.protocol.Implementation
import ch.linkyard.mcp.server.*
import ch.linkyard.mcp.server.ToolFunction.Effect
import com.melvinlow.json.schema.generic.auto.given
import io.circe.generic.auto.given

object SimpleEchoServer extends IOApp:
  case class EchoInput(text: String)

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

  private class Server extends McpServer[IO] with McpServer.ToolProvider[IO]:
    override val serverInfo: Implementation = Implementation("Simple Echo MCP", "1.0.0")
    override def instructions(@scala.annotation.unused context: RequestContext[IO]): IO[Option[String]] = None.pure
    override def tools(context: RequestContext[IO]): IO[List[ToolFunction[IO]]] = List(echoTool).pure

  override def run(args: List[String]): IO[ExitCode] =
    // run with stdio transport, ends when the client closes the input
    Server().run(
      StdioJsonRpcConnection.create[IO],
      e => IO(System.err.println(s"Error: $e")),
    ).as(ExitCode.Success)
  end run
end SimpleEchoServer
