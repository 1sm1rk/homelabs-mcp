package de.homelabs.mcp.it;

import java.time.Instant;
import java.util.List;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Test helpers around the MCP wire format: minting bearer tokens and reading a
 * JSON-RPC result out of an MCP response body, whether it arrived SSE-framed or as
 * plain JSON.
 */
final class McpTestSupport {

    static String bearer(JwtEncoder encoder, String audience) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("test-issuer")
                .subject("gateway-it-user")
                .audience(List.of(audience))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    static JsonNode result(String body, ObjectMapper mapper) {
        for (String line : body.split("\n")) {
            if (line.startsWith("data:")) {
                return mapper.readTree(line.substring("data:".length())).get("result");
            }
        }
        return mapper.readTree(body).get("result");
    }

    private McpTestSupport() {
    }
}
