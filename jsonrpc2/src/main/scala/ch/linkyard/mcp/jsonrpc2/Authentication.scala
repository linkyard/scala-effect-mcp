package ch.linkyard.mcp.jsonrpc2

enum Authentication:
  case Anonymous
  case BearerToken(token: String)
