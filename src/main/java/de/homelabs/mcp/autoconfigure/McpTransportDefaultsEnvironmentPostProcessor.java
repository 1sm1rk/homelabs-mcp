package de.homelabs.mcp.autoconfigure;

import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Supplies the transport defaults the starter stands for: Streamable-HTTP with the
 * synchronous MCP API.
 *
 * <p>Spring AI's Streamable-HTTP server auto-configuration is gated on
 * {@code spring.ai.mcp.server.protocol} being present in the environment — its own
 * default value lives inside the bound properties object and is never seen by that
 * condition. Without this contribution a consumer would get no MCP endpoint at all.
 *
 * <p>The values are added as the lowest-precedence property source, so an application
 * that configures the protocol itself (or deliberately runs another transport) wins.
 */
public class McpTransportDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String PROPERTY_SOURCE_NAME = "homelabsMcpTransportDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(PROPERTY_SOURCE_NAME)) {
            return;
        }
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, Map.of(
                "spring.ai.mcp.server.protocol", "STREAMABLE",
                "spring.ai.mcp.server.type", "SYNC")));
    }
}
