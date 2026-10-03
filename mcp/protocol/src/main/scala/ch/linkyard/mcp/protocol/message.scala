package ch.linkyard.mcp.protocol

import io.circe.Decoder

/** All requests of a (2026-07-28) client. The requests of earlier protocol versions are in the `legacy` package. */
type ClientRequest =
  Discover | Tool.ListTools | Tool.CallTool | Prompts.ListPrompts | Prompts.GetPrompt | Resources.ListResources |
    Resources.ListResourceTemplates | Resources.ReadResource | Subscriptions.Listen | Completion.Complete

type ServerResponse =
  Discover.Response | Tool.ListTools.Response | Tool.CallTool.Response | Prompts.ListPrompts.Response |
    Prompts.GetPrompt.Response | Resources.ListResources.Response | Resources.ListResourceTemplates.Response |
    Resources.ReadResource.Response | Subscriptions.Listen.Response | Completion.Complete.Response |
    InputRequiredResult

type ClientNotification = Cancelled

type ServerNotification =
  Cancelled | ProgressNotification | Prompts.ListChanged | Resources.Updated | Resources.ListChanged |
    Tool.ListChanged | Subscriptions.Acknowledged

object RequestMethod:
  val Discover = "server/discover"
  val ListTools = "tools/list"
  val CallTool = "tools/call"
  val ListPrompts = "prompts/list"
  val GetPrompt = "prompts/get"
  val ListResources = "resources/list"
  val ListResourceTemplates = "resources/templates/list"
  val ReadResource = "resources/read"
  val SubscriptionsListen = "subscriptions/listen"
  val Complete = "completion/complete"

  /** Requests that may be answered with an InputRequiredResult */
  val supportingInputRequired: Set[String] = Set(CallTool, GetPrompt, ReadResource)

object NotificationMethod:
  val Cancelled = "notifications/cancelled"
  val Progress = "notifications/progress"
  val ToolListChanged = "notifications/tools/list_changed"
  val PromptListChanged = "notifications/prompts/list_changed"
  val ResourceListChanged = "notifications/resources/list_changed"
  val ResourceUpdated = "notifications/resources/updated"
  val SubscriptionsAcknowledged = "notifications/subscriptions/acknowledged"

extension (request: ClientRequest)
  def method: String = request match
    case _: Discover                        => RequestMethod.Discover
    case _: Tool.ListTools                  => RequestMethod.ListTools
    case _: Tool.CallTool                   => RequestMethod.CallTool
    case _: Prompts.ListPrompts             => RequestMethod.ListPrompts
    case _: Prompts.GetPrompt               => RequestMethod.GetPrompt
    case _: Resources.ListResources         => RequestMethod.ListResources
    case _: Resources.ListResourceTemplates => RequestMethod.ListResourceTemplates
    case _: Resources.ReadResource          => RequestMethod.ReadResource
    case _: Subscriptions.Listen            => RequestMethod.SubscriptionsListen
    case _: Completion.Complete             => RequestMethod.Complete

  def meta: Meta = request match
    case r: Discover                        => r._meta
    case r: Tool.ListTools                  => r._meta
    case r: Tool.CallTool                   => r._meta
    case r: Prompts.ListPrompts             => r._meta
    case r: Prompts.GetPrompt               => r._meta
    case r: Resources.ListResources         => r._meta
    case r: Resources.ListResourceTemplates => r._meta
    case r: Resources.ReadResource          => r._meta
    case r: Subscriptions.Listen            => r._meta
    case r: Completion.Complete             => r._meta

/** What the client declares on every request (in the `_meta`). */
case class RequestInfo(
  protocolVersion: String,
  clientInfo: Option[Implementation],
  clientCapabilities: ClientCapabilities,
)

object RequestInfo:
  /** Reads the per-request fields, the error message describes the first missing/invalid field. */
  def fromMeta(meta: Meta): Either[String, RequestInfo] =
    def field[A: Decoder](key: String): Either[String, Option[A]] = meta.getAs[A](key) match
      case None            => Right(None)
      case Some(Right(a))  => Right(Some(a))
      case Some(Left(err)) => Left(s"Invalid _meta field $key: ${err.message}")
    def required[A: Decoder](key: String): Either[String, A] =
      field[A](key).flatMap(_.toRight(s"Missing required _meta field $key"))
    for
      version <- required[String](Meta.Key.ProtocolVersion)
      capabilities <- required[ClientCapabilities](Meta.Key.ClientCapabilities)
      info <- field[Implementation](Meta.Key.ClientInfo)
    yield RequestInfo(version, info, capabilities)
