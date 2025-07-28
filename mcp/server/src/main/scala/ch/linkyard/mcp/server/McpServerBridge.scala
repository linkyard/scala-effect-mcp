package ch.linkyard.mcp.server
import cats.effect.implicits.*
import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import cats.effect.kernel.Resource
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.Authentication
import ch.linkyard.mcp.jsonrpc2.JsonRpc.ErrorCode
import ch.linkyard.mcp.jsonrpc2.JsonRpcConnection
import ch.linkyard.mcp.protocol.*
import ch.linkyard.mcp.protocol.Cancelled
import ch.linkyard.mcp.protocol.ClientNotification
import ch.linkyard.mcp.protocol.ClientRequest
import ch.linkyard.mcp.protocol.Completion.Complete
import ch.linkyard.mcp.protocol.Initialize.Capabilities
import ch.linkyard.mcp.protocol.Initialize.ServerCapabilities
import ch.linkyard.mcp.protocol.Initialized
import ch.linkyard.mcp.protocol.Logging.SetLevel
import ch.linkyard.mcp.protocol.Ping
import ch.linkyard.mcp.protocol.ProgressNotification
import ch.linkyard.mcp.protocol.Prompts.GetPrompt
import ch.linkyard.mcp.protocol.Prompts.ListPrompts
import ch.linkyard.mcp.protocol.Resources.ListResourceTemplates
import ch.linkyard.mcp.protocol.Resources.ListResources
import ch.linkyard.mcp.protocol.Resources.ReadResource
import ch.linkyard.mcp.protocol.Resources.Subscribe
import ch.linkyard.mcp.protocol.Resources.Unsubscribe
import ch.linkyard.mcp.protocol.Roots.ListChanged
import ch.linkyard.mcp.protocol.ServerResponse
import ch.linkyard.mcp.protocol.Tool
import ch.linkyard.mcp.protocol.Tool.CallTool
import ch.linkyard.mcp.protocol.Tool.ListTools
import ch.linkyard.mcp.server.LowlevelMcpServer.Communication
import ch.linkyard.mcp.server.McpServer.*
import io.circe.Json

