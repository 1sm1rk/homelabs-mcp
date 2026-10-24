package de.homelabs.mcp.autoconfigure;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The audience binding is the one piece of security logic this starter owns; the
 * issuer validation and the JWK resolution are Spring Security's (and need a live
 * authorization server, which is why the full decoder is only exercised live).
 */
class HomelabsMcpAutoConfigurationTest {

    private static final String RESOURCE = "http://localhost:8090/mcp";

    private final OAuth2TokenValidator<Jwt> validator = HomelabsMcpAutoConfiguration.audienceValidator(RESOURCE);

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
