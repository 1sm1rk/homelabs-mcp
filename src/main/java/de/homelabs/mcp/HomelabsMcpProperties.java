package de.homelabs.mcp;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.lang.Nullable;

/**
 * Configuration of the MCP gateway: MCP resource identity, RFC 9728 Protected
 * Resource Metadata, and the authorization server the resource server validates
 * tokens against.
 *
 * <p>Every value is driven by config, so swapping the authorization server is a
 * config change, not a code change. The defaults are localhost values: they fit a
 * single-host deployment and must be overridden anywhere else (the shipped
 * {@code application.yml} maps the {@code HOMELABS_MCP_*} environment variables onto
 * this namespace).
 *
 * @param resource              the resource URI (audience): the MCP endpoint URL; a
 *                              token's "aud" claim must contain this value
 * @param resourceMetadataUrl   the PRM document URL returned in the 401
 *                              WWW-Authenticate header (resource_metadata)
 * @param authorizationServers  the authorization server issuer(s) advertised in the
 *                              PRM document (RFC 8414 / OIDC discovery); NULL means
 *                              unset — the PRM then advertises {@link #issuer}, so
 *                              one configured issuer is all a deployment needs.
 *                              Configure explicitly only for multi-AS setups.
 * @param scopesSupported       scopes this resource advertises in the PRM document
 *                              (informational; scopes are not enforced)
 * @param issuer                the issuer URI whose metadata is used to resolve the
 *                              JWK set and whose "iss" claim tokens must carry
 */
@ConfigurationProperties(prefix = "homelabs.mcp")
public record HomelabsMcpProperties(
        @DefaultValue("http://localhost:8090/mcp") String resource,
        @DefaultValue("http://localhost:8090/.well-known/oauth-protected-resource") String resourceMetadataUrl,
        @Nullable List<String> authorizationServers,
        @DefaultValue("mcp:tools") List<String> scopesSupported,
        @DefaultValue("http://localhost:8091/realms/hippocampus") String issuer) {
}
