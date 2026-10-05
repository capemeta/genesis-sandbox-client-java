package io.genesis.sandbox;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 写响应丢失后只能查询原请求，不能续租计算或重复发送生命周期控制。 */
class WorkspaceLifecycleHttpTest {
    @Test void correctDigestCannotAuthorizeForgedTerminalTimeOrDeadline() throws Exception {
        WorkspaceLifecycle.Request request = new WorkspaceLifecycle.Request("terminal", 0, "terminal", 0,
                Instant.parse("2026-10-01T00:30:12.123456Z"), 86400);
        for (String field : new String[]{"deadline", "terminal", "precision"}) {
            String terminal = "terminal".equals(field) ? "2026-10-01T00:30:13.123456Z" : "2026-10-01T00:30:12.123456Z";
            String deadline = "deadline".equals(field) ? "2026-10-02T00:30:13.123456Z"
                    : "precision".equals(field) ? "2026-10-02T00:30:12.123456001Z" : "2026-10-02T00:30:12.123456Z";
            String body = "{\"operation_id\":\"terminal\",\"workspace_id\":\"w\",\"revision\":1,"
                    + "\"state\":\"terminal\",\"terminal_at\":\"" + terminal + "\",\"deadline\":\"" + deadline + "\","
                    + "\"request_digest\":\"" + WorkspaceLifecycle.requestDigest(request) + "\"}";
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/workspaces/w/lifecycle", exchange -> {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            try {
                Client client = new Client("http://127.0.0.1:" + server.getAddress().getPort(), null);
                assertThrows(IOException.class, () -> client.controlWorkspaceLifecycle("w", request));
            } finally { server.stop(0); }
        }
    }

    @Test void lostControlResponseIsSingleSendAndOriginalLookupIsReadOnly() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        WorkspaceLifecycle.Request request = new WorkspaceLifecycle.Request("original", 0, "active", 3600, null, 0);
        String receipt = "{\"operation_id\":\"original\",\"workspace_id\":\"w\",\"revision\":1,"
                + "\"state\":\"active\",\"deadline\":\"2026-10-02T01:00:00Z\",\"terminal_at\":null,"
                + "\"request_digest\":\"" + WorkspaceLifecycle.requestDigest(request) + "\"}";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/workspaces/w/lifecycle", exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("lifecycle:lookup")) {
                reads.incrementAndGet();
                assertEquals("GET", exchange.getRequestMethod());
                assertEquals("operation_id=original", exchange.getRequestURI().getRawQuery());
                assertEquals(0, exchange.getRequestBody().readAllBytes().length);
                byte[] body = ("{\"state\":\"found\",\"receipt\":" + receipt + "}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                writes.incrementAndGet();
                assertEquals("POST", exchange.getRequestMethod());
                WorkspaceLifecycle.Request sent = JsonCodec.readStrict(exchange.getRequestBody().readAllBytes(), WorkspaceLifecycle.Request.class);
                assertEquals(WorkspaceLifecycle.requestDigest(request), WorkspaceLifecycle.requestDigest(sent));
                exchange.sendResponseHeaders(503, -1);
            }
            exchange.close();
        });
        server.start();
        try {
            Client client = new Client("http://127.0.0.1:" + server.getAddress().getPort(), null,
                    Duration.ofSeconds(5), 3, Duration.ZERO);
            assertThrows(IOException.class, () -> client.controlWorkspaceLifecycle("w", request));
            WorkspaceLifecycle.Lookup lookup = client.lookupWorkspaceLifecycle("w", "original");
            assertEquals("found", lookup.state);
            assertEquals(WorkspaceLifecycle.requestDigest(request), lookup.receipt.requestDigest());
            assertEquals(1, writes.get());
            assertEquals(1, reads.get());
        } finally { server.stop(0); }
    }
}
