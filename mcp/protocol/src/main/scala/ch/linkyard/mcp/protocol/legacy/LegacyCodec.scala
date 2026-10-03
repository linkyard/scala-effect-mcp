package ch.linkyard.mcp.protocol.legacy

import cats.syntax.option.*
import ch.linkyard.mcp.jsonrpc2.JsonRpc
import ch.linkyard.mcp.protocol.*
import ch.linkyard.mcp.protocol.McpCodec.DecodeError
import ch.linkyard.mcp.protocol.McpCodec.toJsonRpc
import io.circe.Decoder
import io.circe.JsonObject
import io.circe.syntax.*

/** Reads the messages of legacy clients and writes the messages for them: the 2026-07-28 messages are converted to
  * what the earlier versions expect.
  */
object LegacyCodec:
  object Method:
    val Initialize = "initialize"
    val Ping = "ping"
    val SetLevel = "logging/setLevel"
    val Subscribe = "resources/subscribe"
    val Unsubscribe = "resources/unsubscribe"
    val ElicitationCreate = "elicitation/create"
    val Initialized = "notifications/initialized"

  def decodeRequest(request: JsonRpc.Request): Either[DecodeError, LegacyRequest] =
    val params = request.params.getOrElse(JsonObject.empty).asJson
    def as[A: Decoder]: Either[DecodeError, A] = params.as[A].left.map(DecodeError.InvalidParams.apply)
    request.method match
      case Method.Initialize                   => as[Initialize]
      case Method.Ping                         => as[Ping]
      case Method.SetLevel                     => as[SetLevel]
      case Method.Subscribe                    => as[Subscribe]
      case Method.Unsubscribe                  => as[Unsubscribe]
      case RequestMethod.ListTools             => as[Tool.ListTools]
      case RequestMethod.CallTool              => as[Tool.CallTool]
      case RequestMethod.ListPrompts           => as[Prompts.ListPrompts]
      case RequestMethod.GetPrompt             => as[Prompts.GetPrompt]
      case RequestMethod.ListResources         => as[Resources.ListResources]
      case RequestMethod.ListResourceTemplates => as[Resources.ListResourceTemplates]
      case RequestMethod.ReadResource          => as[Resources.ReadResource]
      case RequestMethod.Complete              => as[Completion.Complete]
      case other                               => Left(DecodeError.UnknownMethod(other))

  def decodeNotification(notification: JsonRpc.Notification): Either[DecodeError, LegacyNotification] =
    notification.method match
      case Method.Initialized => Right(LegacyNotification.Initialized)
      case NotificationMethod.Cancelled =>
        notification.params.getOrElse(JsonObject.empty).asJson.as[Cancelled]
          .map(LegacyNotification.Cancelled.apply).left.map(DecodeError.InvalidParams.apply)
      case other => Right(LegacyNotification.Ignored(other))

  /** The result of the handshake */
  def encodeInitializeResult(id: RequestId, result: InitializeResult): JsonRpc.Response.Success =
    JsonRpc.Response.Success(id.toJsonRpc, downgrade(result.protocolVersion, result.asJsonObject))

  def encodeEmptyResult(id: RequestId): JsonRpc.Response.Success = JsonRpc.Response.Success(id.toJsonRpc, JsonObject.empty)

  /** The result of a request, for a client that speaks `version`. */
  def encodeResponse(version: String, id: RequestId, response: ServerResponse): JsonRpc.Response.Success =
    val modern = McpCodec.encodeResponse(id, response)
    modern.copy(result = downgrade(version, modern.result))

  /** The request to the client that asks the user for input. Only form mode exists in 2025-06-18; the url mode of
    * 2025-11-25 needs an id to relate the later completion to.
    */
  def encodeElicitation(id: RequestId, params: ElicitParams, elicitationId: String): JsonRpc.Request =
    val body = params match
      case ElicitParams.Form(message, schema) =>
        JsonObject("message" -> message.asJson, "requestedSchema" -> schema.asJson)
      case ElicitParams.Url(message, url) =>
        JsonObject(
          "mode" -> "url".asJson,
          "message" -> message.asJson,
          "url" -> url.asJson,
          "elicitationId" -> elicitationId.asJson,
        )
    JsonRpc.Request(id.toJsonRpc, Method.ElicitationCreate, body.some)

  /** The answer of the client to an elicitation. */
  def decodeElicitResult(response: JsonRpc.Response): ElicitResult = response match
    case JsonRpc.Response.Success(_, result) =>
      result.asJson.as[ElicitResult].getOrElse(ElicitResult(ElicitAction.Cancel))
    case _: JsonRpc.Response.Error => ElicitResult(ElicitAction.Cancel)

  /** What the result of 2026-07-28 has additionally, compared to the earlier versions. */
  def downgrade(version: String, result: JsonObject): JsonObject =
    val withoutNewFields = result.remove("resultType").remove("ttlMs").remove("cacheScope")
    val withoutServerInfo = withoutNewFields("_meta").flatMap(_.asObject).map(_.remove(Meta.Key.ServerInfo)) match
      case Some(meta) if meta.isEmpty => withoutNewFields.remove("_meta")
      case Some(meta)                 => withoutNewFields.add("_meta", meta.asJson)
      case None                       => withoutNewFields
    val withObjectStructuredContent = withoutServerInfo("structuredContent") match
      case Some(content) if !content.isObject => withoutServerInfo.remove("structuredContent")
      case _                                  => withoutServerInfo
    if version == LegacyVersion.V2025_06_18 then withoutIcons(withObjectStructuredContent)
    else withObjectStructuredContent

  /** 2025-06-18 does not know icons (and the additional fields of the implementation). */
  private def withoutIcons(result: JsonObject): JsonObject =
    def mapAll(o: JsonObject, key: String)(f: JsonObject => JsonObject): JsonObject =
      o(key).fold(o)(value => o.add(key, value.mapArray(_.map(_.mapObject(f)))))
    val lists = List("tools", "prompts", "resources", "resourceTemplates")
      .foldLeft(result)((o, key) => mapAll(o, key)(_.remove("icons")))
    val content = mapAll(lists, "content")(_.remove("icons"))
    val messages = mapAll(content, "messages")(message =>
      message("content").fold(message)(c => message.add("content", c.mapObject(_.remove("icons"))))
    )
    messages("serverInfo").fold(messages)(info =>
      messages.add("serverInfo", info.mapObject(_.remove("icons").remove("description").remove("websiteUrl")))
    )
