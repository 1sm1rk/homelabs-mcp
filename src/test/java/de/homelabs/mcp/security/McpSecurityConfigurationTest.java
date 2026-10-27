package de.homelabs.mcp.security;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

import de.homelabs.mcp.HomelabsMcpProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two pieces of security logic the gateway owns: the audience binding and the
 * derivation of the advertised authorization servers. The issuer validation and the
 * JWK resolution are Spring Security's (and need a live authorization server, which is
 * why the full decoder is only exercised live).
 */
class McpSecurityConfigurationTest {

    private static final String RESOURCE = "http://localhost:8090/mcp";
    private static final String ISSUER = "https://auth.example.org/realms/homelabs";

    private final OAuth2TokenValidator<Jwt> validator = McpSecurityConfiguration.audienceValidator(RESOURCE);

    @Test
    void acceptsTokenMintedForTheResource() {
        assertThat(validator.validate(token(List.of(RESOURCE))).hasErrors()).isFalse();
    }

    @Test
    void rejectsTokenMintedForAnotherAudience() {
        assertThat(validator.validate(token(List.of("http://localhost:9999/other"))).hasErrors()).isTrue();
    }

    @Test
    void rejectsTokenWithSeveralAudiencesThatExcludeTheResource() {
        assertThat(validator.validate(token(List.of("http://localhost:9999/other", "http://localhost:9998/x")))
                .hasErrors()).isTrue();
    }

    @Test
    void rejectsTokenWithoutAudience() {
        assertThat(validator.validate(Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("sub", "someone")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build()).hasErrors()).isTrue();
    }

    @Test
    void advertisedAuthorizationServersDeriveFromTheIssuerWhenUnset() {
        HomelabsMcpProperties properties = new HomelabsMcpProperties(RESOURCE, RESOURCE + "-metadata", null,
                List.of("mcp:tools"), ISSUER);

        assertThat(McpSecurityConfiguration.advertisedAuthorizationServers(properties)).containsExactly(ISSUER);
    }

    @Test
    void anExplicitAuthorizationServerListWins() {
        List<String> configured = List.of("https://auth1.example.org/realms/x", "https://auth2.example.org/realms/y");
        HomelabsMcpProperties properties = new HomelabsMcpProperties(RESOURCE, RESOURCE + "-metadata", configured,
                List.of("mcp:tools"), ISSUER);

        assertThat(McpSecurityConfiguration.advertisedAuthorizationServers(properties)).containsExactlyElementsOf(configured);
    }

    private Jwt token(List<String> audience) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("sub", "someone")
                .audience(audience)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }
}
