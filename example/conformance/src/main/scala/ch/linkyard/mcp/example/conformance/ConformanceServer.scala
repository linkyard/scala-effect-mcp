package ch.linkyard.mcp.example.conformance

import cats.effect.IO
import ch.linkyard.mcp.protocol.Cursor
import ch.linkyard.mcp.protocol.Implementation
import ch.linkyard.mcp.protocol.Resource
import ch.linkyard.mcp.protocol.Resources.ReadResource
import ch.linkyard.mcp.server.CacheHint
import ch.linkyard.mcp.server.McpServer
import ch.linkyard.mcp.server.McpServer.Pageable
import ch.linkyard.mcp.server.McpServer.ResourceUpdated
import ch.linkyard.mcp.server.Outcome
import ch.linkyard.mcp.server.PromptFunction
import ch.linkyard.mcp.server.RequestContext
import ch.linkyard.mcp.server.ResourceTemplate
import ch.linkyard.mcp.server.ToolFunction
import fs2.concurrent.Topic

import scala.concurrent.duration.DurationInt

/** The server of the conformance suite (a port of the reference server everything-server.ts). */
class ConformanceServer private (toolChangeTopic: Topic[IO, Unit], promptChangeTopic: Topic[IO, Unit])
    extends McpServer[IO] with McpServer.ToolProviderWithChanges[IO] with McpServer.PromptProviderWithChanges[IO]
    with McpServer.ResourceSubscriptionProvider[IO]:
  override val serverInfo: Implementation = Implementation("mcp-conformance-test-server", "1.0.0")
  override def instructions: IO[Option[String]] = IO.pure(None)

  private val allTools = ConformanceTools.all(toolChangeTopic.publish1(()).void, promptChangeTopic.publish1(()).void)

  override def tools(context: RequestContext[IO]): IO[List[ToolFunction[IO]]] = IO.pure(allTools)
  override def prompts(context: RequestContext[IO]): IO[List[PromptFunction[IO]]] = IO.pure(ConformancePrompts.all)

  override def toolChanges: fs2.Stream[IO, Unit] = toolChangeTopic.subscribe(16)
  override def promptChanges: fs2.Stream[IO, Unit] = promptChangeTopic.subscribe(16)
  override def resourceChanges: fs2.Stream[IO, Unit] = fs2.Stream.never[IO]

  override def resources(after: Option[Cursor], context: RequestContext[IO]): fs2.Stream[IO, Pageable[Resource]] =
    fs2.Stream.emits(ConformanceResources.resources.zipWithIndex.map((r, i) => i.toString -> r))
      .drop(after.flatMap(_.toIntOption).map(_ + 1).getOrElse(0).toLong)

  override def resourceTemplates(
    after: Option[Cursor],
    context: RequestContext[IO],
  ): fs2.Stream[IO, Pageable[ResourceTemplate[IO]]] =
    if after.isEmpty then fs2.Stream.emit("0" -> ConformanceResources.template) else fs2.Stream.empty

  override def resource(uri: String, context: RequestContext[IO]): IO[Outcome[ReadResource.Response]] =
    ConformanceResources.read(uri)

  override def resourceUpdates(uri: String, context: RequestContext[IO]): fs2.Stream[IO, ResourceUpdated] =
    if uri == Fixtures.WatchedUri then fs2.Stream.awakeEvery[IO](3.seconds).map(_ => ResourceUpdated())
    else fs2.Stream.empty

  override val toolsCache: CacheHint = CacheHint.public(5.minutes)
  override val promptsCache: CacheHint = CacheHint.public(5.minutes)
  override val resourcesCache: CacheHint = CacheHint.public(5.minutes)
end ConformanceServer

object ConformanceServer:
  def create: IO[ConformanceServer] =
    for
      tools <- Topic[IO, Unit]
      prompts <- Topic[IO, Unit]
    yield ConformanceServer(tools, prompts)
