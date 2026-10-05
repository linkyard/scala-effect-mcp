package ch.linkyard.mcp.example.demo

import cats.effect.IO
import cats.implicits.*
import ch.linkyard.mcp.example.demo.prompts.StoryPrompt
import ch.linkyard.mcp.example.demo.resources.AnimalResource
import ch.linkyard.mcp.example.demo.resources.AnimalResourceTemplate
import ch.linkyard.mcp.example.demo.tools.*
import ch.linkyard.mcp.protocol.Cursor
import ch.linkyard.mcp.protocol.Implementation
import ch.linkyard.mcp.protocol.Resource as Res
import ch.linkyard.mcp.protocol.Resources.ReadResource
import ch.linkyard.mcp.server.McpError
import ch.linkyard.mcp.server.McpServer
import ch.linkyard.mcp.server.McpServer.Pageable
import ch.linkyard.mcp.server.Outcome
import ch.linkyard.mcp.server.PromptFunction
import ch.linkyard.mcp.server.RequestContext
import ch.linkyard.mcp.server.ResourceTemplate
import ch.linkyard.mcp.server.ToolFunction

class DemoServer extends McpServer[IO] with McpServer.ToolProvider[IO] with McpServer.PromptProvider[IO]
    with McpServer.ResourceProvider[IO]:
  override val serverInfo: Implementation = Implementation("Demo MCP", "development")
  override val maxPageSize: Int = 5
  override def instructions(@scala.annotation.unused context: RequestContext[IO]): IO[Option[String]] = None.pure

  override def tools(context: RequestContext[IO]): IO[List[ToolFunction[IO]]] =
    List(ParrotTool(), AdderTool(), UserEmailTool()).pure
  override def prompts(context: RequestContext[IO]): IO[List[PromptFunction[IO]]] = List(StoryPrompt).pure

  override def resources(after: Option[Cursor], context: RequestContext[IO]): fs2.Stream[IO, Pageable[Res]] =
    AnimalResource.resources(after)

  override def resource(uri: String, context: RequestContext[IO]): IO[Outcome[ReadResource.Response]] =
    if uri.startsWith("animal://") then AnimalResource.resource(uri).map(Outcome.Complete(_))
    else McpError.raiseResourceNotFound[IO](uri)

  override def resourceTemplates(
    after: Option[Cursor],
    context: RequestContext[IO],
  ): fs2.Stream[IO, Pageable[ResourceTemplate[IO]]] =
    fs2.Stream.emit("1" -> AnimalResourceTemplate)
end DemoServer
