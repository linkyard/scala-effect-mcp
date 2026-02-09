# Getting Started

This guide walks you through creating a minimal MCP server with a single tool, running it via stdio, and testing it with the MCP Inspector.

## Prerequisites

- [SBT](https://www.scala-sbt.org/) (Scala build tool)
- Java 11 or later

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
  "io.circe"       %% "circe-generic"    % "0.14.15",
  "com.melvinlow"  %% "scala-json-schema" % "0.2.0",
)
```

## 2. Define a Tool

A tool is a function the AI client can call. Define the input as a case class -- the JSON schema is derived automatically.

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

`ToolFunction.text` creates a tool that returns plain text. For structured (JSON) responses, use `ToolFunction.structured` instead.

## 3. Create a Session

A `Session` represents a connected client. Mix in capability traits to advertise what your server can do:

```scala
import cats.implicits.*
import ch.linkyard.mcp.protocol.Initialize.PartyInfo
import ch.linkyard.mcp.server.*

class MySession extends McpServer.Session[IO] with McpServer.ToolProvider[IO]:
  override val serverInfo: PartyInfo = PartyInfo("My MCP Server", "0.1.0")
  override def instructions: IO[Option[String]] = None.pure
  override val tools: IO[List[ToolFunction[IO]]] = List(echoTool).pure
```

The library detects that `MySession` extends `ToolProvider` and automatically tells the client that tools are available.

## 4. Create the Server

`McpServer` is the factory that creates a session for each incoming connection:

```scala
import cats.effect.kernel.Resource

class MyServer extends McpServer[IO]:
  override def initialize(
    client: McpServer.Client[IO],
    info: McpServer.ConnectionInfo[IO],
  ): Resource[IO, McpServer.Session[IO]] =
    Resource.pure(MySession())
```

The `client` parameter lets you communicate back to the AI client (logging, elicitation, sampling). The `info` parameter provides connection metadata and authentication.

## 5. Wire It Up and Run

Connect the server to the stdio transport and run it as an `IOApp`:

```scala
import cats.effect.{ExitCode, IO, IOApp}
import ch.linkyard.mcp.jsonrpc2.transport.StdioJsonRpcConnection

object Main extends IOApp:
  override def run(args: List[String]): IO[ExitCode] =
    MyServer().start(
      StdioJsonRpcConnection.create[IO],
      e => IO(System.err.println(s"Error: $e")),
    ).useForever.as(ExitCode.Success)
```

See [SimpleEchoServer](example/simple-echo/src/main/scala/ch/linkyard/mcp/example/simpleEcho/SimpleEchoServer.scala) for the complete, runnable version.

## 6. Build and Test

Build a fat JAR:

```bash
sbt assembly
```

Test with the [MCP Inspector](https://modelcontextprotocol.io/docs/tools/inspector):

```bash
npx @modelcontextprotocol/inspector java -jar target/scala-3.8.1/your-server-assembly.jar
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

## Next Steps

- **Add more tools** -- define additional `ToolFunction` instances and add them to your session's `tools` list
- **Add resources** -- mix in `ResourceProvider[F]` to expose data the AI client can read
- **Add prompts** -- mix in `PromptProvider[F]` to provide reusable prompt templates
- **Switch to HTTP** -- use `mcp-server-http4s` for streamable HTTP transport instead of stdio
- **Add authentication** -- see the [Simple Authenticated](example/simple-authenticated/) example for OAuth/Bearer token setup
- Read the **[Architecture documentation](ARCHITECTURE.md)** for a deeper understanding of the library's types and layers
- Explore the **[examples](example/)** for more complete implementations
