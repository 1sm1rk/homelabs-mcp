package de.homelabs.mcp.security;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

import de.homelabs.mcp.HomelabsMcpProperties;

/**
 * Wires the OAuth 2.1 resource server of the gateway: every request carries a JWT in
 * the {@code Authorization} header, only the Protected Resource Metadata (RFC 9728)
 * discovery document is public.
 *
 * <p>The MCP transport is not built here — it comes from Spring AI's MCP server starter
 * and the {@code spring.ai.mcp.server.*} values in {@code application.yml}. This class
 * adds exactly the authorization layer and nothing domain-specific: tools arrive as
 * adapter modules whose {@code @McpTool} beans the application scans.
 *
 * <p>Beans are declared unconditionally on purpose. The gateway is the top of the
 * stack, not a library: nobody negotiates with these definitions, so there is no
 * conditional activation and no back-off logic. A test that needs a different
 * {@link JwtDecoder} simply contributes a primary one.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HomelabsMcpProperties.class)
public class McpSecurityConfiguration {

    /**
     * JWT validation bound to issuer and audience. The issuer metadata (and with it
     * the JWK set) is resolved lazily on the first token, so the gateway starts even
     * while its authorization server is still coming up.
     */
    @Bean
    JwtDecoder homelabsMcpJwtDecoder(HomelabsMcpProperties properties) {
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(properties.issuer()).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(
                    new JwtIssuerValidator(properties.issuer()),
                    audienceValidator(properties.resource())));
            return decoder;
        });
    }

    /** The audience binding: the token's {@code aud} claim must contain the resource URI. */
    static OAuth2TokenValidator<Jwt> audienceValidator(String resource) {
        return new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                audience -> audience != null && audience.contains(resource));
    }

    /**
     * Stateless JWT API: no session, no CSRF (no cookies). Authenticated for
     * everything except the PRM document, with the 401 challenge pointing the client
     * at that document (RFC 9728 flow the MCP authorization spec builds on).
     */
    @Bean
    SecurityFilterChain homelabsMcpSecurityFilterChain(HttpSecurity http, HomelabsMcpProperties properties)
            throws Exception {
        BearerTokenAuthenticationEntryPoint entryPoint = new BearerTokenAuthenticationEntryPoint();
        entryPoint.setResourceMetadataParameterResolver(request -> properties.resourceMetadataUrl());

        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/.well-known/oauth-protected-resource").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(Customizer.withDefaults())
                        .protectedResourceMetadata(metadata -> metadata
                                .protectedResourceMetadataCustomizer(builder -> builder
                                        .resource(properties.resource())
                                        // One-config-value rule: an explicitly configured
                                        // authorization-servers list wins, otherwise the PRM
                                        // advertises the JWT issuer — two independent issuer
                                        // defaults must never diverge silently.
                                        .authorizationServers(
                                                servers -> servers.addAll(advertisedAuthorizationServers(properties)))
                                        .scopes(scopes -> scopes.addAll(properties.scopesSupported()))))
                        .authenticationEntryPoint(entryPoint));
        return http.build();
    }

    /**
     * The authorization servers the PRM advertises: the explicitly configured list, or
     * — when the property is unset — the single JWT issuer (RFC 8414 / RFC 9728).
     */
    static List<String> advertisedAuthorizationServers(HomelabsMcpProperties properties) {
        return properties.authorizationServers() != null
                ? properties.authorizationServers()
                : List.of(properties.issuer());
    }
}
