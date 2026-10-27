package de.homelabs.mcp.it;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gateway's reason to exist: it runs the adapter module as its tool surface and
 * passes the harness token through to the project API.
 *
 * <p>Booted for real (full context, real web layer, the adapter from the Maven
 * dependency) against a fake project API — the assertions therefore cover the whole
 * chain: MCP client → gateway transport → OAuth 2.1 resource server → adapter tool →
 * forwarded HTTP call → reply text back to the client.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestSecurityConfig.class)
class HomelabsMcpGatewayCompositionIT {

    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"gateway-it","version":"0"}}}
            """;

    /** The complete tool surface the hippocampus adapter contributes. */
    private static final Set<String> EXPECTED_TOOLS = Set.of(
            "store", "find", "list", "update",
            "promote", "import_file",
            "ltm_find", "ltm_get", "ltm_list",
            "ping");

    private static final FakeProjectApi API = new FakeProjectApi();

    @DynamicPropertySource
    static void adapterBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("hippocampus.mcp.api.base-url", API::baseUrl);
    }

    @AfterAll
    static void stopFakeApi() {
        API.close();
    }

    @BeforeEach
    void resetFakeApi() {
        API.reset();
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JwtEncoder jwtEncoder;

    @Test
    void toolsListIsTheAdaptersCompleteSurface() throws Exception {
        String token = McpTestSupport.bearer(jwtEncoder, TestSecurityConfig.AUDIENCE);
        String sessionId = initialize(token);

        var tools = McpHttp.postMcp(port, """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """, sessionId, token);

        assertThat(tools.status()).isEqualTo(200);
        JsonNode listed = McpTestSupport.result(tools.body(), objectMapper).get("tools");
        List<String> names = new ArrayList<>();
        for (JsonNode tool : listed) {
            names.add(tool.get("name").asString());
        }

        assertThat(names).hasSize(EXPECTED_TOOLS.size());
        assertThat(names).containsExactlyInAnyOrderElementsOf(EXPECTED_TOOLS);
    }

    @Test
    void storeRoundTripForwardsTheHarnessTokenAndTheClientIdentity() throws Exception {
        API.on("POST", "/api/stm/events", 200, """
                {"id":42,"warnings":["unregistered meta keys (curation candidates): brandnew"]}""");
        String token = McpTestSupport.bearer(jwtEncoder, TestSecurityConfig.AUDIENCE);
        String sessionId = initialize(token);

        var call = McpHttp.postMcp(port, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"store","arguments":{"actor":"user","kind":"fact","content":"a fact from the gateway it","meta":{"brandnew":"v"}}}}
                """, sessionId, token);

        assertThat(call.status()).isEqualTo(200);
        JsonNode result = McpTestSupport.result(call.body(), objectMapper);
        // the agent-facing reply text is the adapter's, unchanged
        assertThat(result.get("content").get(0).get("text").asString())
                .isEqualTo("stored event #42\nunregistered meta keys (curation candidates): brandnew");

        FakeProjectApi.Call forwarded = API.onlyCall();
        assertThat(forwarded.method()).isEqualTo("POST");
        assertThat(forwarded.path()).isEqualTo("/api/stm/events");
        assertThat(forwarded.authorization()).isEqualTo("Bearer " + token);
        assertThat(forwarded.source()).isEqualTo("gateway-it");
        assertThat(forwarded.body()).contains("a fact from the gateway it").doesNotContain("source");
    }

    private String initialize(String token) throws Exception {
        var init = McpHttp.postMcp(port, INITIALIZE, null, token);
        assertThat(init.status()).as("initialize: %s", init.body()).isEqualTo(200);
        return init.sessionId();
    }
}
