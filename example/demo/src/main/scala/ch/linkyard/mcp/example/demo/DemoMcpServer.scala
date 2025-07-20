package ch.linkyard.mcp.example.demo

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import ch.linkyard.mcp.jsonrpc2.JsonRpcServer
import ch.linkyard.mcp.jsonrpc2.transport.StdioJsonRpcConnection
import ch.linkyard.mcp.server.LowlevelMcpServer
import ch.linkyard.mcp.server.McpServer

object DemoMcpServer extends IOApp:
  override def run(args: List[String]): IO[ExitCode] =
    val sf = McpServer.create(new DemoServer)
    IO(System.err.println("Welcome to Echo MCP")) >>
      LowlevelMcpServer.start(sf, e => IO(System.err.println(s"Error parsing: $e")))
        .flatMap(jsonRpc =>
          val conn = StdioJsonRpcConnection.resource[IO].map(StdioJsonRpcConnection.logRequestsToSyserr)
          JsonRpcServer.start(jsonRpc, conn)
        ).useForever.as(ExitCode.Success)
