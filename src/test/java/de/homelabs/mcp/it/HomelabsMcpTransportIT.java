package de.homelabs.mcp.it;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transport and authorization contract of the gateway: the PRM document is public,
 * everything else needs a Bearer token minted for the gateway's resource URI, and an
 * authenticated client reaches the composed tool surface.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestSecurityConfig.class)
class HomelabsMcpTransportIT {

    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"transport-it","version":"0"}}}
            """;

    private static final FakeProjectApi API = new FakeProjectApi();

    @DynamicPropertySource
    static void adapterBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("hippocampus.mcp.api.base-url", API::baseUrl);
    }

    @AfterAll
    static void stopFakeApi() {
        API.close();
    }

    @BeforeEach
    void resetFakeApi() {
        API.reset();
    }

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
    void authenticatedClientReachesTheServerAndTheComposedTools() throws Exception {
        API.on("GET", "/api/ping", 200, """
                {"reply":"pong: hippocampus alive"}""");
        String token = McpTestSupport.bearer(jwtEncoder, TestSecurityConfig.AUDIENCE);

        var init = McpHttp.postMcp(port, INITIALIZE, null, token);
        assertThat(init.status()).as("initialize: %s", init.body()).isEqualTo(200);
        assertThat(McpTestSupport.result(init.body(), objectMapper).get("serverInfo").get("name").asString())
                .isEqualTo("homelabs-mcp");

        var tools = McpHttp.postMcp(port, """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """, init.sessionId(), token);
        assertThat(tools.status()).isEqualTo(200);
        assertThat(tools.body()).contains("\"ping\"");

        var call = McpHttp.postMcp(port, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ping","arguments":{}}}
                """, init.sessionId(), token);
        assertThat(call.status()).isEqualTo(200);
        assertThat(call.body()).contains("pong: hippocampus alive");
    }

    @Test
    void tokenMintedForAnotherAudienceIsRejected() throws Exception {
        var response = McpHttp.postMcp(port, INITIALIZE, null,
                McpTestSupport.bearer(jwtEncoder, TestSecurityConfig.FOREIGN_AUDIENCE));

        assertThat(response.status()).isEqualTo(401);
    }
}
