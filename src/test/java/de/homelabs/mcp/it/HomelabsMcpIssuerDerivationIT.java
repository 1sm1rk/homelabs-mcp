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
 * The AP-7 one-config-value rule realized: when the consumer sets ONLY the issuer
 * (the JWT validation authority), the PRM advertisement derives from it — no second
 * issuer default that can silently diverge (found live 2026-10-26: the hippocampus
 * prod deployment set the issuer env, but the starter's LAN-realms default of
 * authorization-servers kept advertising the retired LAN AS in the PRM document).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "homelabs.mcp.issuer=https://auth.example.org/realms/homelabs"
})
@Import(TestSecurityConfig.class)
class HomelabsMcpIssuerDerivationIT {

    @Value("${local.server.port}")
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void prmAdvertisesTheIssuerWhenAuthorizationServersIsUnset() throws Exception {
        var response = McpHttp.get(McpHttp.protectedResourceMetadataUrl(port));

        assertThat(response.status()).isEqualTo(200);
        JsonNode document = objectMapper.readTree(response.body());
        assertThat(document.get("authorization_servers").get(0).asString())
                .isEqualTo("https://auth.example.org/realms/homelabs");
        // every other default stays the localhost deployment
        assertThat(document.get("resource").asString()).isEqualTo("http://localhost:8090/mcp");
        assertThat(document.get("scopes_supported").get(0).asString()).isEqualTo("mcp:tools");
    }
}