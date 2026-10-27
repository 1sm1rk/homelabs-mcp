package de.homelabs.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * The gateway ships localhost defaults that match the single-host deployment it grew
 * out of, so a deployment only overrides what differs.
 */
class HomelabsMcpPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class);

    @Test
    void defaultsAreTheLocalhostDeployment() {
        runner.run(context -> {
            HomelabsMcpProperties properties = context.getBean(HomelabsMcpProperties.class);

            assertThat(properties.resource()).isEqualTo("http://localhost:8090/mcp");
            assertThat(properties.resourceMetadataUrl())
                    .isEqualTo("http://localhost:8090/.well-known/oauth-protected-resource");
            // unset by default — the PRM derives the advertised authorization servers
            // from the issuer (one-config-value rule)
            assertThat(properties.authorizationServers()).isNull();
            assertThat(properties.scopesSupported()).containsExactly("mcp:tools");
            assertThat(properties.issuer()).isEqualTo("http://localhost:8091/realms/hippocampus");
        });
    }

    @Test
    void everyValueCanBeOverriddenInTheNamespace() {
        runner
                .withPropertyValues(
                        "homelabs.mcp.resource=https://memory.example.org/mcp",
                        "homelabs.mcp.resource-metadata-url=https://memory.example.org/.well-known/oauth-protected-resource",
                        "homelabs.mcp.authorization-servers[0]=https://auth.example.org/realms/homelabs",
                        "homelabs.mcp.scopes-supported[0]=mcp:tools",
                        "homelabs.mcp.scopes-supported[1]=mcp:memory",
                        "homelabs.mcp.issuer=https://auth.example.org/realms/homelabs")
                .run(context -> {
                    HomelabsMcpProperties properties = context.getBean(HomelabsMcpProperties.class);

                    assertThat(properties.resource()).isEqualTo("https://memory.example.org/mcp");
                    assertThat(properties.resourceMetadataUrl())
                            .isEqualTo("https://memory.example.org/.well-known/oauth-protected-resource");
                    assertThat(properties.authorizationServers())
                            .containsExactly("https://auth.example.org/realms/homelabs");
                    assertThat(properties.scopesSupported()).containsExactly("mcp:tools", "mcp:memory");
                    assertThat(properties.issuer()).isEqualTo("https://auth.example.org/realms/homelabs");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(HomelabsMcpProperties.class)
    static class PropertiesConfig {
    }
}
