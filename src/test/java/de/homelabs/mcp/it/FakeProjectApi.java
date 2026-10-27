package de.homelabs.mcp.it;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Minimal stand-in for a project API behind an adapter: a JDK {@link HttpServer} that
 * records what the adapter sent (method, path, query, the two headers that matter,
 * body) and answers the stubbed status/body.
 *
 * <p>Deliberately not WireMock and not an embedded Spring context — the contract under
 * test is the HTTP call the adapter makes, and the JDK server observes it without a new
 * dependency.
 */
final class FakeProjectApi implements AutoCloseable {

    /** One recorded request, reduced to the parts the adapter is responsible for. */
    record Call(String method, String path, String query, String authorization, String source, String body) {
    }

    private record Stub(int status, String json) {
    }

    private final HttpServer server;
    private final Map<String, Stub> stubs = new ConcurrentHashMap<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();

    FakeProjectApi() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Answers the next call to {@code method path} with the given status and JSON body. */
    void on(String method, String path, int status, String json) {
        stubs.put(method + " " + path, new Stub(status, json));
    }

    List<Call> calls() {
        return List.copyOf(calls);
    }

    Call onlyCall() {
        if (calls.size() != 1) {
            throw new AssertionError("expected exactly one API call, got " + calls.size());
        }
        return calls.getFirst();
    }

    /** Clears recorded calls and stubs (one shared fake per test class). */
    void reset() {
        calls.clear();
        stubs.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        calls.add(new Call(method, exchange.getRequestURI().getPath(), exchange.getRequestURI().getQuery(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("X-Mcp-Source"),
                body));

        Stub stub = stubs.getOrDefault(method + " " + exchange.getRequestURI().getPath(), new Stub(200, "{}"));
        byte[] payload = stub.json().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(stub.status(), payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
