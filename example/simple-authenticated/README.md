# Simple Authenticated Server

A demonstration of authentication and authorization in MCP servers using Bearer tokens and the flow described in <https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization>.

Source: [SimpleAuthenticatedServer.scala](src/main/scala/ch/linkyard/mcp/example/simpleAuthenticated/SimpleAuthenticatedServer.scala)

## How It Works

- **Set up the Authentication**: Uses OAuthAuthorizationServer to guard the `/mcp` path and provide the `.well-known/oauth-protected-resource`. Pass it a token validator function to e.g. check the signature of the provided JWP.
- **Proxy Authorization Server** (optional): Since not all OIDC IdP provide the necessary `.well-known/oauth-authorization-server` endpoint this route provides a proxy. The result will be the `.well-known/openid-configuration` of the IdP.
- **Static Client Registration** (optional): If both client ID and client secret are provided as command-line arguments, the server will expose a static client registration endpoint at `.well-known/oauth-authorization-server/register` and patch the authorization server metadata to include the `registration_endpoint` field.
- **Access the Token**: In the open session the authentication token is available as `connectionInfo.authentication`, the connectionInfo is passed in when a session is started (McpServer.initialize). This is refreshed on every request, so it will be kept current (provided the client makes a request from time to time, eg a Ping).

## Usage

```bash
# Without static client registration
java -jar simple-authenticated-assembly.jar <idp-uri>

# With static client registration
java -jar simple-authenticated-assembly.jar <idp-uri> <client-id> <client-secret>
```

### Command-line Arguments

- `<idp-uri>` (required): The OIDC Identity Provider URI (e.g., `https://id.acme.local/realm/example`)
- `<client-id>` (optional): OAuth client ID for static client registration. If provided, `<client-secret>` must also be provided.
- `<client-secret>` (optional): OAuth client secret for static client registration. If provided, `<client-id>` must also be provided.

This example is useful for understanding how to build secure MCP servers that require user authentication and implement proper authorization controls and have access to the token.
