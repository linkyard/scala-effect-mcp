package ch.linkyard.mcp.example.demo

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import ch.linkyard.mcp.jsonrpc2.transport.StdioJsonRpcConnection
import ch.linkyard.mcp.server.McpServer.*

object StdioDemoMcpServer extends IOApp:
  override def run(args: List[String]): IO[ExitCode] =
    // run with stdio transport, ends when the client closes the input
    IO(System.err.println("Welcome to the demo MCP server")) >>
      DemoServer().run(
        StdioJsonRpcConnection.create[IO],
        e => IO(System.err.println(s"Error: $e")),
      ).as(ExitCode.Success)
