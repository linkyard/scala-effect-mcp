package ch.linkyard.mcp.server

import cats.MonadThrow
import cats.effect.Concurrent
import cats.effect.kernel.Async
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.JsonRpc.ErrorCode
import ch.linkyard.mcp.jsonrpc2.JsonRpcConnection
import ch.linkyard.mcp.jsonrpc2.JsonRpcHandlerFactory
import ch.linkyard.mcp.jsonrpc2.JsonRpcServer
import ch.linkyard.mcp.protocol.Cursor
import ch.linkyard.mcp.protocol.Implementation
import ch.linkyard.mcp.protocol.Meta
import ch.linkyard.mcp.protocol.Resource
import ch.linkyard.mcp.protocol.Resources.ReadResource

/** An MCP server. It is stateless: the same instance serves all clients and requests, what a client is allowed to see
  * may depend on its authentication (see the [[RequestContext]]) but not on the connection.
  *
  * Mix in the provider traits to expose tools, prompts or resources.
  */
trait McpServer[F[_]]:
  /** Name and version of the server */
  def serverInfo: Implementation

  /** Instructions for the model on how to use the server */
  def instructions: F[Option[String]]

  /** The maximum number of items on a page of resources */
  def maxPageSize: Int = 100
end McpServer

object McpServer:
  extension [F[_]](server: McpServer[F])
    /** The handlers to serve the server with a transport. */
    def handlerFactory(config: McpServerConfig, logError: Throwable => F[Unit])(using
      Async[F]
    ): JsonRpcHandlerFactory[F] =
      McpServerHandlers(ServerCore(server, config, logError), config, logError)

    /** Serves a connection until its input ends (the connection of the stdio transport for example). */
    def run(connection: JsonRpcConnection[F], logError: Throwable => F[Unit], config: McpServerConfig)(using
      Async[F]
    ): F[Unit] =
      JsonRpcServer.serve(server.handlerFactory(config, logError), connection, logError)

    def run(connection: JsonRpcConnection[F], logError: Throwable => F[Unit])(using Async[F]): F[Unit] =
      server.run(connection, logError, McpServerConfig.default)
  end extension

  trait ToolProvider[F[_]] extends McpServer[F]:
    /** The tools, may vary by the authentication but not by anything else. */
    def tools(context: RequestContext[F]): F[List[ToolFunction[F]]]

    /** How long clients may keep the list of tools */
    def toolsCache: CacheHint = CacheHint.none

  trait ToolProviderWithChanges[F[_]] extends ToolProvider[F]:
    /** Emits when the list of tools changed */
    def toolChanges: fs2.Stream[F, Unit]

  trait PromptProvider[F[_]: MonadThrow] extends McpServer[F]:
    def prompts(context: RequestContext[F]): F[List[PromptFunction[F]]]

    /** How long clients may keep the list of prompts */
    def promptsCache: CacheHint = CacheHint.none

    def prompt(name: String, context: RequestContext[F]): F[PromptFunction[F]] =
      prompts(context).flatMap(_.find(_.prompt.name == name).toRight(McpError.error(
        ErrorCode.InvalidParams,
        s"Prompt $name not found",
      )).liftTo[F])

  trait PromptProviderWithChanges[F[_]] extends PromptProvider[F]:
    /** Emits when the list of prompts changed */
    def promptChanges: fs2.Stream[F, Unit]

  /** An element together with the cursor that continues after it. */
  type Pageable[A] = (Cursor, A)

  trait ResourceProvider[F[_]: MonadThrow: Concurrent] extends McpServer[F]:
    def resources(after: Option[Cursor], context: RequestContext[F]): fs2.Stream[F, Pageable[Resource]]
    def resourceTemplates(
      after: Option[Cursor],
      context: RequestContext[F],
    ): fs2.Stream[F, Pageable[ResourceTemplate[F]]]

    /** Reads a resource. Fail with [[McpError.resourceNotFound]] when it does not exist. */
    def resource(uri: String, context: RequestContext[F]): F[Outcome[ReadResource.Response]]

    def resourceTemplate(uriTemplate: String, context: RequestContext[F]): F[ResourceTemplate[F]] =
      resourceTemplates(None, context).compile.toList.flatMap(_.map(_._2).find(_.template.uriTemplate == uriTemplate)
        .toRight(McpError.error(ErrorCode.InvalidParams, s"Resource template $uriTemplate not found"))
        .liftTo[F])

    /** How long clients may keep the lists of resources and resource templates */
    def resourcesCache: CacheHint = CacheHint.none

  trait ResourceProviderWithChanges[F[_]] extends ResourceProvider[F]:
    /** Emits when the list of resources changed */
    def resourceChanges: fs2.Stream[F, Unit]

  trait ResourceSubscriptionProvider[F[_]] extends ResourceProviderWithChanges[F]:
    /** Emits whenever the resource with the uri is updated. */
    def resourceUpdates(uri: String, context: RequestContext[F]): fs2.Stream[F, ResourceUpdated]

  case class ResourceUpdated(meta: Meta = Meta.empty)
end McpServer
