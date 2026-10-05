package ch.linkyard.mcp.server

import cats.effect.kernel.Async
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.Authentication
import ch.linkyard.mcp.jsonrpc2.JsonRpc.ErrorCode
import ch.linkyard.mcp.jsonrpc2.JsonRpcConnection
import ch.linkyard.mcp.protocol.*
import ch.linkyard.mcp.protocol.Prompts.GetPrompt
import ch.linkyard.mcp.protocol.Resources.ReadResource
import ch.linkyard.mcp.protocol.Tool.CallTool
import ch.linkyard.mcp.server.McpServer.*
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*

import java.time.Instant

/** What a request needs to be executed, independent of the protocol version of the client. */
private[server] case class RequestEnv[F[_]](
  client: ClientInfo,
  authentication: Authentication,
  transport: JsonRpcConnection.Info,
  /** Sends a notification that belongs to the request (progress) */
  emit: ServerNotification => F[Unit],
  /** The `Mcp-Param-*` headers of the http request (lower case name), None if the request has no headers */
  paramHeaders: Option[Map[String, String]] = None,
)

/** Executes the requests of the clients, shared by the handlers of all protocol versions. */
private[server] final class ServerCore[F[_]](
  val server: McpServer[F],
  val config: McpServerConfig,
  val logError: Throwable => F[Unit],
)(using F: Async[F]):
  private val protector = RequestStateProtector(config.requestState)

  val capabilities: ServerCapabilities = ServerCapabilities(
    tools = server match
      case _: ToolProviderWithChanges[F] => Some(ServerCapabilities.Tools(Some(true)))
      case _: ToolProvider[F]            => Some(ServerCapabilities.Tools(Some(false)))
      case _                             => None,
    prompts = server match
      case _: PromptProviderWithChanges[F] => Some(ServerCapabilities.Prompts(Some(true)))
      case _: PromptProvider[F]            => Some(ServerCapabilities.Prompts(Some(false)))
      case _                               => None,
    resources = server match
      case _: ResourceSubscriptionProvider[F] => Some(ServerCapabilities.Resources(Some(true), Some(true)))
      case _: ResourceProviderWithChanges[F]  => Some(ServerCapabilities.Resources(Some(false), Some(true)))
      case _: ResourceProvider[F]             => Some(ServerCapabilities.Resources(Some(false), Some(false)))
      case _                                  => None,
    completions = server match
      case _: PromptProvider[?]   => Some(JsonObject.empty)
      case _: ResourceProvider[?] => Some(JsonObject.empty)
      case _                      => None,
  )

  /** The cache hints of the discover result: the instructions may depend on the authentication. */
  def discover(supportedVersions: List[String], env: RequestEnv[F], meta: Meta): F[Discover.Response] =
    server.instructions(context(meta, env)).map(instructions =>
      Discover.Response(supportedVersions, capabilities, instructions, ttlMs = 0, cacheScope = CacheScope.Private)
    )

  def context(
    requestMeta: Meta,
    env: RequestEnv[F],
    inputContext: InputContext = InputContext.empty,
  ): RequestContext[F] =
    new RequestContext[F]:
      override val client: ClientInfo = env.client
      override val authentication: Authentication = env.authentication
      override val transport: JsonRpcConnection.Info = env.transport
      override val meta: Meta = requestMeta
      override val input: InputContext = inputContext
      override def reportProgress(progress: Double, total: Option[Double], message: Option[String]): F[Unit] =
        requestMeta.progressToken match
          case Some(token) => env.emit(ProgressNotification(token, progress, total, message))
          case None        => F.unit

  private def unsupported[A]: F[A] =
    McpError.raise[F](ErrorCode.MethodNotFound, "Capability not supported by this server").widen

  def execute(request: ClientRequest, env: RequestEnv[F]): F[ServerResponse] = request match
    case r: Discover       => unsupported // answered by the handler that knows the supported versions
    case r: Tool.ListTools =>
      server match
        case s: ToolProvider[F] =>
          for
            tools <- s.tools(context(r._meta, env))
            valid <- tools.sortBy(_.name).filterA(withValidHeaders)
          yield Tool.ListTools.Response(valid.map(toProtocol), None, s.toolsCache.ttlMs, s.toolsCache.scope)
        case _ => unsupported
    case r: CallTool =>
      server match
        case s: ToolProvider[F] =>
          val binding = binder(env, r.name, "tools/call", r.arguments.getOrElse(JsonObject.empty).toJson)
          withInput(r.inputResponses, r.requestState, binding, env) { input =>
            val ctx = context(r._meta, env, input)
            for
              tools <- s.tools(ctx)
              tool <- tools.find(_.name == r.name)
                .toRight(McpError.error(ErrorCode.InvalidParams, s"Tool ${r.name} not found")).liftTo[F]
              arguments = r.arguments.getOrElse(JsonObject.empty)
              _ <- env.paramHeaders.traverse_(headers =>
                McpHeaderAnnotations.validateRequest(tool.argsSchema, arguments, headers)
                  .left.map(message => McpError(McpErrorCode.HeaderMismatch, message, None)).liftTo[F]
              )
              outcome <- tool(arguments, ctx)
            yield outcome
          }
        case _ => unsupported
    case r: Prompts.ListPrompts =>
      server match
        case s: PromptProvider[F] =>
          s.prompts(context(r._meta, env)).map(prompts =>
            Prompts.ListPrompts.Response(
              prompts.map(_.prompt).sortBy(_.name),
              None,
              s.promptsCache.ttlMs,
              s.promptsCache.scope,
            )
          )
        case _ => unsupported
    case r: GetPrompt =>
      server match
        case s: PromptProvider[F] =>
          val arguments = r.arguments.getOrElse(Map.empty)
          val binding = binder(env, r.name, "prompts/get", arguments.asJson)
          withInput(r.inputResponses, r.requestState, binding, env) { input =>
            val ctx = context(r._meta, env, input)
            s.prompt(r.name, ctx).flatMap(_.get(arguments, ctx))
          }
        case _ => unsupported
    case r: Resources.ListResources =>
      server match
        case s: ResourceProvider[F] =>
          page(s.resources(r.cursor, context(r._meta, env)), s.maxPageSize).map((resources, next) =>
            Resources.ListResources.Response(resources, next, s.resourcesCache.ttlMs, s.resourcesCache.scope)
          )
        case _ => unsupported
    case r: Resources.ListResourceTemplates =>
      server match
        case s: ResourceProvider[F] =>
          page(s.resourceTemplates(r.cursor, context(r._meta, env)), s.maxPageSize).map((templates, next) =>
            Resources.ListResourceTemplates.Response(
              templates.map(_.template),
              next,
              s.resourcesCache.ttlMs,
              s.resourcesCache.scope,
            )
          )
        case _ => unsupported
    case r: ReadResource =>
      server match
        case s: ResourceProvider[F] =>
          val binding = binder(env, r.uri, "resources/read", Json.Null)
          val isRetry = r.inputResponses.isDefined || r.requestState.isDefined
          withInput(r.inputResponses, r.requestState, binding, env) { input =>
            s.resource(r.uri, context(r._meta, env, input)).map(_.map(response =>
              // results of a retry must not be cached
              if isRetry then response.copy(ttlMs = 0, cacheScope = CacheScope.Private) else response
            ))
          }
        case _ => unsupported
    case r: Completion.Complete =>
      val ctx = context(r._meta, env)
      val arguments = r.context.flatMap(_.arguments).getOrElse(Map.empty)
      val completion = r.ref match
        case CompletionReference.PromptReference(name, _) =>
          server match
            case s: PromptProvider[F] =>
              s.prompt(name, ctx).flatMap(_.argumentCompletions(r.argument.name, r.argument.value, arguments, ctx))
            case _ => Completion(Nil).pure[F]
        case CompletionReference.ResourceTemplateReference(uri) =>
          server match
            case s: ResourceProvider[F] =>
              s.resourceTemplate(uri, ctx).flatMap(_.completions(r.argument.name, r.argument.value, arguments, ctx))
            case _ => Completion(Nil).pure[F]
      completion.map(Completion.Complete.Response(_))
    case _: Subscriptions.Listen => unsupported // streamed by the handlers, see listen

  /** Tools with invalid `x-mcp-header` annotations are not listed (clients would reject them), the error is logged. */
  private def withValidHeaders(tool: ToolFunction[F]): F[Boolean] =
    McpHeaderAnnotations.validate(tool.argsSchema) match
      case Right(_)     => true.pure[F]
      case Left(reason) =>
        logError(IllegalStateException(s"The tool ${tool.name} is not listed: $reason")).as(false)

  private def toProtocol(tool: ToolFunction[F]): Tool = Tool(
    name = tool.name,
    title = tool.info.title,
    description = tool.info.description,
    inputSchema = tool.argsSchema,
    outputSchema = tool.resultSchema,
    annotations = Some(Tool.Annotations(
      title = tool.info.title,
      readOnlyHint = Some(tool.info.isReadOnly),
      destructiveHint = Some(tool.info.isDestructive),
      idempotentHint = Some(tool.info.isIdempotent),
      openWorldHint = Some(tool.info.isOpenWorld),
    )),
    icons = tool.info.icons,
    _meta = tool.meta.map(Meta.apply).getOrElse(Meta.empty),
  )

  /** One page of at most `pageSize` elements, the cursor of the next page is only there when there is more. */
  private def page[A](items: fs2.Stream[F, Pageable[A]], pageSize: Int): F[(List[A], Option[Cursor])] =
    items.take(pageSize.toLong + 1).compile.toList.map { list =>
      if list.size > pageSize then list.take(pageSize).map(_._2) -> list.lift(pageSize - 1).map(_._1)
      else list.map(_._2) -> None
    }

  private def binder(env: RequestEnv[F], target: String, method: String, arguments: Json): StateBinding =
    StateBinding(config.requestState.principal(env.authentication), method, target, StateBinding.hash(arguments))

  /** Checks the request state of a retry and runs the handler with what the client answered. */
  private def withInput[A <: ServerResponse](
    responses: Option[InputResponses],
    requestState: Option[String],
    binding: StateBinding,
    env: RequestEnv[F],
  )(handler: InputContext => F[Outcome[A]]): F[ServerResponse] =
    val input = requestState match
      case None        => InputContext(responses.getOrElse(Map.empty), None).pure[F]
      case Some(token) =>
        F.realTimeInstant.flatMap(now =>
          protector.verify(token, binding, now)
            .map(state => InputContext(responses.getOrElse(Map.empty), Some(state)))
            .left.map(message => McpError.error(ErrorCode.InvalidParams, message))
            .liftTo[F]
        )
    input.flatMap(handler).flatMap {
      case Outcome.Complete(value)                => (value: ServerResponse).pure[F]
      case Outcome.InputRequired(requests, state) =>
        for
          _ <- checkCapabilities(requests, env.client.capabilities).liftTo[F]
          _ <- Either.cond(
            requests.nonEmpty || state.isDefined,
            (),
            McpError(ErrorCode.InternalError, "Input required without requests or state", None),
          ).liftTo[F]
          now <- F.realTimeInstant
          token = state.map(protector.protect(_, binding, now))
        yield InputRequiredResult(Option(requests).filter(_.nonEmpty), token)
    }

  private def checkCapabilities(requests: InputRequests, capabilities: ClientCapabilities): Either[McpError, Unit] =
    val missing = requests.values.toList.flatMap {
      case InputRequest.Elicit(_: ElicitParams.Form) if !capabilities.supportsFormElicitation =>
        List(ClientCapabilities(elicitation = Some(ClientCapabilities.Elicitation(form = Some(JsonObject.empty)))))
      case InputRequest.Elicit(_: ElicitParams.Url) if !capabilities.supportsUrlElicitation =>
        List(ClientCapabilities(elicitation = Some(ClientCapabilities.Elicitation(url = Some(JsonObject.empty)))))
      case _ => Nil
    }
    missing.headOption.toLeft(()).left.map(required =>
      McpError(
        McpErrorCode.MissingRequiredClientCapability,
        "The client does not support the requested input",
        Some(MissingRequiredClientCapabilityData(
          required.copy(elicitation =
            Some(
              ClientCapabilities.Elicitation(
                form = missing.flatMap(_.elicitation).flatMap(_.form).headOption,
                url = missing.flatMap(_.elicitation).flatMap(_.url).headOption,
              )
            )
          )
        ).asJson),
      )
    )

  /** The notifications for the list changes of the server (as far as it supports them). */
  def changes(filter: SubscriptionFilter): (SubscriptionFilter, fs2.Stream[F, ServerNotification]) =
    val tools = server match
      case s: ToolProviderWithChanges[F] if filter.toolsListChanged.contains(true) =>
        Some(true) -> s.toolChanges.map(_ => Tool.ListChanged())
      case _ => None -> fs2.Stream.empty
    val prompts = server match
      case s: PromptProviderWithChanges[F] if filter.promptsListChanged.contains(true) =>
        Some(true) -> s.promptChanges.map(_ => Prompts.ListChanged())
      case _ => None -> fs2.Stream.empty
    val resources = server match
      case s: ResourceProviderWithChanges[F] if filter.resourcesListChanged.contains(true) =>
        Some(true) -> s.resourceChanges.map(_ => Resources.ListChanged())
      case _ => None -> fs2.Stream.empty
    val accepted = SubscriptionFilter(tools._1, prompts._1, resources._1, None)
    accepted -> fs2.Stream(tools._2, prompts._2, resources._2).parJoinUnbounded

  /** The updates of a resource. */
  def resourceUpdates(uri: String, context: RequestContext[F]): Option[fs2.Stream[F, ServerNotification]] =
    server match
      case s: ResourceSubscriptionProvider[F] =>
        Some(s.resourceUpdates(uri, context).map(updated => Resources.Updated(uri, updated.meta)))
      case _ => None

  /** The notifications for a subscription: what the server accepted and the stream of the notifications */
  def listen(
    filter: SubscriptionFilter,
    context: RequestContext[F],
  ): (SubscriptionFilter, fs2.Stream[F, ServerNotification]) =
    val (accepted, changeStream) = changes(filter)
    val uris = filter.resourceSubscriptions.getOrElse(Nil).distinct
    val updates = uris.flatMap(uri => resourceUpdates(uri, context).map(uri -> _))
    val acceptedUris = updates.map(_._1)
    val all = fs2.Stream.emits(changeStream +: updates.map(_._2)).parJoinUnbounded
    accepted.copy(resourceSubscriptions = Option(acceptedUris).filter(_.nonEmpty)) -> all

  def now: F[Instant] = F.realTimeInstant
end ServerCore
