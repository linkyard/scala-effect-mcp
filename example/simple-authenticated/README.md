# Simple Authenticated Server

A demonstration of authentication and authorization in MCP servers using Bearer tokens and the flow described in <https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization>.

Source: [SimpleAuthenticatedServer.scala](src/main/scala/ch/linkyard/mcp/example/simpleAuthenticated/SimpleAuthenticatedServer.scala)

## How It Works

- **Set up the Authentication**: Uses OAuthAuthorizationServer to guard the `/mcp` path and provide the `.well-known/oauth-protected-resource`. Pass it a token validator function to e.g. check the signature of the provided JWP.
- **Proxy Authorization Server** (optional): Since not all OIDC IdP provide the necessary `.well-known/oauth-authorization-server` endpoint this route provides a proxy. The result will be the `.well-known/openid-configuration` of the IdP. Clients that follow the 2026-07-28 specification reject metadata whose `issuer` differs from the host that serves it, so prefer pointing `OAuthMiddleware.authorizationServers` directly to the IdP when it provides the metadata.
- **Static Client Registration** (optional): If both client ID and client secret are provided as command-line arguments, the server will expose a static client registration endpoint at `.well-known/oauth-authorization-server/register` and patch the authorization server metadata to include the `registration_endpoint` field.
- **Access the Token**: The authentication of the request is available as `context.authentication` (`Anonymous` or `BearerToken(token)`) in every tool function and provider method. The server is stateless, so the value always belongs to the current request.

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
