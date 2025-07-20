package ch.linkyard.mcp.server

import cats.effect.kernel.Async
import cats.effect.kernel.Resource as CEResource
import ch.linkyard.mcp.protocol.Completion
import ch.linkyard.mcp.protocol.Cursor
import ch.linkyard.mcp.protocol.Elicitation
import ch.linkyard.mcp.protocol.Initialize.ClientCapabilities
import ch.linkyard.mcp.protocol.Initialize.PartyInfo
import ch.linkyard.mcp.protocol.JsonSchema
import ch.linkyard.mcp.protocol.LoggingLevel
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
  /** Open a session, see the sub-trait of Session for the capabilities like tools, etc. */
  def connect(client: McpServer.Client[F]): CEResource[F, McpServer.Session[F]]

object McpServer:
  def create[F[_]: Async](server: McpServer[F]): Communication[F] => CEResource[F, LowlevelMcpServer[F]] = comms =>
    McpServerBridge[F](switchTo => McpServerBridge.PhaseInitial(server, comms, switchTo))

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
      _meta: Option[JsonObject] = None,
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
      _meta: Option[JsonObject] = None,
    ): F[Sampling.CreateMessage.Response]
  end Client

  trait Session[F[_]]:
    val serverInfo: PartyInfo
    def instructions: F[Option[String]]
    protected[server] def maxPageSize: Int = 100

  // all the session traits
  trait ToolProvider[F[_]] extends Session[F]:
    def tools: F[List[ToolFunction[F]]]
  trait ToolProviderWithChanges[F[_]] extends ToolProvider[F]:
    def toolChanges: fs2.Stream[F, Tool.ListChanged]

  trait PromptProvider[F[_]] extends Session[F]:
    def prompts: F[List[PromptFunction[F]]]
    def promptCompletions(
      promptName: String,
      argumentName: String,
      valueToComplete: String,
      otherArguments: Map[String, String],
      context: CallContext[F],
    ): F[Completion]
  trait PromptProviderWithChanges[F[_]] extends PromptProvider[F]:
    def promptChanges: fs2.Stream[F, Prompts.ListChanged]

  type Pageable[A] = (Cursor, A)
  trait ResourceProvider[F[_]] extends Session[F]:
    def resources(after: Option[Cursor]): fs2.Stream[F, Pageable[Resource]]
    def resource(uri: String, context: CallContext[F]): F[ReadResource.Response]
    def resourceTemplates(after: Option[Cursor]): fs2.Stream[F, Pageable[Resource.Template]]
    def resourceTemplateCompletions(
      uri: String,
      argumentName: String,
      valueToComplete: String,
      otherArguments: Map[String, String],
      context: CallContext[F],
    ): F[Completion]
  trait ResourceProviderWithChanges[F[_]] extends ResourceProvider[F]:
    def resourceChanges: fs2.Stream[F, Resources.ListChanged]
  trait ResourceSubscriptionProvider[F[_]] extends ResourceProviderWithChanges[F]:
    def resourceSubscription(uri: String, context: CallContext[F]): fs2.Stream[F, ResourceUpdated]

  trait RootChangeAwareProvider[F[_]] extends ToolProvider[F]:
    def rootsChanged: F[Unit]

  case class ClientInfo(
    clientInfo: PartyInfo,
    capabilities: ClientCapabilities,
    protocolVersion: String,
  )
  case class ResourceUpdated(meta: Option[JsonObject] = None)

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
