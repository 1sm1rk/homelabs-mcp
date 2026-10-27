# homelabs-mcp

The MCP gateway of the homelab: **one** MCP server for every homelab service.

Harnesses (OpenCode, Claude Code, Crush, …) connect here once and see the tools of
all services at the same time. The gateway owns the harness-facing half of every
call —

- an **MCP server** (Streamable-HTTP transport, synchronous API) on `POST /mcp`,
- an **OAuth 2.1 resource server** — Bearer JWT, issuer- and audience-bound — with
  the **RFC 9728 Protected Resource Metadata** document on
  `/.well-known/oauth-protected-resource` and the matching
  `WWW-Authenticate: Bearer resource_metadata="…"` challenge on unauthenticated
  calls,

— and nothing domain-specific. Tools arrive as **adapter modules**: a separate
Maven artifact per project, carrying that project's `@McpTool` definitions plus the
client that binds them to the project's API. Each project stays a normal API server;
the gateway composes the tool surface.

```
harness ──MCP──▶ gateway (this service) ──HTTP + forwarded token──▶ project API
                        │
                        └── tool definitions + API binding from adapter artifacts
```

Because the harness token is forwarded unchanged, the project API still sees the
real user: it validates the token itself and derives `owner` from its `sub`. The
gateway never mints identities and never sends the subject as data.

## Requirements

- Java 25, Maven
- an OAuth 2.1 authorization server (tested against Keycloak 26.8)
- the adapter artifact of every composed project (`de.homelabs:<project>-mcp`)

## Build and run

The adapter artifacts are not on Maven Central. Until they are published to GitHub
Packages, a local build needs the project checkout **next to this one** — the parent
`projects/` directory holds both:

```bash
# 1. build and install the adapter(s) into the local Maven repository
cd ../homelabs-hippocampus
mvn install -DskipTests

# 2. build, test and run the gateway
cd ../homelabs-mcp
mvn clean verify
java -jar target/homelabs-mcp-0.0.1-SNAPSHOT.jar      # gateway on :8090
```

Step 1 is the interim prerequisite: it makes `de.homelabs:hippocampus-mcp` resolvable
locally. Once adapters are released to GitHub Packages, `~/.m2/settings.xml` gains a
`github` server entry (token with `read:packages`) and step 1 disappears — the pom
already pins the adapter version.

Startup is config-complete out of the box (localhost defaults); point it at a real
authorization server with the environment variables below.

## Configuration

Everything lives in the `homelabs.mcp.*` namespace, mapped from environment
variables in [`application.yml`](src/main/resources/application.yml):

| Property | Environment variable | Default | Meaning |
|---|---|---|---|
| `homelabs.mcp.resource` | `HOMELABS_MCP_RESOURCE` | `http://localhost:8090/mcp` | resource URI = audience: a token's `aud` claim must contain it |
| `homelabs.mcp.resource-metadata-url` | `HOMELABS_MCP_RESOURCE_METADATA_URL` | `http://localhost:8090/.well-known/oauth-protected-resource` | PRM document URL advertised in the 401 challenge |
| `homelabs.mcp.issuer` | `HOMELABS_MCP_ISSUER_URI` | `http://localhost:8091/realms/hippocampus` | issuer whose metadata resolves the JWK set, and whose `iss` claim tokens must carry |
| `homelabs.mcp.authorization-servers` | — | unset → the PRM advertises the issuer | issuers advertised in the PRM document; set explicitly only for multi-AS setups |
| `homelabs.mcp.scopes-supported` | — | `[mcp:tools]` | scopes advertised in the PRM document (informational, not enforced) |
| `server.port` | `SERVER_PORT` | `8090` | HTTP port |

`HOMELABS_MCP_ISSUER_URI` is the single authorization-server knob: validation and the
PRM advertisement both follow it, so they cannot drift apart. Harness-side: every
scope advertised in `scopes_supported` must exist as a client scope in the
authorization server — clients such as Claude Code request exactly these.

Adapter-side configuration stays with the adapter (e.g. the hippocampus API base URL
via `hippocampus.mcp.api.base-url`).

## Adding a project

1. **The project** exposes a normal API and ships its adapter module, e.g.
   `de.homelabs:hippocampus-mcp` from the hippocampus repo: `@McpTool` beans plus a
   typed client that forwards the incoming `Authorization` header.
2. **The gateway** declares the adapter and bumps the version:

   ```xml
   <dependency>
       <groupId>de.homelabs</groupId>
       <artifactId>hippocampus-mcp</artifactId>
       <version>${hippocampus-mcp.version}</version>
   </dependency>
   ```

3. **Nothing else.** The application scans `de.homelabs`, the adapter's beans are
   ordinary Spring components, and Spring AI's annotation scanner turns them into MCP
   tools. After a redeploy the new tools simply appear in `tools/list` — no realm
   change, no harness change, no second MCP endpoint.

Adapter releases and gateway versions move together: a new adapter release means a
version bump here plus an image rebuild.

## Docker image

The build context is the parent `projects/` directory, the dockerfile path is
`homelabs-mcp/Dockerfile`:

```bash
# sibling mode: the context contains the homelabs-hippocampus checkout, so the
# adapter module is built and installed into the image's Maven cache first
cd projects
docker build -f homelabs-mcp/Dockerfile -t homelabs-mcp .

# packages mode: no sibling checkout — the adapter comes from GitHub Packages
docker build --secret id=github_token,src=token.txt \
  -f homelabs-mcp/Dockerfile -t homelabs-mcp .
```

Both modes are handled by [`docker/build-gateway.sh`](docker/build-gateway.sh); an
unresolvable adapter fails the build with the mode-specific remedy. The image ships
only the packaged jar: `eclipse-temurin:25-jre-noble`, non-root (`ubuntu`), port 8090,
and a healthcheck that probes the public PRM document (explicit `bash`, host and port
joined with a slash — the runtime image's `/bin/sh` is dash and bash's `/dev/tcp`
rejects `host:port`).

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

# 3. authenticated handshake, then the composed tool surface
TOKEN=...   # access token from the configured issuer, audience = homelabs.mcp.resource
INIT=$(curl -si -X POST http://localhost:8090/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl","version":"0"}}}')
SESSION=$(printf '%s' "$INIT" | grep -i '^mcp-session-id:' | tr -d '\r' | cut -d' ' -f2)

curl -s -X POST http://localhost:8090/mcp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Mcp-Session-Id: $SESSION" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
```

`mvn verify` runs the unit tests (property defaults/overrides, audience binding,
authorization-server derivation) and the integration tests: PRM document, 401
challenge, authenticated handshake and tool call, foreign-audience rejection,
namespace overrides, issuer derivation, and the gateway composition test — the real
application booted with the hippocampus adapter wired against a fake project API,
asserting the complete tool surface and that a tool call arrives with the harness
token and the MCP client identity, and that the reply text reaches the client
unchanged. No database, no Testcontainers.

## Commit messages

Commit messages are short and describe the change itself. They deliberately
avoid references to internal planning artifacts (ADRs, work packages): those
documents are not part of this public repository.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
