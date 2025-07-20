package ch.linkyard.mcp.example.demo

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.implicits.*
import ch.linkyard.mcp.example.demo.prompts.StoryPrompt
import ch.linkyard.mcp.example.demo.resources.AnimalResource
import ch.linkyard.mcp.example.demo.tools.*
import ch.linkyard.mcp.jsonrpc2.JsonRpc.ErrorCode
import ch.linkyard.mcp.protocol.Completion
import ch.linkyard.mcp.protocol.Cursor
import ch.linkyard.mcp.protocol.Initialize.PartyInfo
import ch.linkyard.mcp.protocol.Resource as Res
import ch.linkyard.mcp.protocol.Resources.ReadResource
import ch.linkyard.mcp.server.CallContext
import ch.linkyard.mcp.server.McpError
import ch.linkyard.mcp.server.McpServer
import ch.linkyard.mcp.server.McpServer.Pageable
import ch.linkyard.mcp.server.PromptFunction
import ch.linkyard.mcp.server.ToolFunction

class DemoServer extends McpServer[IO]:
  override def connect(client: McpServer.Client[IO]): Resource[IO, McpServer.Session[IO]] =
    Resource.pure(DemoSession(client))

  private class DemoSession(client: McpServer.Client[IO]) extends McpServer.Session[IO] with McpServer.ToolProvider[IO]
      with McpServer.PromptProvider[IO] with McpServer.ResourceProvider[IO]:

    override val serverInfo: PartyInfo = PartyInfo(
      "Demo MCP",
      "development",
    )
    override val maxPageSize: Int = 5
    override def instructions: IO[Option[String]] = None.pure
    override val tools: IO[List[ToolFunction[IO]]] = List(ParrotTool(), AdderTool(), UserEmailTool(client)).pure
    override val prompts: IO[List[PromptFunction[IO]]] = List(StoryPrompt).pure

    override def promptCompletions(
      promptName: String,
      argumentName: String,
      valueToComplete: String,
      otherArguments: Map[String, String],
      context: CallContext[IO],
    ): IO[Completion] = promptName match
      case "story" => StoryPrompt.completions(argumentName, valueToComplete)
      case _       => McpError.raise(ErrorCode.InvalidParams, s"Prompt $promptName not found")
    end promptCompletions

    override def resource(uri: String, context: CallContext[IO]): IO[ReadResource.Response] =
      // Check if this is an animal resource URI
      if uri.startsWith("animal://") then
        AnimalResource.resource(uri)
      else
        McpError.raise(ErrorCode.InvalidParams, s"Unsupported resource schema: $uri")

    override def resources(after: Option[Cursor]): fs2.Stream[IO, Pageable[Res]] =
      AnimalResource.resources(after)

    override def resourceTemplates(after: Option[Cursor]): fs2.Stream[IO, Pageable[Res.Template]] =
      AnimalResource.resourceTemplates

    override def resourceTemplateCompletions(
      uri: String,
      argumentName: String,
      valueToComplete: String,
      otherArguments: Map[String, String],
      context: CallContext[IO],
    ): IO[Completion] =
      // Check if this is an animal resource template URI
      if uri.startsWith("animal://") then
        AnimalResource.resourceTemplateCompletions(uri, argumentName, valueToComplete)
      else
        Completion(Nil).pure
  end DemoSession
