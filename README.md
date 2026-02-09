[![CI](https://github.com/linkyard/scala-effect-mcp/actions/workflows/ci.yaml/badge.svg)](https://github.com/linkyard/scala-effect-mcp/actions/workflows/ci.yaml)
[![Scala Steward badge](https://img.shields.io/badge/Scala_Steward-helping-blue.svg?style=flat&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAAQCAMAAAARSr4IAAAAVFBMVEUAAACHjojlOy5NWlrKzcYRKjGFjIbp293YycuLa3pYY2LSqql4f3pCUFTgSjNodYRmcXUsPD/NTTbjRS+2jomhgnzNc223cGvZS0HaSD0XLjbaSjElhIr+AAAAAXRSTlMAQObYZgAAAHlJREFUCNdNyosOwyAIhWHAQS1Vt7a77/3fcxxdmv0xwmckutAR1nkm4ggbyEcg/wWmlGLDAA3oL50xi6fk5ffZ3E2E3QfZDCcCN2YtbEWZt+Drc6u6rlqv7Uk0LdKqqr5rk2UCRXOk0vmQKGfc94nOJyQjouF9H/wCc9gECEYfONoAAAAASUVORK5CYII=)](https://scala-steward.org)

# scala-effect-mcp

Library to implement model context protocol servers (MCP) in Scala using fs2 and cats effect.

* Current version is 0.3.3
* Supported MCP protocol revision is 2025-06-18
* Supported Transports: Stdio and Streamable HTTP

---

**[Getting Started](GETTING-STARTED.md)** | **[Key Concepts](#key-concepts)** | **[Architecture](ARCHITECTURE.md)** | **[Examples](#examples)** | **[Testing](#testing-your-mcp-server)** | **[Modules](#project-modules)**

---

## Getting Started

See the **[Getting Started guide](GETTING-STARTED.md)** for a step-by-step walkthrough of building your first MCP server.

## Key Concepts

The Model Context Protocol (MCP) defines several core concepts that enable AI assistants to interact with external systems and data sources. Have your `Session` implement the listed traits to expose capabilities.
For a deeper look at the library's types, layers, and internal wiring, see the **[Architecture documentation](ARCHITECTURE.md)**.

| Concept | Description | Required Trait(s) |
|---------|-------------|-------------------|
| **Tools** | Functions that AI assistants can call to perform actions or retrieve information. Tools have defined input/output schemas and can be read-only, additive, or destructive. | `ToolProvider[F]` |
| **Resources** | Data objects that can be read, listed, and subscribed to for real-time updates. Resources represent external data sources like files, databases, or APIs. | `ResourceProvider[F]`, `ResourceProviderWithChanges[F]`, `ResourceSubscriptionProvider[F]` |
| **Prompts** | Predefined conversation templates that can be parameterized and used to generate consistent AI responses. Prompts help standardize interactions. | `PromptProvider[F]`, `PromptProviderWithChanges[F]` |
| **Elicitation** | A mechanism for servers to request additional information from users during tool execution. This enables interactive workflows where the AI can ask clarifying questions. | Available via `Client[F].elicit()` |
| **Sampling** | Allows servers to request AI model completions from the client, enabling servers to generate content or make decisions using the client's AI capabilities. | Available via `Client[F].sample()` |
| **Roots** | Entry points that define the starting locations for resource hierarchies. Roots help organize and navigate complex data structures. | `RootChangeAwareProvider[F]` |
| **Logging** | Built-in logging capabilities for servers to send diagnostic and informational messages to clients for debugging and monitoring. | Available via `Client[F].log()` |
| **Completion** | Autocomplete functionality for prompt arguments and resource URIs, helping users and AI assistants discover available options. | Built into `PromptProvider[F]` and `ResourceProvider[F]` |

For a detailed introduction to all MCP concepts, see [modelcontextprotocol.io/introduction](https://modelcontextprotocol.io/introduction).

### Additional Considerations

* **Cancellation**
  * Request cancellation is supported by cancelling the IO for pending request (eg client.sample)
  * When the client requests cancellation the server will automatically cancel the IO for the request handling (eg the tool function)
* **Logging**
  * Prefer logging via the call context as this will automatically add the logger name
  * Logging level is automatically applied, only client.log calls that meet the logging level set by the client will be passed on to your code
* **Progress**
  * Progress can be reported to the client using the call context
  * Progress logs by the client are not passed on to your code
* **Json Schema**
  * Json Schemas are automatically derived using [scala-json-schema](https://github.com/lowmelvin/scala-json-schema). You may use the `@JsonSchemaField` annotation to add additional attributes to the schema, for example `@JsonSchemaField("description", "my nice field".asJson)`.


## Testing Your MCP Server

The [MCP Inspector](https://modelcontextprotocol.io/docs/tools/inspector) is an interactive developer tool for testing and debugging MCP servers.

The Inspector runs directly through `npx` without requiring installation:

```bash
npx @modelcontextprotocol/inspector <command>
```

1. **Build your server:**
   ```bash
   sbt assembly
   ```

2. **Launch the Inspector with your server:**
   ```bash
   npx @modelcontextprotocol/inspector java -jar target/scala-3.7.1/your-server-assembly-0.1.0.jar
   ```

3. **Verify connectivity and capabilities:**
   - Check that the server connects successfully
   - Verify that all expected capabilities are negotiated
   - Review the server information and instructions

## Examples

| Example | Description |
|---------|-------------|
| **[Simple Echo](example/simple-echo/)** | Minimal server with a single echo tool. Best starting point. |
| **[Simple Authenticated](example/simple-authenticated/)** | OAuth/Bearer token authentication over streamable HTTP. |
| **[Demo (Stdio)](example/demo/)** | Comprehensive demo of tools, prompts, resources, elicitation, sampling, and progress reporting. |
| **[Demo (HTTP)](example/demo-http/)** | Same as the stdio demo, but over streamable HTTP. |

All examples can be built and tested using the [MCP Inspector](https://modelcontextprotocol.io/docs/tools/inspector) as described in the [Testing](#testing-your-mcp-server) section.

## Project Modules

This project is organized as a multi-module Scala build:

| Module | Artifact | Description |
|--------|----------|-------------|
| **jsonrpc2** | `ch.linkyard.mcp:jsonrpc2` | Minimal JSON-RPC 2.0 protocol implementation -- message types and basic server logic. Foundation for all communication. |
| **transport/stdio** | `ch.linkyard.mcp:transport-stdio` | Transport layer for JSON-RPC 2.0 over standard input/output (stdio). Depends on `jsonrpc2`. |
| **transport/http4s** | `ch.linkyard.mcp:mcp-server-http4s` | Transport layer for JSON-RPC 2.0 over streamable HTTP using http4s. Includes session management and OAuth support. Depends on `jsonrpc2`. |
| **mcp/protocol** | `ch.linkyard.mcp:mcp-protocol` | MCP message types, codecs, and protocol-specific logic. Depends on `jsonrpc2`. |
| **mcp/server** | `ch.linkyard.mcp:mcp-server` | Core server logic for handling MCP requests and notifications. Provides `McpServer`, `Session`, and all capability traits. Depends on `jsonrpc2` and `mcp/protocol`. |

Each module is defined as an SBT subproject and can be built, tested, and published independently.
