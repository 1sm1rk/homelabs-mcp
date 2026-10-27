package de.homelabs.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The homelabs MCP gateway: ONE MCP server for the whole homelab, composing its tool
 * surface from adapter modules.
 *
 * <p>The gateway owns the harness-facing half of every tool call — OAuth 2.1 resource
 * server (RFC 9728 Protected Resource Metadata), Streamable-HTTP MCP transport — and
 * nothing domain-specific. Tools and their API bindings arrive as adapter artifacts
 * (for example {@code de.homelabs:hippocampus-mcp}); their {@code @McpTool} beans are
 * ordinary Spring components, so the scan below is the whole integration: a new
 * service joins by adding its adapter to the pom.
 */
@SpringBootApplication(scanBasePackages = "de.homelabs")
public class HomelabsMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(HomelabsMcpApplication.class, args);
    }
}
