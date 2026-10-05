# Migrating from 0.3.x

Version 0.4.0 implements MCP revision 2026-07-28. In this revision a server is stateless: there is no handshake and no per-client session. The library API changed accordingly and is not compatible with 0.3.x. Clients of the revisions 2025-06-18 and 2025-11-25 are still served, the library adapts them to the stateless model (see the [Architecture documentation](ARCHITECTURE.md#legacy-clients)).

## Overview of the Changes

| 0.3.x | Now |
|---|---|
| `McpServer.initialize(client, info)` returns a `Resource` with a `Session` | `McpServer` is the stateless server itself, there is no `initialize` |
| `McpServer.Session[F]` | `McpServer[F]` (`serverInfo`, `instructions`, provider traits) |
| `McpServer.Client[F]` (`elicit`, `sample`, `log`, `listRoots`, `ping`) | removed, elicitation is `ask.elicit` or `Outcome.elicit` |
| `McpServer.ConnectionInfo[F]` | `RequestContext[F]` |
| `CallContext[F]` | `RequestContext[F]` with `authentication`, `client`, `transport`, `meta`, `input` and `reportProgress` |
| provider methods without parameters (`tools`, `prompts`) | provider methods take a `RequestContext` |
| `PartyInfo` | `Implementation` |
| tools, prompts and resources return the response | they return `F[Outcome[...]]` |
| `Client.sample`, `Client.listRoots`, `Client.log`, `RootChangeAwareProvider` | removed |
| `toolChanges: Stream[F, Tool.ListChanged]` | `toolChanges: Stream[F, Unit]`, server wide |
| `resourceSubscription(uri, context)` | `resourceUpdates(uri, context)` |
| `server.start(connection, logError).useForever` | `server.run(connection, logError)` |
| `server.jsonRpcConnectionHandler(logError)` | `server.handlerFactory(config, logError)` |
| `OAuthMiddleware` validator `String => IO[Boolean]` | `String => IO[TokenValidation]` |

## The Server Class

The session and the server of 0.3.x merge into one class. Everything that was in the session moves to the server, the methods get a `RequestContext` parameter.

Before:

```scala
class MySession extends McpServer.Session[IO] with McpServer.ToolProvider[IO]:
  override val serverInfo: PartyInfo = PartyInfo("My MCP Server", "0.1.0")
  override def instructions: IO[Option[String]] = None.pure
  override val tools: IO[List[ToolFunction[IO]]] = List(echoTool).pure

class MyServer extends McpServer[IO]:
  override def initialize(
    client: McpServer.Client[IO],
    info: McpServer.ConnectionInfo[IO],
  ): Resource[IO, McpServer.Session[IO]] =
    Resource.pure(MySession())
```

After:

```scala
class MyServer extends McpServer[IO] with McpServer.ToolProvider[IO]:
  override val serverInfo: Implementation = Implementation("My MCP Server", "0.1.0")
  override def instructions(context: RequestContext[IO]): IO[Option[String]] = None.pure
  override def tools(context: RequestContext[IO]): IO[List[ToolFunction[IO]]] = List(echoTool).pure
```

`PartyInfo` is now `ch.linkyard.mcp.protocol.Implementation` (additional optional fields `title`, `description`, `websiteUrl`, `icons`). The same instance serves all clients, so it must not keep state per client. What a client sees may depend on `context.authentication`, but not on the connection. State that you created in `initialize` (a database connection pool for example) moves into the constructor of the server or into a `Resource` that you allocate in your `main`.

The provider traits keep their names and get new signatures:

* `ToolProvider.tools(context)`, `PromptProvider.prompts(context)` and `PromptProvider.prompt(name, context)`
* `ResourceProvider.resources(after, context)`, `resourceTemplates(after, context)`, `resource(uri, context)` and `resourceTemplate(uriTemplate, context)`
* `maxPageSize` is a public `def` of `McpServer`
* `toolsCache`, `promptsCache` and `resourcesCache` are new (see [Cache Hints](#cache-hints))

## Request Context

`CallContext` is renamed to `RequestContext`. It replaces `ConnectionInfo` as well and is passed to every provider method, tool function, prompt function and resource template.

| Member | Description |
|---|---|
| `authentication: Authentication` | `Anonymous` or `BearerToken(token)` of this request (was `ConnectionInfo.authentication: F[Authentication]`) |
| `client: ClientInfo` | `info: Option[Implementation]` (self reported) and `capabilities: ClientCapabilities` |
| `transport: JsonRpcConnection.Info` | how the request arrived (was `ConnectionInfo.connection`) |
| `meta: Meta` | the `_meta` of the request |
| `input: InputContext` | the answers and the state of the client when this is the retry of a request that needed input |
| `reportProgress(progress, total, message)` | unchanged |

`CallContext.log` is removed (see [Removed Features](#removed-features)).

## Tools

Tool functions get the `RequestContext` instead of the `CallContext`. For the common case nothing changes, the second parameter is often ignored:

```scala
val echoTool: ToolFunction[IO] = ToolFunction.text(
  ToolFunction.Info("echo", Some("Echo"), Some("Repeats the input text back to you"), Effect.ReadOnly, isOpenWorld = false),
  (input: EchoInput, _) => IO(input.text),
)
```

Differences:

* `ToolFunction.native` and the `apply` method of `ToolFunction` return `F[Outcome[CallTool.Response]]`. Wrap a finished result with `Outcome.Complete(...)`.
* Arguments that cannot be decoded are reported as a tool error (`isError`), so that the model can correct them. They are no protocol error anymore.
* `ToolFunction.Info` has an optional `icons` field.

### Elicitation

In 0.3.x a tool called `client.elicit(...)` and waited for the answer inside the function. The server cannot call the client anymore. It answers the request with an "input required" result, the client asks the user and sends the request again with the answers. `ToolFunction.interactiveText` and `ToolFunction.interactiveStructured` keep the sequential style with the `Ask` helper.

Before:

```scala
def apply(client: McpServer.Client[IO]): ToolFunction[IO] = ToolFunction.text(
  ToolFunction.Info("userEmail", "Get email for a user".some, "Guesses the email".some, ToolFunction.Effect.ReadOnly, isOpenWorld = true),
  execute(client),
)

private def execute(client: McpServer.Client[IO])(in: Input, context: CallContext[IO]): IO[String] =
  for
    compResp <- client.elicit(
      s"Where does ${in.name} work?",
      McpServer.ElicitationField.Text("company", true, description = "The name of the company".some),
    )
    company <- (compResp.action match
      case Action.Accept => compResp.content.flatMap(_("company")).flatMap(_.asString)
      case _             => None
    ).toRight(McpError.error(ErrorCode.Other(-1), "User did not provide input")).liftTo[IO]
  yield s"${in.name.toLowerCase.replace(' ', '.')}@${company.toLowerCase.replace(' ', '-')}.com"
```

After:

```scala
def apply(): ToolFunction[IO] = ToolFunction.interactiveText(
  ToolFunction.Info("userEmail", "Get email for a user".some, "Guesses the email".some, ToolFunction.Effect.ReadOnly, isOpenWorld = true),
  execute,
)

private def execute(in: Input, context: RequestContext[IO], ask: Ask[IO]): IO[String] =
  for
    answer <- ask.elicit(
      "company",
      s"Where does ${in.name} work?",
      ElicitationField.Text("company", true, description = "The name of the company".some),
    )
    company <- (answer.action match
      case ElicitAction.Accept => answer.content.flatMap(_("company")).flatMap(_.asString)
      case _                   => None
    ).toRight(McpError.error(ErrorCode.Other(-1), "User did not provide input")).liftTo[IO]
  yield s"${in.name.toLowerCase.replace(' ', '.')}@${company.toLowerCase.replace(' ', '-')}.com"
```

The first parameter of `ask.elicit` is a key that identifies the answer. `ElicitationField` moved from `McpServer.ElicitationField` to `ch.linkyard.mcp.server.ElicitationField` and has a new `Choice` case. `Elicitation.Action` is now `ElicitAction`.

The function runs again from the start after every answer: `ask.elicit` returns the answers that are already known and the first question without an answer ends the run. Side effects before a question are repeated, so ask first and act afterwards. `ask.elicitAll(questions*)` puts several questions into one round, `ask.elicitUrl(key, message, url)` sends the user to a url.

If you need full control (for example to answer with your own state), return `Outcome.InputRequired(requests, state)` from `ToolFunction.native`, a prompt or a resource. `Outcome.elicit(key, message, fields*)` is the shortcut for a single form. On the retry, `context.input.elicit(key)` returns the answer and `context.input.state` the state.

```scala
ToolFunction.native[IO](
  info,
  argsSchema,
  (args, context) =>
    context.input.elicit("confirm") match
      case Some(answer) if answer.action == ElicitAction.Accept => doIt(args).map(Outcome.Complete(_))
      case Some(_)                                              => cancelled.map(Outcome.Complete(_))
      case None => IO.pure(Outcome.elicit("confirm", "Delete everything?", ElicitationField.YesNo("sure", required = true))),
)
```

Questions are only sent to clients that declared elicitation support, otherwise the request fails with a missing capability error. Legacy clients get the questions as `elicitation/create` requests, the library retries the request for them.

## Prompts and Resources

`PromptFunction.get` and `ResourceProvider.resource` return `F[Outcome[...]]`:

```scala
override def get(arguments: Map[String, String], context: RequestContext[IO]): IO[Outcome[Prompts.GetPrompt.Response]] =
  Outcome.Complete(Prompts.GetPrompt.Response(messages = ...)).pure

override def resource(uri: String, context: RequestContext[IO]): IO[Outcome[ReadResource.Response]] =
  if uri.startsWith("animal://") then AnimalResource.resource(uri).map(Outcome.Complete(_))
  else IO.raiseError(McpError.resourceNotFound(uri))
```

* A resource that does not exist is reported with `McpError.resourceNotFound(uri)`. The library chooses the error code per client (`-32602` for 2026-07-28, `-32002` for the earlier revisions).
* `Resource.Annotations` is now the top level `Annotations` (`audience`, `priority`, `lastModified`).
* `Resource.Embedded` is removed. `Content.EmbeddedResource` and `ReadResource.Response` use `Resource.Contents` (`Resource.Contents.Text(uri, mimeType, text)` and `Resource.Contents.Blob(uri, mimeType, blob)`). The `title` field of the embedded resource is gone.
* `Resource` and `Resource.Template` have an optional `icons` field.
* `Resource.Template` arguments are completed with `ResourceTemplate.completions(argumentName, valueToComplete, otherArguments, context)`, now with a `RequestContext`.

## Changes and Subscriptions

The change streams are server wide, there is no session to attach them to. They emit `Unit`:

```scala
class MyServer extends McpServer[IO] with McpServer.ToolProviderWithChanges[IO]:
  override def toolChanges: fs2.Stream[IO, Unit] = ...
```

The same applies to `promptChanges` and `resourceChanges`. `ResourceSubscriptionProvider.resourceSubscription` is renamed to `resourceUpdates(uri, context)` and emits `ResourceUpdated`. Clients of 2026-07-28 receive these notifications on a `subscriptions/listen` stream. Legacy clients receive them over their connection, the list changes after the handshake and the updates of a resource after `resources/subscribe`.

## Cache Hints

Servers can tell clients how long they may keep a result. Override `toolsCache`, `promptsCache` or `resourcesCache` (the latter covers resource lists and templates):

```scala
override def toolsCache: CacheHint = CacheHint.public(10.minutes)
```

`CacheHint.none` is the default (stale immediately). `CacheHint.public(ttl)` may be cached by shared caches, `CacheHint.perAuthorization(ttl)` only for the same authorization. Lists that depend on the authentication must not be `public`.

## Removed Features

Sampling, roots and logging are deprecated in 2026-07-28 and are not part of the API anymore: `Client.sample`, `Client.listRoots`, `Client.log`, `CallContext.log`, `RootChangeAwareProvider` and `Client.ping` are removed. The specification suggests these alternatives:

* **Roots**: pass the locations as tool parameters or encode them in resource URIs.
* **Sampling**: call the API of an LLM provider from your server.
* **Logging**: write to stderr (stdio) or use your usual logging, for example OpenTelemetry.

Legacy clients that send `logging/setLevel` or `notifications/roots/list_changed` get an answer or are ignored, the server does not advertise a `logging` capability.

The same applies to experimental tasks, the HTTP+SSE transport of 2024-11-05 and resumable streams, which were never supported.

## Starting the Server

### Stdio

Before:

```scala
override def run(args: List[String]): IO[ExitCode] =
  MyServer().start(
    StdioJsonRpcConnection.create[IO],
    e => IO(System.err.println(s"Error: $e")),
  ).useForever.as(ExitCode.Success)
```

After:

```scala
override def run(args: List[String]): IO[ExitCode] =
  MyServer().run(
    StdioJsonRpcConnection.create[IO],
    e => IO(System.err.println(s"Error: $e")),
  ).as(ExitCode.Success)
```

`run` returns when the client closes the input, `useForever` is not needed. The error callback takes a `Throwable` (it was an `Exception`). An optional third parameter takes the `McpServerConfig`.

### Streamable HTTP

Before:

```scala
for
  given SessionStore[IO] <- SessionStore.inMemory[IO](30.minutes)
  handler = DemoServer().jsonRpcConnectionHandler(logError)
  route = McpServerRoute.route(handler)
  _ <- EmberServerBuilder.default[IO]
    .withHttpApp(route.orNotFound)
    .build
yield ()
```

After:

```scala
for
  given SessionStore[IO] <- SessionStore.inMemory[IO](30.minutes)
  factory = DemoServer().handlerFactory(McpServerConfig.default, logError)
  route = McpServerRoute.route(factory)
  _ <- EmberServerBuilder.default[IO]
    .withHttpApp(route.orNotFound)
    .build
yield ()
```

`McpServerRoute.route(factory, config, root)` takes an optional `McpServerRouteConfig` (allowed origins, keep-alive interval) and the root path. The `SessionStore` is only used for legacy clients, which open a session with `initialize`. The routes validate the `MCP-Protocol-Version`, `Mcp-Method` and `Mcp-Name` headers and the `Origin` header (by default only `localhost` is accepted, also when it is the host of the request, which does not protect against DNS rebinding). If a browser based client sends an `Origin`, configure `McpServerRouteConfig(originAllowed = ...)`. Requests without `Origin` header are not affected. Tools with `x-mcp-header` annotations are validated against the `Mcp-Param-*` headers of the request.

## Authentication

`ConnectionInfo.authentication` (an effect that returned the current token) is replaced by `RequestContext.authentication`, a plain value that belongs to the request:

```scala
(input: HelloInput, context: RequestContext[IO]) =>
  IO.pure(s"Hello ${input.name}!\nYour authentication Token is ${context.authentication}")
```

The validator function of `OAuthMiddleware` returns a `TokenValidation` instead of a `Boolean`:

```scala
OAuthMiddleware(
  name = "my-server",
  authorizationServers = authServer.rootUri :: Nil,
  scopes = List("openid"),
  validateToken = token => IO.pure(TokenValidation.of(token.nonEmpty)),
  root = root,
)
```

* `TokenValidation.Valid`: access is granted
* `TokenValidation.Invalid`: answered with 401 and `error="invalid_token"`
* `TokenValidation.InsufficientScope(requiredScopes)`: answered with 403 and `error="insufficient_scope"`, the client can start a step-up authorization with these scopes
* `TokenValidation.of(boolean)` maps a validator that only knows valid and invalid

The `WWW-Authenticate` challenge now contains `resource_metadata` (RFC 9728) and the `scope` parameter, the protected resource metadata uses `scopes_supported`. Point `authorizationServers` to the upstream authorization server. `MinimalOAuthAuthorizationServer` serves metadata with the issuer of the upstream server under the host of your server, clients that follow the 2026-07-28 specification reject this. It is kept for compatibility.

## Request State

When a tool, prompt or resource asks the user, the state of the request travels through the client (`requestState`). The library signs it with HMAC-SHA256, limits its lifetime and binds it to the user, the method, the tool/prompt name or resource uri and the arguments. It is not encrypted.

By default `McpServerConfig` creates a random key per process. That works for a single instance but not for a deployment with several instances or restarts between the two calls: a state created by one instance is rejected by another. Configure the same key on all instances:

```scala
val config = McpServerConfig(
  requestState = RequestStateConfig(
    // the first key signs, all keys verify (put the new key first when you rotate)
    keys = List(sys.env("MCP_STATE_KEY").getBytes("UTF-8")),
    timeToLive = 10.minutes,
  )
)
val factory = MyServer().handlerFactory(config, logError)
```

Keys need at least 16 bytes. `principal` (default: a hash of the bearer token) decides who a state belongs to, a state is only accepted from the user that received it. With the default, a state is not valid anymore after the client refreshed its token. Pass a function `Authentication => String` that returns a stable user id to avoid this.

## McpServerConfig

```scala
McpServerConfig(
  requestState = RequestStateConfig.default,
  supportLegacyClients = true,
  maxLegacyInputRounds = 10,
)
```

* `requestState`: see above
* `supportLegacyClients`: also serve clients of 2025-06-18 and 2025-11-25. Set it to `false` to serve only 2026-07-28, then `initialize` is rejected on HTTP and no sessions are opened.
* `maxLegacyInputRounds`: how often a request that needs input is retried for a legacy client before it fails

Pass the config to `handlerFactory(config, logError)` or `run(connection, logError, config)`.

## Checklist

* Merge `Session` and `McpServer` into one class, remove `initialize`
* Add the `RequestContext` parameter to provider methods, rename `CallContext` to `RequestContext`
* Replace `PartyInfo` with `Implementation`
* Return `Outcome.Complete(...)` from prompts, resources and `ToolFunction.native`
* Replace `client.elicit` with `ask.elicit` in `ToolFunction.interactiveText` or with `Outcome.elicit`, and move side effects after the question
* Remove the uses of sampling, roots and logging
* Replace `Resource.Embedded` and `Resource.Annotations`
* Change the change streams to `Stream[F, Unit]` and rename `resourceSubscription` to `resourceUpdates`
* Start with `server.run(connection, logError)` (stdio) or `server.handlerFactory(config, logError)` and `McpServerRoute.route(factory)` (HTTP)
* Return a `TokenValidation` from the `OAuthMiddleware` validator
* Configure the keys of `RequestStateConfig` when you run several instances
