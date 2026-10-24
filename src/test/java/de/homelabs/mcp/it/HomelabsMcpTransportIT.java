package de.homelabs.mcp.it;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generic transport contract of the starter, on a consumer that has no database and
 * no domain: the PRM document is public, everything else needs a Bearer token, and an
 * authenticated client reaches the consumer's own {@code @McpTool} bean.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestSecurityConfig.class)
class HomelabsMcpTransportIT {

    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"starter-it","version":"0"}}}
            """;

    @Value("${local.server.port}")
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JwtEncoder jwtEncoder;

    @Test
    void protectedResourceMetadataIsPublicAndDescribesTheConfiguredResource() throws Exception {
        var response = McpHttp.get(McpHttp.protectedResourceMetadataUrl(port));

        assertThat(response.status()).isEqualTo(200);
        JsonNode document = objectMapper.readTree(response.body());
        assertThat(document.get("resource").asString()).isEqualTo(TestSecurityConfig.AUDIENCE);
        assertThat(document.get("authorization_servers").isArray()).isTrue();
        assertThat(document.get("authorization_servers").get(0).asString())
                .isEqualTo("http://localhost:8091/realms/hippocampus");
        assertThat(document.get("scopes_supported").isArray()).isTrue();
        assertThat(document.get("scopes_supported").get(0).asString()).isEqualTo("mcp:tools");
    }

    @Test
    void unauthenticatedMcpCallIsRejectedWithTheResourceMetadataChallenge() throws Exception {
        var response = McpHttp.postMcp(port, INITIALIZE, null, null);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.wwwAuthenticate())
                .contains("Bearer")
                .contains("resource_metadata=")
                .contains("/.well-known/oauth-protected-resource");
    }

    @Test
    void authenticatedClientReachesTheServerAndTheConsumerTools() throws Exception {
        String token = bearerToken(TestSecurityConfig.AUDIENCE);

        var init = McpHttp.postMcp(port, INITIALIZE, null, token);
        assertThat(init.status()).as("initialize: %s", init.body()).isEqualTo(200);
        assertThat(serverName(init.body())).isEqualTo("homelabs-mcp-it");

        var tools = McpHttp.postMcp(port, """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """, init.sessionId(), token);
        assertThat(tools.status()).isEqualTo(200);
        assertThat(tools.body()).contains("\"ping\"");

        var call = McpHttp.postMcp(port, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ping","arguments":{}}}
                """, init.sessionId(), token);
        assertThat(call.status()).isEqualTo(200);
        assertThat(call.body()).contains("pong: homelabs-mcp alive");
    }

    @Test
    void tokenMintedForAnotherAudienceIsRejected() throws Exception {
        var response = McpHttp.postMcp(port, INITIALIZE, null, bearerToken(TestSecurityConfig.FOREIGN_AUDIENCE));

        assertThat(response.status()).isEqualTo(401);
    }

    private String bearerToken(String audience) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("test-issuer")
                .subject("starter-it-user")
                .audience(List.of(audience))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private String serverName(String sseOrJson) {
        for (String line : sseOrJson.split("\n")) {
            if (line.startsWith("data:")) {
                JsonNode root = objectMapper.readTree(line.substring("data:".length()));
                if (root.path("result").has("serverInfo")) {
                    return root.get("result").get("serverInfo").get("name").asString();
                }
            }
        }
        JsonNode root = objectMapper.readTree(sseOrJson);
        return root.get("result").get("serverInfo").get("name").asString();
    }
}
