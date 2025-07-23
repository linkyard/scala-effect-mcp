package ch.linkyard.mcp.server

import cats.effect.kernel.Async
import cats.effect.kernel.Ref
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.Authentication
import ch.linkyard.mcp.jsonrpc2.JsonRpc.ErrorCode
import ch.linkyard.mcp.protocol.*
import ch.linkyard.mcp.protocol.Initialize.ClientCapabilities
import ch.linkyard.mcp.protocol.Initialize.PartyInfo
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*

private class McpServerClientRepr[F[_]: Async] private (
  client: Initialize,
  comms: LowlevelMcpServer.Communication[F],
  authenticationRef: Ref[F, Authentication],
  logLevelRef: Ref[F, LoggingLevel],
) extends McpServer.Client[F]:
  override val clientInfo: PartyInfo = client.clientInfo
  override val capabilities: ClientCapabilities = client.capabilities

  override def authentication: F[Authentication] =
    authenticationRef.get

  private[server] def updateAuthentication(auth: Authentication): F[Unit] =
    authenticationRef.modify(old =>
      (old, auth) match
        case (Authentication.Anonymous, Authentication.Anonymous)           => (auth, true)
        case (Authentication.BearerToken(_), Authentication.BearerToken(_)) => (auth, true)
        case _                                                              => (old, false)
    ).ifM(
      ().pure[F],
      McpError.raise(ErrorCode.InvalidRequest, "Cannot change authentication mode after initialization").void,
    )

  override def ping: F[Unit] = comms.request(Ping()).liftToF.void

  def setLogLevel(level: LoggingLevel): F[Unit] = logLevelRef.set(level)

  override def log(level: LoggingLevel, logger: Option[String], message: String): F[Unit] =
    log(level, logger, message.asJson)

  override def log(level: LoggingLevel, logger: Option[String], data: Json): F[Unit] =
    logLevelRef.get.map(_ <= level).ifM(comms.notify(Logging.LoggingMessage(level, logger, data)), Async[F].unit)

  override def sample(
    messages: List[Sampling.Message],
    maxTokens: Int,
    modelPreferences: Option[Sampling.ModelPreferences] = None,
    systemPrompt: Option[String] = None,
    temperature: Option[Double] = None,
    includeContext: Option[String] = None,
    stopSequences: Option[List[String]] = None,
    metadata: Option[JsonObject] = None,
    _meta: Meta = Meta.empty,
  ): F[Sampling.CreateMessage.Response] =
    if client.capabilities.sampling.isDefined then
      comms.request(Sampling.CreateMessage(
        messages = messages,
        modelPreferences = modelPreferences,
        systemPrompt = systemPrompt,
        maxTokens = maxTokens,
        temperature = temperature,
        includeContext = includeContext,
        stopSequences = stopSequences,
        metadata = metadata,
        _meta = _meta,
      )).flatMap(_.liftTo[F])
    else McpError.raise(ErrorCode.MethodNotFound, "Sampling is not supported by this MCP client").widen
  end sample

  override def listRoots: F[Roots.ListRoots.Response] =
    if client.capabilities.roots.isDefined then
      comms.request(Roots.ListRoots()).flatMap(_.liftTo[F])
    else McpError.raise(ErrorCode.MethodNotFound, "Listing roots is not supported by this MCP client").widen
  end listRoots

  override def elicit(
    message: String,
    requestedSchema: JsonSchema,
    _meta: Meta = Meta.empty,
  ): F[Elicitation.Create.Response] =
    if client.capabilities.elicitation.isDefined then
      comms.request(Elicitation.Create(message, requestedSchema, _meta)).flatMap(_.liftTo[F])
    else McpError.raise(ErrorCode.MethodNotFound, "Elicitation is not supported by this MCP client").widen

object McpServerClientRepr:
  def apply[F[_]: Async](
    client: Initialize,
    comms: LowlevelMcpServer.Communication[F],
    auth: Authentication,
  ): F[McpServerClientRepr[F]] =
    for
      logLevelRef <- Ref.of[F, LoggingLevel](LoggingLevel.Info)
      authRef <- Ref.of[F, Authentication](auth)
    yield new McpServerClientRepr[F](client, comms, authRef, logLevelRef)
