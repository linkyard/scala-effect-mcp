[![CI](https://github.com/linkyard/scala-effect-mcp/actions/workflows/ci.yaml/badge.svg)](https://github.com/linkyard/scala-effect-mcp/actions/workflows/ci.yaml)
[![Scala Steward badge](https://img.shields.io/badge/Scala_Steward-helping-blue.svg?style=flat&logo=data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAA4AAAAQCAMAAAARSr4IAAAAVFBMVEUAAACHjojlOy5NWlrKzcYRKjGFjIbp293YycuLa3pYY2LSqql4f3pCUFTgSjNodYRmcXUsPD/NTTbjRS+2jomhgnzNc223cGvZS0HaSD0XLjbaSjElhIr+AAAAAXRSTlMAQObYZgAAAHlJREFUCNdNyosOwyAIhWHAQS1Vt7a77/3fcxxdmv0xwmckutAR1nkm4ggbyEcg/wWmlGLDAA3oL50xi6fk5ffZ3E2E3QfZDCcCN2YtbEWZt+Drc6u6rlqv7Uk0LdKqqr5rk2UCRXOk0vmQKGfc94nOJyQjouF9H/wCc9gECEYfONoAAAAASUVORK5CYII=)](https://scala-steward.org)

# scala-effect-mcp

Library to implement model context protocol servers (MCP) in Scala using fs2 and cats effect.

* Current version is 0.3.3
* Supported MCP protocol revision is 2026-07-28 (stateless servers)
* Clients of the revisions 2025-06-18 and 2025-11-25 are served too, this can be turned off with `McpServerConfig(supportLegacyClients = false)`
* Supported Transports: Stdio and Streamable HTTP

---

**[Getting Started](GETTING-STARTED.md)** | **[Key Concepts](#key-concepts)** | **[Architecture](ARCHITECTURE.md)** | **[Migration](MIGRATION.md)** | **[Examples](#examples)** | **[Testing](#testing-your-mcp-server)** | **[Modules](#project-modules)**

---

## Getting Started

See the **[Getting Started guide](GETTING-STARTED.md)** for a step-by-step walkthrough of building your first MCP server.

## Key Concepts

The Model Context Protocol (MCP) defines several core concepts that enable AI assistants to interact with external systems and data sources. An `McpServer` is stateless: one instance serves all clients and requests. Have your server implement the listed traits to expose capabilities. Every method receives a `RequestContext` with the authentication, the client information and the answers to earlier questions.
For a deeper look at the library's types, layers, and internal wiring, see the **[Architecture documentation](ARCHITECTURE.md)**. If you come from version 0.3.x, see the **[Migration guide](MIGRATION.md)**.

| Concept | Description | Required Trait(s) / API |
|---------|-------------|-------------------------|
| **Tools** | Functions that AI assistants can call to perform actions or retrieve information. Tools have defined input/output schemas and can be read-only, additive, or destructive. | `ToolProvider[F]`, `ToolFunction` |
| **Resources** | Data objects that can be read and listed. Resources represent external data sources like files, databases, or APIs. | `ResourceProvider[F]` |
| **Prompts** | Predefined conversation templates that can be parameterized and used to generate consistent AI responses. Prompts help standardize interactions. | `PromptProvider[F]` |
| **Elicitation** | A mechanism for servers to ask the user for additional information while a request runs. The server answers with `Outcome.InputRequired`, the client asks the user and retries the request with the answers. | `Outcome.InputRequired` / `Outcome.elicit(...)`, or the `Ask` helper of `ToolFunction.interactiveText` and `ToolFunction.interactiveStructured` |
| **Completion** | Autocomplete functionality for prompt arguments and resource template arguments, helping users and AI assistants discover available options. | `PromptFunction.argumentCompletions`, `ResourceTemplate.completions` |
| **Subscriptions / change notifications** | Clients open a `subscriptions/listen` stream to learn about changed tool, prompt and resource lists and about updated resources. | `ToolProviderWithChanges[F]`, `PromptProviderWithChanges[F]`, `ResourceProviderWithChanges[F]`, `ResourceSubscriptionProvider[F]` |
| **Caching hints** | Tell clients for how long they may keep a list or a resource and who may cache it. | `CacheHint` (`toolsCache`, `promptsCache`, `resourcesCache`) |
| **Authentication** | The bearer token of the request is available to every handler, the HTTP transport can validate it with `OAuthMiddleware`. | `RequestContext.authentication` |

For a detailed introduction to all MCP concepts, see [modelcontextprotocol.io/introduction](https://modelcontextprotocol.io/introduction).

### Not supported

* Sampling, roots and logging: they are deprecated in 2026-07-28 and are not part of the API (a legacy client that sends `logging/setLevel` gets an empty answer, the level is ignored)
* Experimental tasks and the tasks extension
* The HTTP+SSE transport of 2024-11-05
* Resumable streams (event ids and `Last-Event-ID`)

### Additional Considerations

* **Cancellation**
  * When the client cancels a request, the server stops the handler (the tool function for example). Over HTTP the client cancels by closing the stream, over stdio by sending `notifications/cancelled`.
  * Cancelling a subscription (`subscriptions/listen`) ends the stream.
* **Progress**
  * Progress can be reported to the client with `context.reportProgress(progress, total, message)`. It is only sent when the client asked for progress with a progress token.
  * Progress notifications of the client are not passed on to your code.
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
   npx @modelcontextprotocol/inspector java -jar target/scala-3.10.0/your-server-assembly-0.1.0.jar
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
| **[Demo (Stdio)](example/demo/)** | Comprehensive demo of tools, prompts, resources, elicitation, completion, and progress reporting. |
| **[Demo (HTTP)](example/demo-http/)** | Same as the stdio demo, but over streamable HTTP. |

All examples can be built and tested using the [MCP Inspector](https://modelcontextprotocol.io/docs/tools/inspector) as described in the [Testing](#testing-your-mcp-server) section.

## Project Modules

This project is organized as a multi-module Scala build:

| Module | Artifact | Description |
|--------|----------|-------------|
| **jsonrpc2** | `ch.linkyard.mcp:jsonrpc2` | Minimal JSON-RPC 2.0 protocol implementation: message types, handler interfaces and the connection loop. Foundation for all communication. |
| **transport/stdio** | `ch.linkyard.mcp:jsonrpc2-stdio` | Transport layer for JSON-RPC 2.0 over standard input/output (stdio). Depends on `jsonrpc2`. |
| **transport/http4s** | `ch.linkyard.mcp:mcp-server-http4s` | Transport layer for JSON-RPC 2.0 over streamable HTTP using http4s. Serves stateless requests, opens sessions only for legacy clients, and includes OAuth support. Depends on `jsonrpc2`. |
| **mcp/protocol** | `ch.linkyard.mcp:mcp-protocol` | MCP message types (2026-07-28 and the `legacy` package for earlier revisions), codecs, and protocol-specific logic. Depends on `jsonrpc2`. |
| **mcp/server** | `ch.linkyard.mcp:mcp-server` | Core server logic for handling MCP requests and notifications. Provides `McpServer`, `RequestContext`, `Outcome`, `ToolFunction`, and all provider traits. Depends on `jsonrpc2` and `mcp/protocol`. |

Each module is defined as an SBT subproject and can be built, tested, and published independently.
