package de.homelabs.mcp.it;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal consumer of the starter: no database, no domain — transport plus the
 * authorization layer, nothing else. Tools come from {@link PingTools} in the same
 * package, discovered by component scanning as they would be in a real application.
 */
@SpringBootApplication
public class HomelabsMcpTestApplication {
}
