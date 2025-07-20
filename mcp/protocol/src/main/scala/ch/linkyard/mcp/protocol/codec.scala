package ch.linkyard.mcp.protocol

import cats.syntax.option.*
import ch.linkyard.mcp.jsonrpc2.JsonRpc
import io.circe.Decoder
import io.circe.DecodingFailure
import io.circe.Encoder
import io.circe.JsonObject
import io.circe.syntax.*

object Codec:
  def encodeServerRequest(id: RequestId, request: ServerRequest): JsonRpc.Request = request match
    case m: Ping                   => JsonRpc.Request(id.toJsonRpc, m.method.key, m.asJsonObject.normalize)
    case m: Elicitation.Create     => JsonRpc.Request(id.toJsonRpc, m.method.key, m.asJsonObject.normalize)
    case m: Roots.ListRoots        => JsonRpc.Request(id.toJsonRpc, m.method.key, m.asJsonObject.normalize)
    case m: Sampling.CreateMessage => JsonRpc.Request(id.toJsonRpc, m.method.key, m.asJsonObject.normalize)

  def encodeClientRequest(id: RequestId, request: ClientRequest): JsonRpc.Request = request match
    case m: Ping                            => JsonRpc.Request(id.toJsonRpc, m.method.key, m.asJsonObject.normalize)
    case m: Initialize                      => JsonRpc.Request(id.toJsonRpc, m.method.key, m.asJsonObject.normalize)
    case m: Prompts.ListPrompts             => JsonRpc.Request(id.toJsonRpc, m.method.key, m.asJsonObject.normalize)
    case m: Prompts.GetPrompt               => JsonRpc.Request(id.toJsonRpc, m.method.key, m.asJsonObject.normalize)
    case m: Resources.ListResources         => JsonRpc.Request(id.toJsonRpc, m.method.key, m.asJsonObject.normalize)
    case m: Resources.ListResourceTemplates => JsonRpc.Request(id.toJsonRpc, m.method.key, m.asJsonObject.normalize)
    case m: Resources.ReadResource          => JsonRpc.Request(id.toJsonRpc, m.method.key, m.asJsonObject.normalize)
    case m: Resources.Subscribe             => JsonRpc.Request(id.toJsonRpc, m.method.key, m.asJsonObject.normalize)
    case m: Resources.Unsubscribe           => JsonRpc.Request(id.toJsonRpc, m.method.key, m.asJsonObject.normalize)
    case m: Tool.ListTools                  => JsonRpc.Request(id.toJsonRpc, m.method.key, m.asJsonObject.normalize)
    case m: Tool.CallTool                   => JsonRpc.Request(id.toJsonRpc, m.method.key, m.asJsonObject.normalize)
    case m: Logging.SetLevel                => JsonRpc.Request(id.toJsonRpc, m.method.key, m.asJsonObject.normalize)
    case m: Completion.Complete             => JsonRpc.Request(id.toJsonRpc, m.method.key, m.asJsonObject.normalize)

  def encodeResponse(id: RequestId, response: ServerResponse | ClientResponse): JsonRpc.Response = response match
    case m: Ping.Response => JsonRpc.Response.Success(id.toJsonRpc, m.asJsonObject.normalize.getOrElse(JsonObject.empty))
    case m: Initialize.Response =>
      JsonRpc.Response.Success(id.toJsonRpc, m.asJsonObject.normalize.getOrElse(JsonObject.empty))
    case m: Prompts.ListPrompts.Response =>
      JsonRpc.Response.Success(id.toJsonRpc, m.asJsonObject.normalize.getOrElse(JsonObject.empty))
    case m: Prompts.GetPrompt.Response =>
      JsonRpc.Response.Success(id.toJsonRpc, m.asJsonObject.normalize.getOrElse(JsonObject.empty))
    case m: Resources.ListResources.Response =>
      JsonRpc.Response.Success(id.toJsonRpc, m.asJsonObject.normalize.getOrElse(JsonObject.empty))
    case m: Resources.ListResourceTemplates.Response =>
      JsonRpc.Response.Success(id.toJsonRpc, m.asJsonObject.normalize.getOrElse(JsonObject.empty))
    case m: Resources.ReadResource.Response =>
      JsonRpc.Response.Success(id.toJsonRpc, m.asJsonObject.normalize.getOrElse(JsonObject.empty))
    case m: Resources.Subscribe.Response =>
      JsonRpc.Response.Success(id.toJsonRpc, m.asJsonObject.normalize.getOrElse(JsonObject.empty))
    case m: Resources.Unsubscribe.Response =>
      JsonRpc.Response.Success(id.toJsonRpc, m.asJsonObject.normalize.getOrElse(JsonObject.empty))
    case m: Tool.ListTools.Response =>
      JsonRpc.Response.Success(id.toJsonRpc, m.asJsonObject.normalize.getOrElse(JsonObject.empty))
    case m: Tool.CallTool.Response =>
      JsonRpc.Response.Success(id.toJsonRpc, m.asJsonObject.normalize.getOrElse(JsonObject.empty))
    case m: Logging.SetLevel.Response =>
      JsonRpc.Response.Success(id.toJsonRpc, m.asJsonObject.normalize.getOrElse(JsonObject.empty))
    case m: Completion.Complete.Response =>
      JsonRpc.Response.Success(id.toJsonRpc, m.asJsonObject.normalize.getOrElse(JsonObject.empty))
    case m: Elicitation.Create.Response =>
      JsonRpc.Response.Success(id.toJsonRpc, m.asJsonObject.normalize.getOrElse(JsonObject.empty))
    case m: Roots.ListRoots.Response =>
      JsonRpc.Response.Success(id.toJsonRpc, m.asJsonObject.normalize.getOrElse(JsonObject.empty))
    case m: Sampling.CreateMessage.Response =>
      JsonRpc.Response.Success(id.toJsonRpc, m.asJsonObject.normalize.getOrElse(JsonObject.empty))

  def encodeServerNotification(notification: ServerNotification): JsonRpc.Notification = notification match
    case m: Cancelled              => JsonRpc.Notification(m.method.key, m.asJsonObject.normalize)
    case m: ProgressNotification   => JsonRpc.Notification(m.method.key, m.asJsonObject.normalize)
    case m: Resources.Updated      => JsonRpc.Notification(m.method.key, m.asJsonObject.normalize)
    case m: Resources.ListChanged  => JsonRpc.Notification(m.method.key, m.asJsonObject.normalize)
    case m: Tool.ListChanged       => JsonRpc.Notification(m.method.key, m.asJsonObject.normalize)
    case m: Prompts.ListChanged    => JsonRpc.Notification(m.method.key, m.asJsonObject.normalize)
    case m: Logging.LoggingMessage => JsonRpc.Notification(m.method.key, m.asJsonObject.normalize)

  def encodeClientNotification(notification: ClientNotification): JsonRpc.Notification = notification match
    case m: Initialized          => JsonRpc.Notification(m.method.key, m.asJsonObject.normalize)
    case m: Roots.ListChanged    => JsonRpc.Notification(m.method.key, m.asJsonObject.normalize)
    case m: Cancelled            => JsonRpc.Notification(m.method.key, m.asJsonObject.normalize)
    case m: ProgressNotification => JsonRpc.Notification(m.method.key, m.asJsonObject.normalize)

  /** Convert a JsonRpc.Request to protocol Request based on the method field */
  def fromJsonRpc(jsonRpcRequest: JsonRpc.Request): Either[DecodingFailure, (RequestId, Request)] =
    val requestId = jsonRpcRequest.id.fromJsonRpc
    val params = jsonRpcRequest.params.getOrElse(JsonObject.empty).asJson
    (jsonRpcRequest.method match
      case RequestMethod.Initialize.key            => params.as[Initialize].map(identity)
      case RequestMethod.Ping.key                  => params.as[Ping].map(identity)
      case RequestMethod.ListResources.key         => params.as[Resources.ListResources].map(identity)
      case RequestMethod.ListResourceTemplates.key => params.as[Resources.ListResourceTemplates].map(identity)
      case RequestMethod.ReadResource.key          => params.as[Resources.ReadResource].map(identity)
      case RequestMethod.Subscribe.key             => params.as[Resources.Subscribe].map(identity)
      case RequestMethod.Unsubscribe.key           => params.as[Resources.Unsubscribe].map(identity)
      case RequestMethod.ListPrompts.key           => params.as[Prompts.ListPrompts].map(identity)
      case RequestMethod.GetPrompt.key             => params.as[Prompts.GetPrompt].map(identity)
      case RequestMethod.ListTools.key             => params.as[Tool.ListTools].map(identity)
      case RequestMethod.CallTool.key              => params.as[Tool.CallTool].map(identity)
      case RequestMethod.SetLevel.key              => params.as[Logging.SetLevel].map(identity)
      case RequestMethod.CreateMessage.key         => params.as[Sampling.CreateMessage].map(identity)
      case RequestMethod.Complete.key              => params.as[Completion.Complete].map(identity)
      case RequestMethod.ListRoots.key             => params.as[Roots.ListRoots].map(identity)
      case RequestMethod.ElicitCreate.key          => params.as[Elicitation.Create].map(identity)
      case method => Left(DecodingFailure(s"Unknown request method: $method", List.empty))
    ).map(requestId -> _)

  /** Convert a JsonRpc.Response to a specific protocol Response type */
  def fromJsonRpc[A <: McpResponse: Decoder](jsonRpcResponse: JsonRpc.Response): Either[DecodingFailure, A] =
    jsonRpcResponse match
      case JsonRpc.Response.Success(id, result) =>
        result.asJson.as[A]
      case JsonRpc.Response.Error(id, code, message, data) =>
        Left(DecodingFailure(s"JSON-RPC error: $message (code: $code)", List.empty))

  /** Convert a JsonRpc.Notification to protocol Notification based on the method field */
  def fromJsonRpc(jsonRpcNotification: JsonRpc.Notification): Either[DecodingFailure, Notification] =
    val params = jsonRpcNotification.params.getOrElse(JsonObject.empty).asJson
    jsonRpcNotification.method match
      case NotificationMethod.Initialized.key         => params.as[Initialized].map(identity)
      case NotificationMethod.Cancelled.key           => params.as[Cancelled].map(identity)
      case NotificationMethod.Progress.key            => params.as[ProgressNotification].map(identity)
      case NotificationMethod.ResourceUpdated.key     => params.as[Resources.Updated].map(identity)
      case NotificationMethod.ResourceListChanged.key => params.as[Resources.ListChanged].map(identity)
      case NotificationMethod.ToolListChanged.key     => params.as[Tool.ListChanged].map(identity)
      case NotificationMethod.PromptListChanged.key   => params.as[Prompts.ListChanged].map(identity)
      case NotificationMethod.RootsListChanged.key    => params.as[Roots.ListChanged].map(identity)
      case NotificationMethod.LoggingMessage.key      => params.as[Logging.LoggingMessage].map(identity)
      case method => Left(DecodingFailure(s"Unknown notification method: $method", List.empty))

  // Helper method to convert JsonRpc.Id to RequestId
  extension (id: JsonRpc.Id)
    def fromJsonRpc: RequestId = id match
      case JsonRpc.Id.IdString(id) => RequestId.IdString(id)
      case JsonRpc.Id.IdInt(id)    => RequestId.IdNumber(id)

  extension (id: RequestId)
    def toJsonRpc: JsonRpc.Id = id match
      case RequestId.IdString(id) => JsonRpc.Id.IdString(id)
      case RequestId.IdNumber(id) => JsonRpc.Id.IdInt(id)

  extension (jsonObject: JsonObject)
    private def normalize: Option[JsonObject] =
      jsonObject.mapValues(_.deepDropNullValues).filter((_, v) => !v.isNull).some.filter(_.nonEmpty)
