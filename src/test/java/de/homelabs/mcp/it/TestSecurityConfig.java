package de.homelabs.mcp.it;

import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Test-only JWT setup: a symmetric-HMAC decoder so the resource server accepts test
 * tokens without a running authorization server, plus an encoder to mint them. Its
 * audience rule mirrors the one the gateway configures (the {@code aud} claim must
 * contain the resource URI).
 *
 * <p>The decoder is {@link Primary}: the gateway defines its own issuer-bound decoder
 * unconditionally (it is an application, not a configurable library), so the test
 * context holds two and the security chain resolves this one.
 */
@TestConfiguration
public class TestSecurityConfig {

    /** Matches the default of {@code homelabs.mcp.resource}. */
    public static final String AUDIENCE = "http://localhost:8090/mcp";

    /** Any other audience: used to prove the audience binding rejects foreign tokens. */
    public static final String FOREIGN_AUDIENCE = "http://localhost:9999/other";

    private static final String SECRET =
            "test-only-symmetric-secret-for-hs256-must-be-at-least-32-bytes-long-ok";

    private static SecretKey secretKey() {
        return new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    @Primary
    JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                jwt -> {
                    List<String> audience = jwt.getAudience();
                    if (audience == null || !audience.contains(AUDIENCE)) {
                        return OAuth2TokenValidatorResult.failure(
                                new OAuth2Error("invalid_token", "missing required audience", null));
                    }
                    return OAuth2TokenValidatorResult.success();
                }));
        return decoder;
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(SECRET.getBytes(StandardCharsets.UTF_8)));
    }
}
