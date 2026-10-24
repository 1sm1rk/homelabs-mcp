package de.homelabs.mcp.it;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

/**
 * The domain side of the contract: a plain bean with an {@code @McpTool} method in the
 * consumer's own package. The starter contributes no tools and never names one.
 */
@Component
public class PingTools {

    @McpTool(description = "Liveness check of the homelabs MCP starter integration test.")
    public String ping() {
        return "pong: homelabs-mcp alive";
    }
}
