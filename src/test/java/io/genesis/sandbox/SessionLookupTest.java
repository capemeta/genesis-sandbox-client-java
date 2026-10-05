package io.genesis.sandbox;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 原会话查询只读，404与结果未知不能伪装为未执行并自动重建。 */
class SessionLookupTest {
    @Test void lookupOriginalIdentityAndFailuresNeverCreateSession() throws Exception {
        AtomicInteger creates = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/sessions", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) creates.incrementAndGet();
            exchange.sendResponseHeaders(500, -1); exchange.close();
        });
        server.createContext("/v1/sessions:lookup", exchange -> {
            reads.incrementAndGet();
            assertEquals("GET", exchange.getRequestMethod());
            String query = exchange.getRequestURI().getRawQuery();
            if (query.endsWith("missing")) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
            if (query.endsWith("unknown")) { exchange.sendResponseHeaders(503, -1); exchange.close(); return; }
            String key = query.endsWith("mismatch") ? "other" : "key/space";
            String body = "{\"session_id\":\"s\",\"workspace_id\":\"w\",\"tenant_id\":\"t\","
                + "\"runtime_profile\":\"p\",\"state_policy\":\"session\",\"status\":\"suspended\","
                + "\"idempotency_key\":\"" + key + "\",\"created_at\":\"2026-10-01T00:00:00Z\","
                + "\"expires_at\":\"2026-10-02T00:00:00Z\",\"resource_version\":1}";
            if (query.endsWith("extra")) body = body.substring(0, body.length() - 1) + ",\"host_path\":\"/host\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            Client client = new Client("http://127.0.0.1:" + server.getAddress().getPort(), null, Duration.ofSeconds(5), 2, Duration.ZERO);
            assertEquals("s", client.lookupSession("key/space").sessionId);
            assertNull(client.lookupSession("missing"));
            assertThrows(IOException.class, () -> client.lookupSession("unknown"));
            assertThrows(IOException.class, () -> client.lookupSession("mismatch"));
            assertThrows(IOException.class, () -> client.lookupSession("extra"));
            assertThrows(IllegalArgumentException.class, () -> client.lookupSession(" "));
            assertEquals(0, creates.get()); assertEquals(6, reads.get());
        } finally { server.stop(0); }
    }
}