private object McpServerBridge:
  def apply[F[_]: Async](initial: SwitchTo[F] => Phase[F]): Resource[F, LowlevelMcpServer[F]] =
    for
      state <- Resource.eval(Ref.of[F, Phase[F]](null))
      _ <- Resource.eval(state.set(initial(phase => state.set(phase).void)))
      bridge <- Resource.make[F, Bridge[F]](Bridge(state).pure[F])((bridge) => bridge.cleanup)
    yield bridge

  trait Phase[F[_]] extends LowlevelMcpServer[F]:
    def cleanup: F[Unit]

  type SwitchTo[F[_]] = Phase[F] => F[Unit]

  private class Bridge[F[_]: Async](state: Ref[F, Phase[F]]) extends LowlevelMcpServer[F]:
    override def handleRequest(request: ClientRequest, requestId: RequestId, auth: Authentication): F[ServerResponse] =
      state.get.flatMap(_.handleRequest(request, requestId, auth))
    override def handleNotification(notification: ClientNotification, auth: Authentication): F[Unit] =
      state.get.flatMap(_.handleNotification(notification, auth))
    def switchTo(newState: Phase[F]): F[Unit] =
      state.getAndSet(newState).flatMap(_.cleanup)
    def cleanup: F[Unit] = state.set(null).void

  private def capabilitiesFor[F[_]](session: McpServer.Session[F]): ServerCapabilities =
    ServerCapabilities(
      tools = session match {
        case _: ToolProviderWithChanges[F] => Capabilities.Changable(listChanged = true).some
        case _: ToolProvider[F]            => Capabilities.Changable(listChanged = false).some
        case _                             => None
      },
      prompts = session match {
        case _: PromptProviderWithChanges[F] => Capabilities.Changable(listChanged = true).some
        case _: PromptProvider[F]            => Capabilities.Changable(listChanged = false).some
        case _                               => None
      },
      resources = session match {
        case _: ResourceProvider[F] =>
          val subscribe = session match
            case _: ResourceSubscriptionProvider[F] => true
            case _                                  => false
          val listChanged = session match
            case _: ResourceProviderWithChanges[F] => true
            case _                                 => false
          Capabilities.Subscribable(subscribe = subscribe, listChanged = listChanged).some
        case _ => None
      },
      completions = session match {
        case _: PromptProvider[?]   => Capabilities.Supported().some
        case _: ResourceProvider[?] => Capabilities.Supported().some
        case _                      => None
      },
      logging = Capabilities.Supported().some,
      experimental = None,
    )

  /** Server is not initialized yet. */
  class PhaseInitial[F[_]: Async](
    server: McpServer[F],
    comms: LowlevelMcpServer.Communication[F],
    connectionInfo: JsonRpcConnection.Info,
    switchTo: SwitchTo[F],
  ) extends Phase[F]:
    override def handleRequest(request: ClientRequest, requestId: RequestId, auth: Authentication): F[ServerResponse] =
      request match
        case init: Initialize =>
          for
            client <- McpServerClientRepr[F](init, comms)
            connInfo <- ConnectionInfoRepr(auth, connectionInfo)
            (session, cleanup) <- server.initialize(client, connInfo).allocated
            _ <- switchTo(PhaseInitializing(session, client, connInfo, cleanup, comms, switchTo))
            instructions <- session.instructions
            response = Initialize.Response(
              serverInfo = session.serverInfo,
              capabilities = capabilitiesFor(session),
              instructions = instructions,
            )
          yield response
        case Ping(_) => Ping.Response().pure
        case other => McpError.raise(
            ErrorCode.MethodNotFound,
            "Unexpected request during initialization phase: " + other.method.key,
          ).widen
    override def handleNotification(notification: ClientNotification, auth: Authentication): F[Unit] = Async[F].unit // ignore everything
    override def cleanup: F[Unit] = Async[F].unit

  /** Server has been initialized, and is ready to start handling requests. */
  private class PhaseInitializing[F[_]: Async](
    session: McpServer.Session[F],
    client: McpServerClientRepr[F],
    connInfo: ConnectionInfoRepr[F],
    val cleanup: F[Unit],
    comms: LowlevelMcpServer.Communication[F],
    switchTo: SwitchTo[F],
  ) extends Phase[F]:
    override def handleRequest(request: ClientRequest, requestId: RequestId, auth: Authentication): F[ServerResponse] =
      connInfo.updateAuthentication(auth) >> (request match
        case init: Initialize =>
          for
            instructions <- session.instructions
          yield Initialize.Response(
            serverInfo = session.serverInfo,
            capabilities = capabilitiesFor(session),
            instructions = instructions,
          )
        case _: Ping => Ping.Response().pure
        case other => McpError.raise(
            ErrorCode.InvalidRequest,
            "Unexpected request during initialization phase: " + other.method.key,
          ).widen)
    override def handleNotification(notification: ClientNotification, auth: Authentication): F[Unit] =
      connInfo.updateAuthentication(auth) >> (notification match
          case Initialized(_) =>
            PhaseRunning(session, client, connInfo, cleanup, comms).flatMap(switchTo)
          case other => Async[F].unit // ignore everything
      )

  /** Server has been initialized, and is ready to start handling requests. */
  private class PhaseRunning[F[_]: Async] private (
    session: McpServer.Session[F],
    client: McpServerClientRepr[F],
    connInfo: ConnectionInfoRepr[F],
    val cleanup: F[Unit],
    comms: LowlevelMcpServer.Communication[F],
    /** uri => unsubscribe */
    activeSubscriptions: Ref[F, Map[String, F[Unit]]],
  ) extends Phase[F]:
    private def unsupported: F[ServerResponse] =
      McpError.raise(ErrorCode.MethodNotFound, "Capability not supported").widen

    private def createCallContext(name: String, _meta: Meta, request: RequestId): CallContext[F] =
      new CallContext[F] {
        override def reportProgress(
          progress: Double,
          total: Option[Double] = None,
          message: Option[String] = None,
        ): F[Unit] = _meta.progressToken match
          case Some(progressToken) =>
            comms.notify(ProgressNotification(progressToken, progress, total, message, Meta.withRequestRelation(request)))
          case None => Async[F].unit
        override def log(level: LoggingLevel, message: String): F[Unit] =
          client.log(level, name.some, message)
        override def log(level: LoggingLevel, data: Json): F[Unit] =
          client.log(level, name.some, data)
        override val meta: Meta = _meta
      }

    override def handleRequest(request: ClientRequest, requestId: RequestId, auth: Authentication): F[ServerResponse] =
      connInfo.updateAuthentication(auth) >> (request match
        case Tool.ListTools(_, _) => session match {
            case session: ToolProvider[F] => session.tools.map(_.map(tool =>
                Tool(
                  name = tool.name,
                  title = tool.info.title,
                  description = tool.info.description,
                  inputSchema = tool.argsSchema,
                  outputSchema = tool.resultSchema,
                  annotations = Tool.Annotations(
                    title = tool.info.title,
                    readOnlyHint = tool.info.isReadOnly.some,
                    destructiveHint = tool.info.isDestructive.some,
                    idempotentHint = tool.info.isIdempotent.some,
                    openWorldHint = tool.info.isOpenWorld.some,
                  ).some,
                )
              )).map(tools => Tool.ListTools.Response(tools, nextCursor = None))
            case _ => unsupported
          }
        case Tool.CallTool(name, arguments, _meta) => session match {
            case session: ToolProvider[F] =>
              for
                tool <- session.tools
                  .flatMap(_.find(_.name == name)
                    .toRight(McpError.error(ErrorCode.InvalidRequest, s"Tool $name not found")).liftTo[F])
                context = createCallContext(s"tool/${tool.name}", _meta, requestId)
                response <- tool.apply(arguments, context)
              yield response
            case _ => unsupported
          }
        case Prompts.ListPrompts(cursor, _meta) => session match
            case session: PromptProvider[F] => session.prompts.map(_.map(_.prompt))
                .map(Prompts.ListPrompts.Response(_, None))
            case _ => unsupported
        case Prompts.GetPrompt(name, arguments, _meta) => session match
            case session: PromptProvider[F] =>
              for
                prompt <- session.prompts.flatMap(_.find(_.prompt.name == name)
                  .toRight(McpError.error(ErrorCode.InvalidRequest, s"Prompt $name not found")).liftTo[F])
                context = createCallContext(s"prompt/$name", _meta, requestId)
                response <- prompt.get(arguments.getOrElse(Map.empty), context)
              yield response
            case _ => unsupported
        case Resources.ListResources(cursor, _meta) => session match
            case session: ResourceProvider[F] =>
              for
                resourceList <- session.resources(cursor).take(session.maxPageSize).compile.toList
                lastCursor = resourceList.lastOption.map(_._1)
                resources = resourceList.map(_._2)
              yield Resources.ListResources.Response(resources, nextCursor = lastCursor)
            case _ => unsupported
        case Resources.ListResourceTemplates(cursor, _meta) => session match
            case session: ResourceProvider[F] =>
              for
                resourceList <- session.resourceTemplates(cursor).take(session.maxPageSize).compile.toList
                lastCursor = resourceList.lastOption.map(_._1)
                templates = resourceList.map(_._2.template)
              yield Resources.ListResourceTemplates.Response(templates, nextCursor = lastCursor)
            case _ => unsupported
        case Resources.ReadResource(uri, _meta) => session match
            case session: ResourceProvider[F] =>
              val context = createCallContext(s"resource/$uri", _meta, requestId)
              session.resource(uri, context).widen
            case _ => unsupported
        case Resources.Subscribe(uri, _meta) => session match
            case session: ResourceSubscriptionProvider[F] =>
              for
                context = createCallContext("resource/subription", _meta, requestId)
                fibre <- session.resourceSubscription(uri, context)
                  .evalMap(updated => comms.notify(Resources.Updated(uri, updated.meta)))
                  .compile.drain.start
                unsubscribe = fibre.cancel
                before <- activeSubscriptions.getAndUpdate(subs => subs + (uri -> unsubscribe))
                _ <- before.get(uri).traverse(identity) // unsubscribe old subscription
              yield Resources.Subscribe.Response()
            case _ => unsupported
        case Resources.Unsubscribe(uri, _meta) => session match
            case session: ResourceSubscriptionProvider[F] =>
              activeSubscriptions.modify(subs => (subs - uri, subs.get(uri)))
                .map(_.traverse(identity)) // unsubscribe
                .as(Resources.Unsubscribe.Response())
            case _ => unsupported
        case Completion.Complete(ref, argument, context, _meta) =>
          val completion = ref match
            case CompletionReference.PromptReference(name, _) => session match {
                case session: PromptProvider[F] =>
                  session.prompt(name).flatMap(
                    _.argumentCompletions(
                      argument.name,
                      argument.value,
                      context.flatMap(_.arguments).getOrElse(Map.empty),
                      createCallContext(s"completion/prompt", _meta, requestId),
                    )
                  )
                case _ => Completion(Nil).pure
              }
            case CompletionReference.ResourceTemplateReference(uri) => session match {
                case session: ResourceProvider[F] =>
                  session.resourceTemplate(uri).flatMap(_.completions(
                    argument.name,
                    argument.value,
                    context.flatMap(_.arguments).getOrElse(Map.empty),
                    createCallContext(s"completion/prompt", _meta, requestId),
                  ))
                case _ => Completion(Nil).pure
              }
          completion.map(Completion.Complete.Response(_))
        case Logging.SetLevel(level, _) =>
          client.setLogLevel(level).as(Logging.SetLevel.Response())
        case Ping(_) => Ping.Response().pure
        case _: Initialize =>
          McpError.raise(ErrorCode.InvalidRequest, "Unexpected initialize request during running phase").widen)

    override def handleNotification(notification: ClientNotification, auth: Authentication): F[Unit] =
      connInfo.updateAuthentication(auth) >> (notification match
          case Roots.ListChanged(_meta) => session match
              case s: RootChangeAwareProvider[F] => s.rootsChanged
              case _                             => Async[F].unit
          case ProgressNotification(progressToken, progress, total, message, _meta) => Async[F].unit // not supported by api
          case _: Cancelled   => Async[F].unit // handled by the low level server
          case _: Initialized => Async[F].unit // just ignore it, we are already initialized...
      )
  end PhaseRunning
  private object PhaseRunning:
    def apply[F[_]: Async](
      session: McpServer.Session[F],
      client: McpServerClientRepr[F],
      connInfo: ConnectionInfoRepr[F],
      cleanup: F[Unit],
      comms: LowlevelMcpServer.Communication[F],
    ): F[PhaseRunning[F]] =
      for
        toolChangesFibre <- session match {
          case tp: ToolProviderWithChanges[F] => tp.toolChanges.evalMap(comms.notify).compile.drain.start
          case _                              => Async[F].unit.start
        }
        promptChangesFibre <- session match {
          case tp: PromptProviderWithChanges[F] => tp.promptChanges.evalMap(comms.notify).compile.drain.start
          case _                                => Async[F].unit.start
        }
        resourceChangesFibre <- session match {
          case tp: ResourceSubscriptionProvider[F] => tp.resourceChanges.evalMap(comms.notify).compile.drain.start
          case _                                   => Async[F].unit.start
        }
        activeSubscriptions <- Ref.of[F, Map[String, F[Unit]]](Map.empty)
        unsubscribeAll = activeSubscriptions.get.flatMap(_.values.toList.traverse(identity))
        cleanupAll = (
          cleanup,
          toolChangesFibre.cancel,
          promptChangesFibre.cancel,
          resourceChangesFibre.cancel,
          unsubscribeAll,
        ).parTupled.void
      yield new PhaseRunning(session, client, connInfo, cleanupAll, comms, activeSubscriptions)
