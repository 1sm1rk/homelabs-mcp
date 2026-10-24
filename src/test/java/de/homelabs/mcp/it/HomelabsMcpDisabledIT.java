package de.homelabs.mcp.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import de.homelabs.mcp.HomelabsMcpProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code homelabs.mcp.enabled=false} switches the authorization layer off for good —
 * a project without an authorization server keeps the transport but gains no PRM
 * document and no JWT decoding.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "homelabs.mcp.enabled=false")
class HomelabsMcpDisabledIT {

    @Value("${local.server.port}")
    int port;

    @Autowired
    ApplicationContext context;

    @Test
    void starterBeansAreAbsent() {
        assertThat(context.getBeanNamesForType(HomelabsMcpProperties.class)).isEmpty();
        assertThat(context.getBeanNamesForType(JwtDecoder.class)).isEmpty();
    }

    @Test
    void noProtectedResourceMetadataIsServed() throws Exception {
        var response = McpHttp.get(McpHttp.protectedResourceMetadataUrl(port));

        assertThat(response.status()).isNotEqualTo(200);
        assertThat(response.body()).doesNotContain("authorization_servers");
    }
}
