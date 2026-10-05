package io.genesis.sandbox;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkspacePreparationTest {
    static String unknown() {
        return "{\"operation_id\":\"prepare:1\",\"request_digest\":\"" + "a".repeat(64)
            + "\",\"status\":\"unknown\",\"session_id\":\"s\",\"workspace_id\":\"ws\",\"sandbox_id\":\"\","
            + "\"profile_revision\":\"rev\",\"tenant_id\":\"tenant\",\"principal_id\":\"principal\",\"user_id\":\"user\"}";
    }
    @Test void wireIsSingleSendAndLookup404DoesNotPrepare() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        server.createContext("/v1/sessions/s/workspace-view", exchange -> {
            calls.incrementAndGet();
            if ("GET".equals(exchange.getRequestMethod())) {
                assertTrue(exchange.getRequestURI().getPath().endsWith("/preparations/prepare:1"));
                assertEquals("request_digest=" + "a".repeat(64), exchange.getRequestURI().getRawQuery());
                exchange.sendResponseHeaders(404, -1); exchange.close(); return;
            }
            if (calls.get() >= 3) { exchange.sendResponseHeaders(503, -1); exchange.close(); return; }
            assertEquals("action=prepare", exchange.getRequestURI().getRawQuery());
            assertEquals("{\"operation_id\":\"prepare:1\",\"request_digest\":\"" + "a".repeat(64) + "\"}", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = unknown().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var client = new Client("http://127.0.0.1:" + server.getAddress().getPort(), null, Duration.ofSeconds(5), 4, Duration.ZERO);
            var request = new WorkspacePreparation.Request("prepare:1", "a".repeat(64));
            assertEquals("unknown", client.prepareWorkspaceView("s", request).status);
            assertNull(client.lookupWorkspacePreparation("s", request));
            assertEquals(2, calls.get());
            assertThrows(java.io.IOException.class, () -> client.prepareWorkspaceView("s", request));
            assertEquals(3, calls.get());
            assertThrows(IllegalArgumentException.class, () -> client.prepareWorkspaceView("s", new WorkspacePreparation.Request("../bad", "a".repeat(64))));
            assertEquals(3, calls.get());
        } finally { server.stop(0); }
    }
    @Test void invalidProofAndDuplicateOrUnknownFieldsAreRejected() {
        var request = new WorkspacePreparation.Request("prepare:1", "a".repeat(64));
        for (String body : new String[]{unknown().replace("\"unknown\"", "\"prepared\""),
                unknown().replace("\"session_id\":\"s\"", "\"session_id\":\"foreign\""),
                unknown().replace("\"status\":\"unknown\"", "\"status\":\"unknown\",\"status\":\"unknown\""),
                unknown().replace("\"user_id\":\"user\"", "\"user_id\":\"user\",\"host_path\":\"/secret\""),
                unknown().replace("\"sandbox_id\":\"\",", ""),
                unknown().replace("\"status\":\"unknown\"", "\"status\":\"unknown\",\"facts\":{}"),
                unknown().replace("\"status\":\"unknown\"", "\"status\":\"unknown\",\"facts\":null"),
                unknown().replace("\"user_id\":\"user\"", "\"user_id\":\"\"")})
            assertThrows(java.io.IOException.class, () -> WorkspacePreparation.decode(body.getBytes(StandardCharsets.UTF_8), "s", request));
    }
    @Test void preparedProofRetainsCompleteOriginalFacts() throws Exception {
        String body = unknown().replace("\"status\":\"unknown\"", "\"status\":\"prepared\"")
            .replace("\"sandbox_id\":\"\"", "\"sandbox_id\":\"container-original\"");
        body = body.substring(0, body.length()-1) + ",\"facts\":{\"session_id\":\"s\",\"workspace_id\":\"ws\",\"profile_revision\":\"rev\","
            + "\"os\":\"linux\",\"arch\":\"amd64\",\"image_digest\":\"sha256:fixed\",\"mechanisms\":[],\"resource_limits\":{\"memory_bytes\":1},"
            + "\"network_mode\":\"none\",\"view_state\":\"prepared\",\"runtime_versions\":{},\"runtime_executables\":{}}}";
        var request = new WorkspacePreparation.Request("prepare:1", "a".repeat(64));
        assertEquals("prepared", WorkspacePreparation.decode(body.getBytes(StandardCharsets.UTF_8), "s", request).status);
        for (String field : new String[]{"\"mechanisms\":[],", "\"network_mode\":\"none\","}) {
            String broken = body.replace(field, "");
            assertThrows(java.io.IOException.class, () -> WorkspacePreparation.decode(broken.getBytes(StandardCharsets.UTF_8), "s", request));
        }
    }
}
