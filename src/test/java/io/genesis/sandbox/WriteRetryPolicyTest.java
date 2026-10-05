package io.genesis.sandbox;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 写入未知结果必须核对原操作，不能凭幂等键或HTTP方法自动重发。 */
class WriteRetryPolicyTest {
    @Test void missingDeleteRetainsOriginalApiError() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{\"error_code\":\"WORKSPACE_PATH_NOT_FOUND\",\"message\":\"missing\"}"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            Client client = new Client("http://127.0.0.1:" + server.getAddress().getPort(), null);
            ApiException error = assertThrows(ApiException.class, () -> client.removeSessionFile("s", "work/missing", false));
            assertEquals(404, error.statusCode());
            assertEquals("WORKSPACE_PATH_NOT_FOUND", error.errorCode());
        } finally { server.stop(0); }
    }

    @Test void unknownRenewalStopsBothHeartbeatSchedulers() throws Exception {
        java.util.concurrent.atomic.AtomicInteger renewals = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (exchange.getRequestURI().getPath().endsWith(":renew")) {
                renewals.incrementAndGet(); exchange.sendResponseHeaders(503, -1);
            } else {
                byte[] body = "{\"session_id\":\"s\",\"workspace_id\":\"w\"}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            Client client = new Client("http://127.0.0.1:" + server.getAddress().getPort(), null);
            SessionOptions options = SessionOptions.builder().profile("python").heartbeat(true, 600, 60).build();
            try (SandboxSession sync = SandboxSession.open(client, options)) {
                assertHeartbeatStops(sync);
            }
            AsyncSandboxSession async = AsyncSandboxSession.open(client, options, executor).get();
            try { assertHeartbeatStops(async); } finally { async.close().get(); }
            assertEquals(2, renewals.get());
        } finally { executor.shutdownNow(); server.stop(0); }
    }

    private void assertHeartbeatStops(Object session) throws Exception {
        java.lang.reflect.Method renew = session.getClass().getDeclaredMethod("renew", int.class);
        renew.setAccessible(true); renew.invoke(session, 60);
        java.lang.reflect.Field task = session.getClass().getDeclaredField("renewTask");
        task.setAccessible(true);
        assertTrue(((java.util.concurrent.ScheduledFuture<?>) task.get(session)).isCancelled());
        java.lang.reflect.Field scheduler = session.getClass().getDeclaredField("scheduler");
        scheduler.setAccessible(true);
        assertTrue(((java.util.concurrent.ScheduledExecutorService) scheduler.get(session)).isShutdown());
    }

    @Test void everyMutationIsSentOnceEvenWithOriginalIdentity() throws Exception {
        Map<String, Integer> calls = new LinkedHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String key = exchange.getRequestMethod() + " " + exchange.getRequestURI();
            calls.merge(key, 1, Integer::sum);
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(503, -1); exchange.close();
        });
        server.start();
        try {
            Client client = new Client("http://127.0.0.1:" + server.getAddress().getPort(), null,
                Duration.ofSeconds(5), 4, Duration.ZERO);
            SandboxModels.CreateSessionRequest create = new SandboxModels.CreateSessionRequest();
            create.idempotencyKey = "original-create";
            assertThrows(ApiException.class, () -> client.createSession(create));
            assertThrows(ApiException.class, () -> client.execSessionAsync("s",
                new SandboxModels.ExecRequest().operationId("original-exec").code("print(1)", "python")));
            assertThrows(ApiException.class, () -> client.resumeSession("s"));
            assertThrows(ApiException.class, () -> client.renewSession("s", 60));
            assertThrows(ApiException.class, () -> client.uploadSessionFile("s", "work/a", new byte[]{1}, null, false));
            assertThrows(ApiException.class, () -> client.deleteSession("s"));
            assertEquals(6, calls.size());
            calls.forEach((key, count) -> assertEquals(1, count.intValue(), key));
        } finally { server.stop(0); }
    }
}
