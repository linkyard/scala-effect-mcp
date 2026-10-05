# Getting Started

This guide walks you through creating a minimal MCP server with a single tool, running it via stdio, and testing it with the MCP Inspector.

## Prerequisites

- [SBT](https://www.scala-sbt.org/) (Scala build tool)
- Java 17 or later

## 1. Add Dependencies

In your `build.sbt`, add the core server library and the stdio transport:

```scala
libraryDependencies ++= Seq(
  "ch.linkyard.mcp" %% "mcp-server"   % "0.3.3",
  "ch.linkyard.mcp" %% "jsonrpc2-stdio" % "0.3.3",
)
```

You also need a JSON codec deriver and a JSON schema deriver. The examples use [circe-generic](https://circe.github.io/circe/) and [scala-json-schema](https://github.com/lowmelvin/scala-json-schema):

```scala
libraryDependencies ++= Seq(
  "io.circe"       %% "circe-generic"    % "0.14.16",
  "com.melvinlow"  %% "scala-json-schema" % "0.2.0",
)
```

## 2. Define a Tool

A tool is a function the AI client can call. Define the input as a case class, the JSON schema is derived automatically.

```scala
import ch.linkyard.mcp.server.ToolFunction
import ch.linkyard.mcp.server.ToolFunction.Effect
import cats.effect.IO
import com.melvinlow.json.schema.generic.auto.given
import io.circe.generic.auto.given

case class EchoInput(text: String)

val echoTool: ToolFunction[IO] = ToolFunction.text(
  ToolFunction.Info(
    name = "echo",
    title = Some("Echo"),
    description = Some("Repeats the input text back to you"),
    effect = Effect.ReadOnly,
    isOpenWorld = false,
  ),
  (input: EchoInput, _) => IO.pure(input.text),
)
```

`ToolFunction.text` creates a tool that returns plain text. For structured (JSON) responses, use `ToolFunction.structured` instead. The second parameter of the function is the `RequestContext`, which gives access to the authentication of the caller, information about the client and progress reporting (`context.reportProgress`). Arguments that do not match the input class are reported to the model as a tool error, so that it can correct them.

## 3. Create the Server

An `McpServer` is stateless: the same instance serves all clients and all requests. Mix in provider traits to advertise what your server can do:

```scala
import cats.implicits.*
import ch.linkyard.mcp.protocol.Implementation
import ch.linkyard.mcp.server.*

class MyServer extends McpServer[IO] with McpServer.ToolProvider[IO]:
  override val serverInfo: Implementation = Implementation("My MCP Server", "0.1.0")
  override def instructions: IO[Option[String]] = None.pure
  override def tools(context: RequestContext[IO]): IO[List[ToolFunction[IO]]] = List(echoTool).pure
```

The library detects that `MyServer` extends `ToolProvider` and automatically tells the client that tools are available. The `context` parameter lets you vary the list of tools by the authentication of the caller (`context.authentication`). There is no handshake and no per-client state: what a client may see can depend on its authentication, but not on the connection.

## 4. Wire It Up and Run

Connect the server to the stdio transport and run it as an `IOApp`:

```scala
import cats.effect.{ExitCode, IO, IOApp}
import ch.linkyard.mcp.jsonrpc2.transport.StdioJsonRpcConnection

object Main extends IOApp:
  override def run(args: List[String]): IO[ExitCode] =
    // runs until the client closes the input
    MyServer().run(
      StdioJsonRpcConnection.create[IO],
      e => IO(System.err.println(s"Error: $e")),
    ).as(ExitCode.Success)
```

The second parameter is called with the errors that are not reported to the client (the client gets an internal error). Write to stderr and not to stdout, stdout carries the protocol messages.

By default the server also serves clients of the revisions 2025-06-18 and 2025-11-25. To serve only 2026-07-28, pass a config: `MyServer().run(connection, logError, McpServerConfig(supportLegacyClients = false))`.

See [SimpleEchoServer](example/simple-echo/src/main/scala/ch/linkyard/mcp/example/simpleEcho/SimpleEchoServer.scala) for the complete, runnable version.

## 5. Ask the User a Question

A tool can ask the user for more information while it runs (elicitation). Use `ToolFunction.interactiveText` (or `interactiveStructured`) and the `Ask` helper:

```scala
import ch.linkyard.mcp.protocol.ElicitAction

case class EmailInput(name: String)

val emailTool: ToolFunction[IO] = ToolFunction.interactiveText(
  ToolFunction.Info(
    name = "userEmail",
    title = Some("Get email for a user"),
    description = Some("Guesses the email address of a user"),
    effect = Effect.ReadOnly,
    isOpenWorld = true,
  ),
  (in: EmailInput, context: RequestContext[IO], ask: Ask[IO]) =>
    for
      answer <- ask.elicit(
        "company",
        s"Where does ${in.name} work?",
        ElicitationField.Text("company", required = true, description = Some("The name of the company")),
      )
      company = answer.action match
        case ElicitAction.Accept => answer.content.flatMap(_("company")).flatMap(_.asString)
        case _                   => None
      result <- IO.fromOption(company)(RuntimeException("The user did not answer"))
    yield s"${in.name.toLowerCase.replace(' ', '.')}@${result.toLowerCase}.com",
)
```

The server answers the first call with an "input required" result, the client asks the user and calls the tool again with the answer. The function therefore runs again from the start after each answer: `ask.elicit` returns the known answers immediately and the first question without an answer ends the run. Side effects before a question are repeated, so ask first and act afterwards. For full control, return `Outcome.InputRequired` (or `Outcome.elicit(...)`) from a `ToolFunction.native`.

Questions are only possible when the client declared support for elicitation. The state that travels through the client is signed and bound to the user and the request. A server that runs in several instances needs the same key on all of them (see `RequestStateConfig` in the [migration guide](MIGRATION.md)).

## 6. Build and Test

Build a fat JAR:

```bash
sbt assembly
```

Test with the [MCP Inspector](https://modelcontextprotocol.io/docs/tools/inspector):

```bash
npx @modelcontextprotocol/inspector java -jar target/scala-3.10.0/your-server-assembly.jar
```

Or configure your MCP client (e.g., Claude Desktop, Cursor) to launch your server:

```json
{
  "mcpServers": {
    "my-server": {
      "command": "java",
      "args": ["-jar", "/path/to/your-server-assembly.jar"]
    }
  }
}
```

## Serving over HTTP

For streamable HTTP, add `"ch.linkyard.mcp" %% "mcp-server-http4s"` instead of the stdio transport. The server turns into a handler factory that the route serves under `/mcp`:

```scala
import cats.effect.kernel.Resource
import ch.linkyard.mcp.jsonrpc2.transport.http4s.{McpServerRoute, SessionStore}
import com.comcast.ip4s.{Host, Port}
import org.http4s.ember.server.EmberServerBuilder
import scala.concurrent.duration.DurationInt

val program: Resource[IO, Unit] =
  for
    given SessionStore[IO] <- SessionStore.inMemory[IO](30.minutes)
    factory = MyServer().handlerFactory(McpServerConfig.default, e => IO(System.err.println(s"Error: $e")))
    route = McpServerRoute.route(factory)
    _ <- EmberServerBuilder.default[IO]
      .withHost(Host.fromString("127.0.0.1").get)
      .withPort(Port.fromInt(18283).get)
      .withHttpApp(route.orNotFound)
      .build
  yield ()
```

The `SessionStore` is only used for clients of the earlier revisions, which open a session. See [HttpDemoMcpServer](example/demo-http/src/main/scala/ch/linkyard/mcp/example/demo/HttpDemoMcpServer.scala) for the complete version.

## Next Steps

- **Add more tools**: define additional `ToolFunction` instances and add them to the `tools` list of your server
- **Add resources**: mix in `ResourceProvider[F]` to expose data the AI client can read
- **Add prompts**: mix in `PromptProvider[F]` to provide reusable prompt templates
- **Switch to HTTP**: use `mcp-server-http4s` for streamable HTTP transport instead of stdio (see below)
- **Add authentication**: see the [Simple Authenticated](example/simple-authenticated/) example for OAuth/Bearer token setup, the token is available as `context.authentication`
- **Notify about changes**: use the `ToolProviderWithChanges[F]`, `PromptProviderWithChanges[F]` and `ResourceProviderWithChanges[F]` traits
- Read the **[Migration guide](MIGRATION.md)** if you upgrade a server from 0.3.x
- Read the **[Architecture documentation](ARCHITECTURE.md)** for a deeper understanding of the library's types and layers
- Explore the **[examples](example/)** for more complete implementations
