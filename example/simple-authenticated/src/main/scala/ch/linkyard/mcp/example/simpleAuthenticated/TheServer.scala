package ch.linkyard.mcp.example.simpleAuthenticated

import cats.effect.IO
import cats.effect.Resource
import cats.implicits.*
import ch.linkyard.mcp.example.simpleAuthenticated.TheServer.Session
import ch.linkyard.mcp.protocol.Initialize.PartyInfo
import ch.linkyard.mcp.server.McpServer
import ch.linkyard.mcp.server.McpServer.Client
import ch.linkyard.mcp.server.ToolFunction
import ch.linkyard.mcp.server.ToolFunction.Effect
import com.melvinlow.json.schema.generic.auto.given
import io.circe.generic.auto.given
import com.melvinlow.json.schema.annotation.JsonSchemaField
import io.circe.syntax.*

class TheServer extends McpServer[IO]:
  override def initialize(client: Client[IO]): Resource[IO, McpServer.Session[IO]] =
    Resource.pure(Session(client))

object TheServer:
  case class HelloInput(
    @JsonSchemaField("description", "Your Name".asJson)
    name: String
  )

  private def helloTool(client: Client[IO]): ToolFunction[IO] = ToolFunction.text(
    ToolFunction.Info(
      "hello",
      "Say Hello".some,
      "Receives your hello and answers you with our authentication token".some,
      Effect.ReadOnly,
      isOpenWorld = false,
    ),
    (input: HelloInput, _) =>
      for
        auth <- client.authentication
      yield s"Hello ${input.name}!\nYour authentication Token is $auth",
  )

  private class Session(client: Client[IO]) extends McpServer.Session[IO] with McpServer.ToolProvider[IO]:
    override val serverInfo: PartyInfo = PartyInfo(
      "Simple Authenticated MCP",
      "1.0.0",
    )
    override def instructions: IO[Option[String]] = None.pure
    override val tools: IO[List[ToolFunction[IO]]] = List(helloTool(client)).pure
