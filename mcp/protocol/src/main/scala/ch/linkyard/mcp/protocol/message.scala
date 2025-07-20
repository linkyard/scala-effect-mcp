package ch.linkyard.mcp.protocol

trait Request:
  val method: RequestMethod
  type Response <: ClientResponse | ServerResponse
trait McpResponse
trait Notification:
  val method: NotificationMethod

type ServerRequest = Ping | Elicitation.Create | Roots.ListRoots | Sampling.CreateMessage
type ClientResponse =
  Ping.Response | Elicitation.Create.Response | Roots.ListRoots.Response | Sampling.CreateMessage.Response

type ClientRequest =
  Ping | Initialize | Prompts.ListPrompts | Prompts.GetPrompt | Resources.ListResources | Resources.ListResourceTemplates | Resources.ReadResource | Resources.Subscribe | Resources.Unsubscribe | Tool.ListTools | Tool.CallTool | Logging.SetLevel | Completion.Complete
type ServerResponse =
  Ping.Response | Initialize.Response | Prompts.ListPrompts.Response | Prompts.ListPrompts.Response | Prompts.GetPrompt.Response | Resources.ListResources.Response | Resources.ListResourceTemplates.Response | Resources.ReadResource.Response | Resources.Subscribe.Response | Resources.Unsubscribe.Response | Tool.ListTools.Response | Tool.CallTool.Response | Logging.SetLevel.Response | Completion.Complete.Response

type ClientNotification = Initialized | Roots.ListChanged | Cancelled | ProgressNotification
type ServerNotification =
  Cancelled | ProgressNotification | Prompts.ListChanged | Resources.Updated | Resources.ListChanged | Tool.ListChanged | Logging.LoggingMessage

// we define them as enums for exhaustiveness checking
enum RequestMethod(val key: String):
  // Basic protocol methods
  case Initialize extends RequestMethod("initialize")
  case Ping extends RequestMethod("ping")
  // Resources methods
  case ListResources extends RequestMethod("resources/list")
  case ListResourceTemplates extends RequestMethod("resources/templates/list")
  case ReadResource extends RequestMethod("resources/read")
  case Subscribe extends RequestMethod("resources/subscribe")
  case Unsubscribe extends RequestMethod("resources/unsubscribe")
  // Prompts methods
  case ListPrompts extends RequestMethod("prompts/list")
  case GetPrompt extends RequestMethod("prompts/get")
  // Tools methods
  case ListTools extends RequestMethod("tools/list")
  case CallTool extends RequestMethod("tools/call")
  // Logging methods
  case SetLevel extends RequestMethod("logging/setLevel")
  // Sampling methods
  case CreateMessage extends RequestMethod("sampling/createMessage")
  // Autocomplete methods
  case Complete extends RequestMethod("completion/complete")
  // Roots methods
  case ListRoots extends RequestMethod("roots/list")
  // Elicitation methods
  case ElicitCreate extends RequestMethod("elicitation/create")

enum NotificationMethod(val key: String):
  // Basic protocol notifications
  case Initialized extends NotificationMethod("notifications/initialized")
  case Cancelled extends NotificationMethod("notifications/cancelled")
  case Progress extends NotificationMethod("notifications/progress")
  // Resource notifications
  case ResourceUpdated extends NotificationMethod("notifications/resources/updated")
  case ResourceListChanged extends NotificationMethod("notifications/resources/list_changed")
  // Tool notifications
  case ToolListChanged extends NotificationMethod("notifications/tools/list_changed")
  // Prompt notifications
  case PromptListChanged extends NotificationMethod("notifications/prompts/list_changed")
  // Roots notifications
  case RootsListChanged extends NotificationMethod("notifications/roots/list_changed")
  // Logging notifications
  case LoggingMessage extends NotificationMethod("notifications/message")
