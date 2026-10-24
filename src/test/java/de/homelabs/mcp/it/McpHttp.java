package de.homelabs.mcp.it;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Bare HTTP client for the integration tests — deliberately no test framework HTTP
 * client, so the assertions see exactly what an MCP client sees on the wire.
 */
final class McpHttp {

    record Response(String body, String sessionId, int status, String wwwAuthenticate) {
    }

    static String mcpUrl(int port) {
        return "http://localhost:%d/mcp".formatted(port);
    }

    static String protectedResourceMetadataUrl(int port) {
        return "http://localhost:%d/.well-known/oauth-protected-resource".formatted(port);
    }

    static Response postMcp(int port, String json, String sessionId, String token) throws IOException {
        return post(mcpUrl(port), json, sessionId, token);
    }

    static Response post(String url, String json, String sessionId, String token) throws IOException {
        HttpURLConnection connection = open(url);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json, text/event-stream");
        if (sessionId != null) {
            connection.setRequestProperty("Mcp-Session-Id", sessionId);
        }
        if (token != null) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        try (var out = connection.getOutputStream()) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return read(connection);
    }

    static Response get(String url) throws IOException {
        HttpURLConnection connection = open(url);
        connection.setRequestMethod("GET");
        return read(connection);
    }

    private static HttpURLConnection open(String url) throws IOException {
        return (HttpURLConnection) URI.create(url).toURL().openConnection();
    }

    private static Response read(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        var stream = (status >= 400) ? connection.getErrorStream() : connection.getInputStream();
        String body = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        return new Response(body, connection.getHeaderField("Mcp-Session-Id"), status,
                connection.getHeaderField("WWW-Authenticate"));
    }

    private McpHttp() {
    }
}
