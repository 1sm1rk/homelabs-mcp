package de.homelabs.mcp.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PRM document and the 401 challenge are driven by the {@code homelabs.mcp.*}
 * namespace alone — no value is hardcoded, which is what lets a second deployment (or a
 * second project) reuse the starter.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "homelabs.mcp.resource=https://memory.example.org/mcp",
        "homelabs.mcp.resource-metadata-url=https://memory.example.org/.well-known/oauth-protected-resource",
        "homelabs.mcp.authorization-servers[0]=https://auth.example.org/realms/homelabs",
        "homelabs.mcp.scopes-supported[0]=mcp:tools",
        "homelabs.mcp.scopes-supported[1]=mcp:memory",
        "homelabs.mcp.issuer=https://auth.example.org/realms/homelabs"
})
@Import(TestSecurityConfig.class)
class HomelabsMcpOverrideIT {

    @Value("${local.server.port}")
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void metadataDocumentFollowsTheNamespace() throws Exception {
        var response = McpHttp.get(McpHttp.protectedResourceMetadataUrl(port));

        assertThat(response.status()).isEqualTo(200);
        JsonNode document = objectMapper.readTree(response.body());
        assertThat(document.get("resource").asString()).isEqualTo("https://memory.example.org/mcp");
        assertThat(document.get("authorization_servers").get(0).asString())
                .isEqualTo("https://auth.example.org/realms/homelabs");
        assertThat(document.get("scopes_supported")).hasSize(2);
        assertThat(document.get("scopes_supported").get(1).asString()).isEqualTo("mcp:memory");
    }

    @Test
    void challengePointsAtTheConfiguredMetadataUrl() throws Exception {
        var response = McpHttp.postMcp(port, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"starter-it","version":"0"}}}
                """, null, null);

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.wwwAuthenticate())
                .contains("resource_metadata=\"https://memory.example.org/.well-known/oauth-protected-resource\"");
    }
}
