package ch.linkyard.mcp.server

import cats.MonadThrow
import cats.effect.Concurrent
import cats.effect.implicits.*
import cats.effect.kernel.Async
import cats.effect.kernel.Resource as CEResource
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.Authentication
import ch.linkyard.mcp.jsonrpc2.JsonRpc.ErrorCode
import ch.linkyard.mcp.jsonrpc2.JsonRpcConnection
import ch.linkyard.mcp.jsonrpc2.JsonRpcConnectionHandler
import ch.linkyard.mcp.jsonrpc2.JsonRpcServer
import ch.linkyard.mcp.protocol.Cursor
import ch.linkyard.mcp.protocol.Elicitation
import ch.linkyard.mcp.protocol.Initialize.ClientCapabilities
import ch.linkyard.mcp.protocol.Initialize.PartyInfo
import ch.linkyard.mcp.protocol.JsonSchema
import ch.linkyard.mcp.protocol.LoggingLevel
import ch.linkyard.mcp.protocol.Meta
import ch.linkyard.mcp.protocol.Prompts
import ch.linkyard.mcp.protocol.Resource
import ch.linkyard.mcp.protocol.Resources
import ch.linkyard.mcp.protocol.Resources.ReadResource
import ch.linkyard.mcp.protocol.Roots
import ch.linkyard.mcp.protocol.Sampling
import ch.linkyard.mcp.protocol.Tool
import ch.linkyard.mcp.server.LowlevelMcpServer.Communication
import io.circe.Json
import io.circe.JsonObject
import io.circe.syntax.*

trait McpServer[F[_]]:
  /** Initialize the session, see the sub-trait of Session for the capabilities like tools, etc. */
  def initialize(client: McpServer.Client[F], info: McpServer.ConnectionInfo[F]): CEResource[F, McpServer.Session[F]]

