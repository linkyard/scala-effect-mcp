package ch.linkyard.mcp.server

import cats.effect.IO
import cats.effect.Ref
import cats.effect.kernel.Deferred
import cats.effect.unsafe.implicits.global
import ch.linkyard.mcp.jsonrpc2.Authentication
import ch.linkyard.mcp.jsonrpc2.JsonRpc
import ch.linkyard.mcp.jsonrpc2.JsonRpcConnection
import ch.linkyard.mcp.jsonrpc2.JsonRpcHandler
import ch.linkyard.mcp.protocol.*
import ch.linkyard.mcp.protocol.Resources.ReadResource
import ch.linkyard.mcp.server.McpServer.*
import com.melvinlow.json.schema.generic.auto.given
import io.circe.Json
import io.circe.JsonObject
import io.circe.generic.auto.given
import io.circe.syntax.*

import scala.concurrent.duration.DurationInt
import ch.linkyard.mcp.protocol.Prompts.GetPrompt.Response

object TestSupport:
  val ModernVersion = "2026-07-28"

  def clientMeta(
    capabilities: Json = Json.obj("elicitation" -> Json.obj()),
    version: String = ModernVersion,
    extra: (String, Json)*
  ): JsonObject = JsonObject(
    "io.modelcontextprotocol/protocolVersion" -> version.asJson,
    "io.modelcontextprotocol/clientCapabilities" -> capabilities,
    "io.modelcontextprotocol/clientInfo" -> Json.obj("name" -> "test".asJson, "version" -> "1".asJson),
  ).deepMerge(JsonObject(extra*))

  def request(id: Int, method: String, params: (String, Json)*): JsonRpc.Request =
    JsonRpc.Request(JsonRpc.Id.IdInt(id), method, Some(JsonObject(params*).add("_meta", clientMeta().asJson)))

  def requestWithMeta(id: Int, method: String, meta: JsonObject, params: (String, Json)*): JsonRpc.Request =
    JsonRpc.Request(JsonRpc.Id.IdInt(id), method, Some(JsonObject(params*).add("_meta", meta.asJson)))

  def context(auth: Authentication = Authentication.Anonymous): JsonRpcHandler.Context =
    JsonRpcHandler.Context(auth, JsonRpcConnection.Info.Other(Map.empty), None)

  /** The context of an http request with these `Mcp-Param-*` headers. */
  def httpContext(paramHeaders: Map[String, String]): JsonRpcHandler.Context =
    JsonRpcHandler.Context(
      Authentication.Anonymous,
      JsonRpcConnection.Info.Http(None, None, Map.empty),
      Some(paramHeaders),
    )

  /** All messages for the request (until the response). */
  def messages(handler: JsonRpcHandler[IO], request: JsonRpc.Request, auth: Authentication = Authentication.Anonymous)
    : IO[List[JsonRpc.Message]] =
    handler.request(request, context(auth)).compile.toList.timeout(10.seconds)

  extension (messages: List[JsonRpc.Message])
    def response: JsonRpc.Response = messages.last.asInstanceOf[JsonRpc.Response]
    def result: JsonObject = response match
      case JsonRpc.Response.Success(_, result) => result
      case other                               => throw new AssertionError(s"Expected a result but got $other")
    def error: JsonRpc.Response.Error = response match
      case e: JsonRpc.Response.Error => e
      case other                     => throw new AssertionError(s"Expected an error but got $other")
    def notifications: List[JsonRpc.Notification] = messages.collect { case n: JsonRpc.Notification => n }

  extension [A](io: IO[A]) def run: A = io.timeout(20.seconds).unsafeRunSync()

  case class Echo(text: String)
  case class Sum(a: Int, b: Int)
  case class Total(total: Int)

  /** A server with tools, prompts and resources (that can be changed and subscribed). */
  class FixtureServer(
    val toolsRef: Ref[IO, List[ToolFunction[IO]]],
    val cancelled: Deferred[IO, Unit],
    val started: Deferred[IO, Unit],
    val changes: fs2.concurrent.Topic[IO, Unit],
    val updates: fs2.concurrent.Topic[IO, String],
    val seen: Ref[IO, List[RequestContext[IO]]],
  ) extends McpServer[IO] with ToolProvider[IO] with ToolProviderWithChanges[IO] with PromptProvider[IO]
      with ResourceSubscriptionProvider[IO]:
    override val serverInfo: Implementation = Implementation("fixture", "1.2.3")
    override def instructions: IO[Option[String]] = IO.pure(Some("use the tools"))
    override val maxPageSize: Int = 2
    override def toolsCache: CacheHint = CacheHint.public(5.minutes)
    override def promptsCache: CacheHint = CacheHint.perAuthorization(1.minute)
    override def resourcesCache: CacheHint = CacheHint.public(10.seconds)

    private def info(name: String, effect: ToolFunction.Effect = ToolFunction.Effect.ReadOnly) =
      ToolFunction.Info(name, None, Some(s"the $name tool"), effect, isOpenWorld = false)

    private val echo = ToolFunction.text[IO, Echo](info("echo"), (in, _) => IO.pure(in.text))
    private val add = ToolFunction.structured[IO, Sum, Total](info("add"), (in, _) => IO.pure(Total(in.a + in.b)))
    private val failing = ToolFunction.text[IO, Echo](
      info("failing"),
      (_, _) => IO.raiseError(ToolFunction.ToolError(List(Content.Text("it failed")))),
    )
    private val crashing =
      ToolFunction.text[IO, Echo](info("crashing"), (_, _) => IO.raiseError(new RuntimeException("boom")))
    private val slow = ToolFunction.text[IO, Echo](
      info("slow"),
      (_, _) => started.complete(()) >> IO.never[String].onCancel(cancelled.complete(()).void),
    )
    private val progress = ToolFunction.text[IO, Echo](
      info("progress"),
      (_, ctx) => ctx.reportProgress(1, Some(2), Some("half")) >> ctx.reportProgress(2, Some(2)).as("done"),
    )
    private val whoami = ToolFunction.text[IO, Echo](
      info("whoami"),
      (_, ctx) => seen.update(_ :+ ctx).as(ctx.authentication.toString),
    )

    /** asks for the name, remembers the first question in the state */
    private val ask = ToolFunction.native[IO](
      info("ask"),
      JsonObject("type" -> "object".asJson),
      (_, ctx) =>
        ctx.input.elicit("name") match
          case Some(result) if result.action == ElicitAction.Accept =>
            val name = result.content.flatMap(_("name")).flatMap(_.asString).getOrElse("?")
            IO.pure(Outcome.Complete(Tool.CallTool.Response.Success(
              List(Content.Text(s"hello $name (${ctx.input.state.getOrElse("no state")})"))
            )))
          case Some(_) => IO.pure(Outcome.Complete(Tool.CallTool.Response.Error(List(Content.Text("declined")))))
          case None    =>
            IO.pure(Outcome.elicit("name", "Who are you?", ElicitationField.Text("name", true)).withState("asked")),
    )
    private val admin = ToolFunction.text[IO, Echo](info("admin"), (_, _) => IO.pure("admin"))
    private val iconic = ToolFunction.text[IO, Echo](
      info("iconic").copy(icons = Some(List(Icon("https://example.com/icon.png")))),
      (in, _) => IO.pure(in.text),
    )

    /** structured content that is not an object (not possible in the earlier versions) */
    private val arrays = ToolFunction.native[IO](
      info("arrays"),
      JsonObject("type" -> "object".asJson),
      (_, _) =>
        IO.pure(Outcome.Complete(Tool.CallTool.Response.Success(
          List(Content.Text("[1,2]")),
          Some(Json.arr(1.asJson, 2.asJson)),
        ))),
    )

    /** never satisfied */
    private val needy = ToolFunction.native[IO](
      info("needy"),
      JsonObject("type" -> "object".asJson),
      (_, _) => IO.pure(Outcome.elicit("again", "Still there?", ElicitationField.YesNo("yes", true))),
    )

    override def tools(context: RequestContext[IO]): IO[List[ToolFunction[IO]]] =
      toolsRef.get.map(_ ++ (if context.authentication == Authentication.BearerToken("admin") then List(admin) else Nil))
    override def toolChanges: fs2.Stream[IO, Unit] = changes.subscribe(10)

    private val prompt = new PromptFunction[IO]:
      override val prompt: Prompt = Prompt(
        "greet",
        description = Some("greets"),
        arguments = Some(List(PromptArgument("name", required = Some(true)))),
      )
      override def get(arguments: Map[String, String], context: RequestContext[IO]): IO[Outcome[Response]] =
        IO.pure(Outcome.Complete(Prompts.GetPrompt.Response(
          List(PromptMessage(Role.User, Content.Text(s"hello ${arguments.getOrElse("name", "you")}"))),
          Some("a greeting"),
        )))
      override def argumentCompletions(
        argumentName: String,
        valueToComplete: String,
        otherArguments: Map[String, String],
        context: RequestContext[IO],
      ): IO[Completion] = IO.pure(Completion(List("alice", "bob").filter(_.startsWith(valueToComplete))))
    override def prompts(context: RequestContext[IO]): IO[List[PromptFunction[IO]]] = IO.pure(List(prompt))

    val allResources: List[Resource] = List("a", "b", "c", "d", "e").map(n => Resource(s"test://$n", n))
    override def resources(after: Option[Cursor], context: RequestContext[IO]): fs2.Stream[IO, Pageable[Resource]] =
      val skip = after.map(c => allResources.indexWhere(_.name == c) + 1).getOrElse(0)
      fs2.Stream.emits(allResources.drop(skip).map(r => r.name -> r))
    private val template = new ResourceTemplate[IO]:
      override val template: Resource.Template = Resource.Template("test://{name}", "tests")
      override def completions(
        argumentName: String,
        valueToComplete: String,
        otherArguments: Map[String, String],
        context: RequestContext[IO],
      ): IO[Completion] = IO.pure(Completion(List("a", "b")))
    override def resourceTemplates(
      after: Option[Cursor],
      context: RequestContext[IO],
    ): fs2.Stream[IO, Pageable[ResourceTemplate[IO]]] = fs2.Stream.emit("1" -> template)
    override def resource(uri: String, context: RequestContext[IO]): IO[Outcome[ReadResource.Response]] =
      if allResources.exists(_.uri == uri) then
        IO.pure(Outcome.Complete(ReadResource.Response(
          List(Resource.Contents.Text(uri, Some("text/plain"), s"content of $uri")),
          ttlMs = 1000,
          cacheScope = CacheScope.Public,
        )))
      else McpError.raiseResourceNotFound[IO](uri)
    override def resourceChanges: fs2.Stream[IO, Unit] = fs2.Stream.empty
    override def resourceUpdates(uri: String, context: RequestContext[IO]): fs2.Stream[IO, ResourceUpdated] =
      updates.subscribe(10).filter(_ == uri).map(_ => ResourceUpdated())

    def setTools(tools: ToolFunction[IO]*): IO[Unit] = toolsRef.set(tools.toList)
  end FixtureServer

  object FixtureServer:
    def create: IO[FixtureServer] =
      for
        tools <- Ref.of[IO, List[ToolFunction[IO]]](Nil)
        cancelled <- Deferred[IO, Unit]
        started <- Deferred[IO, Unit]
        changes <- fs2.concurrent.Topic[IO, Unit]
        updates <- fs2.concurrent.Topic[IO, String]
        seen <- Ref.of[IO, List[RequestContext[IO]]](Nil)
        server = FixtureServer(tools, cancelled, started, changes, updates, seen)
        _ <- tools.set(List(
          server.echo,
          server.add,
          server.failing,
          server.crashing,
          server.slow,
          server.progress,
          server.whoami,
          server.ask,
          server.iconic,
          server.arrays,
          server.needy,
        ))
      yield server
