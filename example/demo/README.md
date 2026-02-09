# Demo MCP Server (Stdio)

A comprehensive example that demonstrates all major MCP concepts over a stdio transport.

Source: [StdioDemoMcpServer.scala](src/main/scala/ch/linkyard/mcp/example/demo/StdioDemoMcpServer.scala)

## Features

- **Multiple Tools**:
  - `parrot`: Simple text echo with modification
  - `adder`: Mathematical operation with progress reporting and logging
  - `userEmail`: Complex tool using elicitation and sampling to find user emails
- **Prompts**: Story generation prompt with argument completion
- **Resources**: Animal database with 20 animals, resource templates, and autocomplete
- **Advanced Features**: Progress reporting, logging, elicitation, sampling, and completion

## What It Showcases

- How to implement complex workflows using multiple MCP concepts
- Integration between different features (tools calling elicitation and sampling)
- Resource management with pagination and templates
- Error handling and user interaction patterns
- Stdio connection

This example can be built and tested using the [MCP Inspector](https://modelcontextprotocol.io/docs/tools/inspector).