object McpServer:
  extension [F[_]](server: McpServer[F])
    def lowlevelFactory(connectionInfo: JsonRpcConnection.Info)(using
      Async[F]
    ): Communication[F] => CEResource[F, LowlevelMcpServer[F]] =
      comms => McpServerBridge[F](switchTo => McpServerBridge.PhaseInitial(server, comms, connectionInfo, switchTo))

    def jsonRpcConnectionHandler(logError: Exception => F[Unit])(using Async[F]): JsonRpcConnectionHandler[F] =
      new JsonRpcConnectionHandler[F]:
        override def open(conn: JsonRpcConnection[F]): CEResource[F, Unit] = server.start(conn, logError)
    end jsonRpcConnectionHandler

    def start(connection: JsonRpcConnection[F], logError: Exception => F[Unit])(using Async[F]): CEResource[F, Unit] =
      for
        jsonRpcServer <- LowlevelMcpServer.start(server.lowlevelFactory(connection.info), logError)
        _ <- CEResource.make(JsonRpcServer.start[F](jsonRpcServer, connection).useForever.start)(_.cancel)
      yield ()
    end start

  trait Client[F[_]]:
    val clientInfo: PartyInfo
    val capabilities: ClientCapabilities

    /** Pings the client to check if it is still alive. */
    def ping: F[Unit]

    /** Sends a logging message to the client. */
    def log(level: LoggingLevel, logger: Option[String], message: String): F[Unit]

    /** Sends a logging message to the client. */
    def log(level: LoggingLevel, logger: Option[String], data: Json): F[Unit]

    /** Ask the user for additional information, simpler variant for JsonSchema */
    def elicit(message: String, fields: ElicitationField*): F[Elicitation.Create.Response] =
      elicit(message, fields.toJsonSchema)

    /** Ask the user for additional information */
    def elicit(
      message: String,
      requestedSchema: JsonSchema,
      _meta: Meta = Meta.empty,
    ): F[Elicitation.Create.Response]

    def listRoots: F[Roots.ListRoots.Response]

    /** Ask the LLM for completions */
    def sample(
      messages: List[Sampling.Message],
      maxTokens: Int,
      modelPreferences: Option[Sampling.ModelPreferences] = None,
      systemPrompt: Option[String] = None,
      temperature: Option[Double] = None,
      includeContext: Option[String] = None,
      stopSequences: Option[List[String]] = None,
      metadata: Option[JsonObject] = None,
      _meta: Meta = Meta.empty,
    ): F[Sampling.CreateMessage.Response]
  end Client

  trait ConnectionInfo[F[_]]:
    /** the clients authentication (is kept up to date if the clients sends new bearer tokens) */
    def authentication: F[Authentication]

    def connection: JsonRpcConnection.Info
  end ConnectionInfo

  trait Session[F[_]]:
    val serverInfo: PartyInfo
    def instructions: F[Option[String]]
    protected[server] def maxPageSize: Int = 100
  end Session

  // all the session traits
  trait ToolProvider[F[_]] extends Session[F]:
    def tools: F[List[ToolFunction[F]]]
  trait ToolProviderWithChanges[F[_]] extends ToolProvider[F]:
    def toolChanges: fs2.Stream[F, Tool.ListChanged]

  trait PromptProvider[F[_]: MonadThrow] extends Session[F]:
    def prompts: F[List[PromptFunction[F]]]
    def prompt(name: String): F[PromptFunction[F]] =
      prompts.flatMap(_.find(_.prompt.name == name).toRight(McpError.error(
        ErrorCode.InvalidParams,
        s"Prompt $name not found",
      )).liftTo[F])
  trait PromptProviderWithChanges[F[_]] extends PromptProvider[F]:
    def promptChanges: fs2.Stream[F, Prompts.ListChanged]

  type Pageable[A] = (Cursor, A)
  trait ResourceProvider[F[_]: MonadThrow: Concurrent] extends Session[F]:
    def resources(after: Option[Cursor]): fs2.Stream[F, Pageable[Resource]]
    def resource(uri: String, context: CallContext[F]): F[ReadResource.Response]
    def resourceTemplates(after: Option[Cursor]): fs2.Stream[F, Pageable[ResourceTemplate[F]]]
    def resourceTemplate(uri: String): F[ResourceTemplate[F]] =
      resourceTemplates(None).compile.toList.flatMap(_.map(_._2).find(_.template.uriTemplate == uri)
        .toRight(McpError.error(ErrorCode.InvalidParams, s"Resource template $uri not found"))
        .liftTo[F])
  trait ResourceProviderWithChanges[F[_]] extends ResourceProvider[F]:
    def resourceChanges: fs2.Stream[F, Resources.ListChanged]
  trait ResourceSubscriptionProvider[F[_]] extends ResourceProviderWithChanges[F]:
    def resourceSubscription(uri: String, context: CallContext[F]): fs2.Stream[F, ResourceUpdated]

  trait RootChangeAwareProvider[F[_]] extends Session[F]:
    def rootsChanged: F[Unit]

  case class ClientInfo(
    clientInfo: PartyInfo,
    capabilities: ClientCapabilities,
    protocolVersion: String,
  )
  case class ResourceUpdated(meta: Meta = Meta.empty)

  enum ElicitationField:
    case Text(name: String, required: Boolean, title: Option[String] = None, description: Option[String] = None)
    case YesNo(name: String, required: Boolean, title: Option[String] = None, description: Option[String] = None)
    case Number(name: String, required: Boolean, title: Option[String] = None, description: Option[String] = None)

    def name: String
    def title: Option[String]
    def description: Option[String]
    def required: Boolean
    private[McpServer] def toJsonSchema: Json = Json.obj(
      "type" -> (this match
        case _: Text   => "string".asJson
        case _: YesNo  => "boolean".asJson
        case _: Number => "number".asJson),
      "title" -> title.getOrElse(name).asJson,
      "description" -> description.asJson,
      "required" -> required.asJson,
    ).deepDropNullValues

  extension (fields: Seq[ElicitationField])
    private def toJsonSchema: JsonSchema = JsonObject(
      "type" -> "object".asJson,
      "properties" -> Json.obj(
        fields.map(f => f.name -> f.toJsonSchema)*
      ).asJson,
      "required" -> fields.filter(_.required).map(_.name).asJson,
    )
