package ch.linkyard.mcp.protocol

import cats.syntax.option.*
import ch.linkyard.mcp.jsonrpc2.JsonRpc
import io.circe.Decoder
import io.circe.DecodingFailure
import io.circe.Encoder
import io.circe.JsonObject
import io.circe.syntax.*

/** Converts between the json rpc messages and the (2026-07-28) protocol messages. */
object McpCodec:
  enum DecodeError:
    case UnknownMethod(method: String)
    case InvalidParams(failure: DecodingFailure)

  def decodeRequest(request: JsonRpc.Request): Either[DecodeError, ClientRequest] =
    val params = request.params.getOrElse(JsonObject.empty).asJson
    def as[A: Decoder]: Either[DecodeError, A] = params.as[A].left.map(DecodeError.InvalidParams.apply)
    request.method match
      case RequestMethod.Discover              => as[Discover]
      case RequestMethod.ListTools             => as[Tool.ListTools]
      case RequestMethod.CallTool              => as[Tool.CallTool]
      case RequestMethod.ListPrompts           => as[Prompts.ListPrompts]
      case RequestMethod.GetPrompt             => as[Prompts.GetPrompt]
      case RequestMethod.ListResources         => as[Resources.ListResources]
      case RequestMethod.ListResourceTemplates => as[Resources.ListResourceTemplates]
      case RequestMethod.ReadResource          => as[Resources.ReadResource]
      case RequestMethod.SubscriptionsListen   => as[Subscriptions.Listen]
      case RequestMethod.Complete              => as[Completion.Complete]
      case other                               => Left(DecodeError.UnknownMethod(other))

  def decodeNotification(notification: JsonRpc.Notification): Either[DecodeError, ClientNotification] =
    val params = notification.params.getOrElse(JsonObject.empty).asJson
    notification.method match
      case NotificationMethod.Cancelled => params.as[Cancelled].left.map(DecodeError.InvalidParams.apply)
      case other                        => Left(DecodeError.UnknownMethod(other))

  def encodeRequest(id: RequestId, request: ClientRequest): JsonRpc.Request =
    def req[A: Encoder.AsObject](a: A) = JsonRpc.Request(id.toJsonRpc, request.method, nonEmpty(a.asJsonObject))
    request match
      case m: Discover                        => req(m)
      case m: Tool.ListTools                  => req(m)
      case m: Tool.CallTool                   => req(m)
      case m: Prompts.ListPrompts             => req(m)
      case m: Prompts.GetPrompt               => req(m)
      case m: Resources.ListResources         => req(m)
      case m: Resources.ListResourceTemplates => req(m)
      case m: Resources.ReadResource          => req(m)
      case m: Subscriptions.Listen            => req(m)
      case m: Completion.Complete             => req(m)

  def encodeResponse(id: RequestId, response: ServerResponse): JsonRpc.Response.Success =
    def res[A: Encoder.AsObject](a: A) = JsonRpc.Response.Success(id.toJsonRpc, a.asJsonObject)
    response match
      case m: Discover.Response                        => res(m)
      case m: Tool.ListTools.Response                  => res(m)
      case m: Tool.CallTool.Response                   => res(m)
      case m: Prompts.ListPrompts.Response             => res(m)
      case m: Prompts.GetPrompt.Response               => res(m)
      case m: Resources.ListResources.Response         => res(m)
      case m: Resources.ListResourceTemplates.Response => res(m)
      case m: Resources.ReadResource.Response          => res(m)
      case m: Subscriptions.Listen.Response            => res(m)
      case m: Completion.Complete.Response             => res(m)
      case m: InputRequiredResult                      => res(m)

  def encodeNotification(notification: ServerNotification): JsonRpc.Notification =
    def not[A: Encoder.AsObject](method: String, a: A) = JsonRpc.Notification(method, nonEmpty(a.asJsonObject))
    notification match
      case m: Cancelled                  => not(NotificationMethod.Cancelled, m)
      case m: ProgressNotification       => not(NotificationMethod.Progress, m)
      case m: Prompts.ListChanged        => not(NotificationMethod.PromptListChanged, m)
      case m: Resources.Updated          => not(NotificationMethod.ResourceUpdated, m)
      case m: Resources.ListChanged      => not(NotificationMethod.ResourceListChanged, m)
      case m: Tool.ListChanged           => not(NotificationMethod.ToolListChanged, m)
      case m: Subscriptions.Acknowledged => not(NotificationMethod.SubscriptionsAcknowledged, m)

  def encodeClientNotification(notification: ClientNotification): JsonRpc.Notification =
    JsonRpc.Notification(NotificationMethod.Cancelled, nonEmpty(notification.asJsonObject))

  private def nonEmpty(o: JsonObject): Option[JsonObject] = o.some.filter(_.nonEmpty)

  extension (id: JsonRpc.Id)
    def fromJsonRpc: RequestId = id match
      case JsonRpc.Id.IdString(id) => RequestId.IdString(id)
      case JsonRpc.Id.IdInt(id)    => RequestId.IdNumber(id)

  extension (id: RequestId)
    def toJsonRpc: JsonRpc.Id = id match
      case RequestId.IdString(id) => JsonRpc.Id.IdString(id)
      case RequestId.IdNumber(id) => JsonRpc.Id.IdInt(id)
