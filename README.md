# homelabs-mcp

Reusable MCP foundation for the homelabs services, packaged as a Spring Boot
starter: `de.homelabs:homelabs-mcp-spring-boot-starter`.

A consumer that adds this starter gets

- an **MCP server** (Streamable-HTTP transport, synchronous API, annotation-based
  tools via Spring AI) on `POST /mcp`,
- an **OAuth 2.1 resource server** — Bearer JWT, issuer- and audience-bound — with
  the **RFC 9728 Protected Resource Metadata** document on
  `/.well-known/oauth-protected-resource` and the matching
  `WWW-Authenticate: Bearer resource_metadata="…"` challenge on unauthenticated
  calls,
- a single property namespace `homelabs.mcp.*` for resource identity and
  authorization server, all with localhost defaults,
- and nothing else: no domain tools, no database, no server name, no
  authorization server of its own.

The starter carries no knowledge of any domain project. Tools live in the
consuming application and are found by convention (Boot component scanning +
Spring AI's annotation scanner), which is what lets one foundation serve
several independently deployable services.

## Requirements

- Java 25
- Spring Boot 4.1.x (parent or BOM in the consumer)
- an OAuth 2.1 authorization server (tested against Keycloak 26.8)

Spring AI 2.0.x comes in transitively through the MCP server starter; the
starter and its first consumer share one Spring AI line on purpose.

## Use it

```xml
<dependency>
    <groupId>de.homelabs</groupId>
    <artifactId>homelabs-mcp-spring-boot-starter</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

Until a release exists, install it from its checkout first: `mvn install`.

Consumer contract — four things, all on the application side:

1. **Add the starter** (above). The MCP transport and the resource server come
   with it; do not re-declare `spring-ai-starter-mcp-server-webmvc` or
   `spring-boot-starter-oauth2-resource-server`.
2. **Name your server**: `spring.ai.mcp.server.name` (plus `version` if you
   want); the starter deliberately defaults nothing here.
3. **Implement your tools**: plain beans with `@McpTool` methods in a package
   your application scans. Nothing to register, nothing to import.
4. **Configure the resource identity**: `homelabs.mcp.*` (below). Every value
   has a localhost default, so a single-host setup can start without any config.

```yaml
spring:
  application:
    name: my-mcp-service
  ai:
    mcp:
      server:
        name: my-service          # consumer's identity, not the starter's
        version: 0.0.1

homelabs:
  mcp:
    resource: ${MY_SERVICE_RESOURCE:http://localhost:8090/mcp}
    resource-metadata-url: ${MY_SERVICE_RESOURCE_METADATA_URL:http://localhost:8090/.well-known/oauth-protected-resource}
    authorization-servers:
      - ${MY_SERVICE_ISSUER_URI:http://localhost:8091/realms/hippocampus}
    scopes-supported:
      - mcp:tools
    issuer: ${MY_SERVICE_ISSUER_URI:http://localhost:8091/realms/hippocampus}

server:
  port: 8090
```

The starter itself contributes `spring.ai.mcp.server.protocol=STREAMABLE` and
`spring.ai.mcp.server.type=SYNC` with the lowest precedence — Spring AI's
Streamable-HTTP auto-configuration only activates when the protocol property is
present in the environment, so without this the consumer would get no endpoint
at all. Anything the application configures wins.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `homelabs.mcp.enabled` | `true` | wire the authorization layer (security chain, PRM, JWT decoder); the MCP transport stays either way |
| `homelabs.mcp.resource` | `http://localhost:8090/mcp` | resource URI = audience: a token's `aud` claim must contain it |
| `homelabs.mcp.resource-metadata-url` | `http://localhost:8090/.well-known/oauth-protected-resource` | PRM document URL advertised in the 401 challenge |
| `homelabs.mcp.authorization-servers` | `[http://localhost:8091/realms/hippocampus]` | issuers advertised in the PRM document |
| `homelabs.mcp.scopes-supported` | `[mcp:tools]` | scopes advertised in the PRM document (informational, not enforced) |
| `homelabs.mcp.issuer` | `http://localhost:8091/realms/hippocampus` | issuer whose metadata resolves the JWK set, and whose `iss` claim tokens must carry |

A consumer keeps its own environment variable names and maps them onto this
namespace, e.g. the first consumer:

| Its env var | Maps to |
|---|---|
| `HIPPOCAMPUS_ISSUER_URI` | `homelabs.mcp.issuer` + `homelabs.mcp.authorization-servers` |
| `HIPPOCAMPUS_RESOURCE` | `homelabs.mcp.resource` |
| `HIPPOCAMPUS_RESOURCE_METADATA_URL` | `homelabs.mcp.resource-metadata-url` |

## What gets wired

- **Security filter chain** — stateless, CSRF off (no cookies), everything
  authenticated except the PRM document; the 401 entry point points at the
  configured metadata URL.
- **JWT decoder** — issuer metadata and JWK set are resolved **lazily on the
  first token**, so the application starts even while its authorization server
  is still coming up; the token must carry `iss` = `homelabs.mcp.issuer` and an
  `aud` containing `homelabs.mcp.resource`.
- **PRM document** — served at `/.well-known/oauth-protected-resource` from the
  namespace values (`resource`, `authorization_servers`, `scopes_supported`).
- **Auto-configuration** —
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`;
  both beans are `@ConditionalOnMissingBean`, so an application that brings its
  own `JwtDecoder` (tests) or `SecurityFilterChain` (different policy) simply
  wins.

`homelabs.mcp.enabled=false` removes the whole authorization layer — for a
project with no authorization server that only wants the transport. The MCP
endpoint then falls back to Boot's default security, so expose it as you see fit.

## Verification

```bash
# 1. metadata document is public
curl -s http://localhost:8090/.well-known/oauth-protected-resource

# 2. unauthenticated MCP call is challenged (healthy state: 401)
curl -si -X POST http://localhost:8090/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"0"}}}' \
  | grep -i 'www-authenticate'

# 3. authenticated MCP call answers
TOKEN=...   # access token from the configured issuer, audience = homelabs.mcp.resource
curl -s -X POST http://localhost:8090/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"0"}}}'
```

`mvn verify` runs 6 unit tests (property defaults and overrides, audience binding)
and 8 integration tests (PRM document, 401 challenge, authenticated
initialize/`tools/list`/tool call, foreign-audience rejection, namespace
overrides, disabled mode) against a plain web context — no database, no
Testcontainers.

## First consumer

`homelabs-hippocampus` (two-tier memory service) IS the first consumer since
2026-10-24 (AP-9 session 2): its transport/auth wiring lives here now, its
tool classes stayed where they are, and its `HIPPOCAMPUS_*` environment
variables keep working through the mapping above.
