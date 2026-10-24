package de.homelabs.mcp.autoconfigure;

import java.util.List;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.context.annotation.Bean;
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
 * Wires the OAuth 2.1 resource server of an MCP service: every request carries a
 * JWT in the {@code Authorization} header, only the Protected Resource Metadata
 * (RFC 9728) discovery document is public.
 *
 * <p>The MCP transport itself is not built here — it comes from Spring AI's MCP
 * server starter on the classpath, with the Streamable-HTTP/synchronous defaults
 * contributed by {@link McpTransportDefaultsEnvironmentPostProcessor}. This class
 * adds exactly the authorization layer and nothing domain-specific, so consuming
 * applications only declare their tools ({@code @McpTool} beans in their own
 * packages) and their server name.
 *
 * <p>Ordered before the Boot auto-configurations that would otherwise claim the
 * {@link JwtDecoder} or the default security chain, so the beans below win and
 * Boot's defaults back off; any consumer bean of the same type wins over them
 * ({@code @ConditionalOnMissingBean}).
 */
@AutoConfiguration(before = { OAuth2ResourceServerAutoConfiguration.class,
        ServletWebSecurityAutoConfiguration.class })
@ConditionalOnProperty(name = "homelabs.mcp.enabled", matchIfMissing = true)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(HomelabsMcpProperties.class)
public class HomelabsMcpAutoConfiguration {

    /**
     * JWT validation bound to issuer and audience. The issuer metadata (and with it
     * the JWK set) is resolved lazily on the first token, so the application starts
     * even while its authorization server is still coming up.
     */
    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
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
    @ConditionalOnMissingBean(SecurityFilterChain.class)
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
                                        .authorizationServers(
                                                servers -> servers.addAll(properties.authorizationServers()))
                                        .scopes(scopes -> scopes.addAll(properties.scopesSupported()))))
                        .authenticationEntryPoint(entryPoint));
        return http.build();
    }
}
