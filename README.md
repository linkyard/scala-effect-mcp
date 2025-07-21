[![CI](https://github.com/linkyard/scala-effect-mcp/actions/workflows/ci.yaml/badge.svg)](https://github.com/linkyard/scala-effect-mcp/actions/workflows/ci.yaml)
[![Scala Steward badge](https://img.shields.io/badge/Scala_Steward-helping-blue.svg?style=flat&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAAQCAMAAAARSr4IAAAAVFBMVEUAAACHjojlOy5NWlrKzcYRKjGFjIbp293YycuLa3pYY2LSqql4f3pCUFTgSjNodYRmcXUsPD/NTTbjRS+2jomhgnzNc223cGvZS0HaSD0XLjbaSjElhIr+AAAAAXRSTlMAQObYZgAAAHlJREFUCNdNyosOwyAIhWHAQS1Vt7a77/3fcxxdmv0xwmckutAR1nkm4ggbyEcg/wWmlGLDAA3oL50xi6fk5ffZ3E2E3QfZDCcCN2YtbEWZt+Drc6u6rlqv7Uk0LdKqqr5rk2UCRXOk0vmQKGfc94nOJyQjouF9H/wCc9gECEYfONoAAAAASUVORK5CYII=)](https://scala-steward.org)

# scala-effect-mcp
Library to implement model context protocol servers (MCP) in scala using fs2 and cats effect.

* Current version is 0.1.0
* Supported MCP protocol revision is 2025-06-18

## Getting Started

### Adding Dependencies

To use this library in your project, add the following dependencies to your `build.sbt`:

```scala
libraryDependencies += "ch.linkyard.mcp" %% "mcp-server" % "0.1.0"
libraryDependencies += "ch.linkyard.mcp" %% "jsonrpc2-stdio" % "0.1.0"
```

### Writing a Simple Echo Server

Here's a minimal example of an MCP server that provides a simple echo tool (see [SimpleEchoServer](example/simple-echo/src/main/scala/ch/linkyard/mcp/example/simpleEcho/SimpleEchoServer.scala)):

```scala
package example

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.kernel.Resource
import cats.implicits.*
import ch.linkyard.mcp.jsonrpc2.JsonRpcServer
import ch.linkyard.mcp.jsonrpc2.transport.StdioJsonRpcConnection
import ch.linkyard.mcp.protocol.Initialize.PartyInfo
import ch.linkyard.mcp.server.*
import ch.linkyard.mcp.server.ToolFunction.Effect
import com.melvinlow.json.schema.generic.auto.given
import io.circe.generic.auto.given

object SimpleEchoServer extends IOApp:
  // Define the input/output types for your tool
  case class EchoInput(text: String)

  // Create the echo tool function
  private def echoTool: ToolFunction[IO] = ToolFunction.text(
    ToolFunction.Info(
      "echo",
      Some("Echo"),
      Some("Repeats the input text back to you"),
      Effect.ReadOnly,
      isOpenWorld = false,
    ),
    (input: EchoInput, _) => IO(input.text),
  )

  // Define your server session
  private class Session extends McpServer.Session[IO] with McpServer.ToolProvider[IO]:
    override val serverInfo: PartyInfo = PartyInfo(
      "Simple Echo MCP",
      "1.0.0",
    )
    override def instructions: IO[Option[String]] = None.pure
    override val tools: IO[List[ToolFunction[IO]]] = List(echoTool).pure

  // Define your server
  private class Server extends McpServer[IO]:
    override def connect(client: McpServer.Client[IO]): Resource[IO, McpServer.Session[IO]] =
      Resource.pure(Session())

  override def run(args: List[String]): IO[ExitCode] =
    // run with stdio transport
    val serverFactory = McpServer.create(new Server)
    LowlevelMcpServer.start(serverFactory, e => IO(System.err.println(s"Error: $e")))
      .flatMap(jsonRpc => JsonRpcServer.start(jsonRpc, StdioJsonRpcConnection.resource[IO]))
      .useForever.as(ExitCode.Success)
  end run
end SimpleEchoServer

```

### Running Your Server

1. **Compile your server:**
   ```bash
   sbt assembly
   ```

3. **Configure your MCP client** (e.g., Claude Desktop) to use your server:
   ```json
   {
     "mcpServers": {
       "my-echo-server": {
         "command": "java",
         "args": ["-jar", "/path/to/your-server-assembly-1.0.0.jar"]
       }
     }
   }
   ```

## Key Concepts

The Model Context Protocol (MCP) defines several core concepts that enable AI assistants to interact with external systems and data sources. Here's an overview of the main concepts supported by this library, to use them have your McpServer implement the listed traits.

| Concept | Description | Supported by scala-effect-mcp | Required Trait(s) |
|---------|-------------|-----------------------------|-------------------|
| **Tools** | Functions that AI assistants can call to perform actions or retrieve information. Tools have defined input/output schemas and can be read-only, additive, or destructive. | ✅ | `ToolProvider[F]` |
| **Resources** | Data objects that can be read, listed, and subscribed to for real-time updates. Resources represent external data sources like files, databases, or APIs. | ✅ | `ResourceProvider[F]`, `ResourceProviderWithChanges[F]`, `ResourceSubscriptionProvider[F]` |
| **Prompts** | Predefined conversation templates that can be parameterized and used to generate consistent AI responses. Prompts help standardize interactions. | ✅ | `PromptProvider[F]`, `PromptProviderWithChanges[F]` |
| **Elicitation** | A mechanism for servers to request additional information from users during tool execution. This enables interactive workflows where the AI can ask clarifying questions. | ✅ | Available via `Client[F].elicit()` |
| **Sampling** | Allows servers to request AI model completions from the client, enabling servers to generate content or make decisions using the client's AI capabilities. | ✅ | Available via `Client[F].sample()` |
| **Roots** | Entry points that define the starting locations for resource hierarchies. Roots help organize and navigate complex data structures. | ✅ | `RootChangeAwareProvider[F]` |
| **Logging** | Built-in logging capabilities for servers to send diagnostic and informational messages to clients for debugging and monitoring. | ✅ | Available via `Client[F].log()` |
| **Completion** | Autocomplete functionality for prompt arguments and resource URIs, helping users and AI assistants discover available options. | ✅ | Built into `PromptProvider[F]` and `ResourceProvider[F]` |

For a detailed introduction to all MCP concepts, see [https://modelcontextprotocol.io/introduction](https://modelcontextprotocol.io/introduction).

Additional Considerations:
* Cancellation
  * Request cancellation is supported by cancelling the IO for pending request (eg client.sample)
  * When the client requests cancellation the the server will automatically cancel the IO for the request handling (eg the tool function)
* Logging
  * Prefer logging via the call context as this will automatically add the logger name
  * Logging level is automatically applied, only client.log calls that meet the logging level set by the client will be passed on to the your code
* Progress:
  * Progress can be reported to the client using the call context
  * Progress logs by the client are not passed on to the your code
* Json Schema
  * Json Schemas are automatically derived using [https://github.com/lowmelvin/scala-json-schema](scala-json-schema). You may use the @JsonSchemaField annotation to add additional attributes to the schema, for example `@JsonSchemaField("description", "my nice field".asJson).

## Testing Your MCP Server

The [MCP Inspector](https://modelcontextprotocol.io/docs/tools/inspector) is an interactive developer tool for testing and debugging MCP servers. It provides a comprehensive interface to test all aspects of your server implementation.

### Installation and Usage

The Inspector runs directly through `npx` without requiring installation:

```bash
npx @modelcontextprotocol/inspector <command>
```

### Testing Your Server

1. **Build your server:**
   ```bash
   sbt assembly
   ```

2. **Launch the Inspector with your server:**
   ```bash
   npx @modelcontextprotocol/inspector java -jar target/scala-3.3.0/your-server-assembly-0.1.0.jar
   ```

3. **Verify connectivity and capabilities:**
   - Check that the server connects successfully
   - Verify that all expected capabilities are negotiated
   - Review the server information and instructions

## Examples

This library provides two example servers that demonstrate different aspects of MCP functionality:

### [SimpleEchoServer](example/simple-echo/src/main/scala/ch/linkyard/mcp/example/simpleEcho/SimpleEchoServer.scala)

A minimal example that shows the basic structure of an MCP server with a single tool:

- **Single Tool**: Implements a simple echo tool that repeats input text
- **Basic Structure**: Demonstrates the essential components: `McpServer`, `Session`, and `ToolProvider`
- **Getting Started**: Perfect for understanding the fundamentals of MCP server implementation

### [DemoMcpServer](example/demo/src/main/scala/ch/linkyard/mcp/example/demo/DemoMcpServer.scala)

A more complex example that demonstrates all major MCP concepts:

- **Multiple Tools**:
  - `parrot`: Simple text echo with modification
  - `adder`: Mathematical operation with progress reporting and logging
  - `userEmail`: Complex tool using elicitation and sampling to find user emails
- **Prompts**: Story generation prompt with argument completion
- **Resources**: Animal database with 20 animals, resource templates, and autocomplete
- **Advanced Features**: Progress reporting, logging, elicitation, sampling, and completion

This example showcases:
- How to implement complex workflows using multiple MCP concepts
- Integration between different features (tools calling elicitation and sampling)
- Resource management with pagination and templates
- Error handling and user interaction patterns

Both examples can be built and tested using the MCP Inspector as described in the Testing section above.

## Project Modules

This project is organized as a multi-module Scala build. The main modules are:

- **jsonrpc2** (`ch.linkyard.mcp:jsonrpc2`)
  Provides a minimal JSON-RPC 2.0 protocol implementation, including message types and basic server logic. It is the foundation for communication between clients and servers.

- **transport/stdio** (`ch.linkyard.mcp:transport-stdio`)
  Implements a transport layer for JSON-RPC 2.0 over standard input/output (stdio), enabling communication via process pipes or terminals. Depends on the `jsonrpc2` module.

- **mcp/protocol** (`ch.linkyard.mcp:mcp-protocol`)
  Defines the Model Context Protocol (MCP) message types, codecs, and protocol-specific logic. This module specifies the structure and semantics of MCP requests, responses, and notifications. Depends on `jsonrpc2`.

- **mcp/server** (`ch.linkyard.mcp:mcp-server`)
  Implements the core server logic for handling MCP requests and notifications. It provides abstractions for request/response handling, error management, and progress reporting. Depends on both `jsonrpc2` and `mcp/protocol`.

Each module is defined as an SBT subproject and can be built, tested, and published independently. The modular structure allows for flexible reuse and extension of protocol, transport, and server logic.
