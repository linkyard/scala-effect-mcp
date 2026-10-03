package ch.linkyard.mcp.example.simpleAuthenticated

import cats.effect.IO
import cats.implicits.*
import ch.linkyard.mcp.protocol.Implementation
import ch.linkyard.mcp.server.McpServer
import ch.linkyard.mcp.server.RequestContext
import ch.linkyard.mcp.server.ToolFunction
import ch.linkyard.mcp.server.ToolFunction.Effect
import com.melvinlow.json.schema.annotation.JsonSchemaField
import com.melvinlow.json.schema.generic.auto.given
import io.circe.generic.auto.given
import io.circe.syntax.*

class TheServer extends McpServer[IO] with McpServer.ToolProvider[IO]:
  override val serverInfo: Implementation = Implementation("Simple Authenticated MCP", "1.0.0")
  override def instructions: IO[Option[String]] = None.pure
  override def tools(context: RequestContext[IO]): IO[List[ToolFunction[IO]]] = List(TheServer.helloTool).pure

object TheServer:
  case class HelloInput(
    @JsonSchemaField("description", "Your Name".asJson)
    name: String
  )

  private val helloTool: ToolFunction[IO] = ToolFunction.text(
    ToolFunction.Info(
      "hello",
      "Say Hello".some,
      "Receives your hello and answers you with our authentication token".some,
      Effect.ReadOnly,
      isOpenWorld = false,
    ),
    (input: HelloInput, context: RequestContext[IO]) =>
      IO.pure(s"Hello ${input.name}!\nYour authentication Token is ${context.authentication}"),
  )
