package io.genesis.sandbox;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.genesis.sandbox.SandboxModels.ExecRecord;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ExecRecoveryTest {
    @Test
    void interruptedExecIsTerminalForSdkWait() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        HttpServer server = server();
        installSessionAndExecRoutes(server, exchange -> {
            polls.incrementAndGet();
            write(exchange, 200, execRecord("e-1", "s-1", "op-1", "interrupted"));
        });
        server.start();
        try (SandboxSession session = SandboxSession.open(client(server), "python", 120, 0, 0, false)) {
            session.runAsync("print(1)", "python", 30, "op-1");
            ExecResult result = session.waitExec("e-1", 1, Duration.ofSeconds(2));
            assertEquals(1, polls.get());
            assertEquals(0, result.exitCode);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void clientLooksUpExecBySessionAndOperation() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        HttpServer server = server();
        server.createContext("/v1/sessions/s-1/execs:lookup", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            write(exchange, 200, execRecord("e-1", "s-1", "op-1", "running"));
        });
        server.start();
        try {
            ExecRecord record = client(server).getExecByOperation("s-1", "op-1");
            assertEquals("operation_id=op-1", query.get());
            assertEquals("e-1", record.execId);
            assertEquals("op-1", record.operationId);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sessionPollingRetriesTransientGetAndReturnsReceipt() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        HttpServer server = server();
        installSessionAndExecRoutes(server, exchange -> {
            if (polls.incrementAndGet() == 1) write(exchange, 503, errorBody());
            else write(exchange, 200, execRecord("e-1", "s-1", "op-1", "succeeded"));
        });
        server.start();
        try (SandboxSession session = SandboxSession.open(client(server), "python", 120, 0, 0, false)) {
            assertEquals("e-1", session.runAsync("print(1)", "python", 30, "op-1"));
            ExecResult result = session.waitExec("e-1", 1, Duration.ofSeconds(2));
            assertEquals(0, result.exitCode);
            assertEquals("done", result.stdout);
            assertEquals(2, polls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void finalObservationFailureCarriesRecoveryIdentityWithoutCancel() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        AtomicInteger cancels = new AtomicInteger();
        HttpServer server = server();
        installSessionAndExecRoutes(server, exchange -> {
            polls.incrementAndGet();
            write(exchange, 503, errorBody());
        });
        server.createContext("/v1/sessions/s-1/execs/e-1:cancel", exchange -> {
            cancels.incrementAndGet();
            write(exchange, 200, execRecord("e-1", "s-1", "op-1", "cancelled"));
        });
        server.start();
        try (SandboxSession session = SandboxSession.open(client(server), "python", 120, 0, 0, false)) {
            session.runAsync("print(1)", "python", 30, "op-1");
            ExecRecoveryException error = assertThrows(ExecRecoveryException.class,
                    () -> session.waitExec("e-1", 1, Duration.ofSeconds(2)));
            assertEquals("s-1", error.sessionId());
            assertEquals("op-1", error.operationId());
            assertEquals("e-1", error.execId());
            assertEquals("observe", error.phase());
            assertEquals(3, polls.get());
            assertEquals(0, cancels.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void asyncSessionCanRecoverLookupAndRetainOperationIdOnSubmitFailure() throws Exception {
        HttpServer server = server();
        AtomicInteger execPosts = new AtomicInteger();
        server.createContext("/v1/sessions", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                write(exchange, 201, "{\"session_id\":\"s-1\",\"workspace_id\":\"w-1\",\"status\":\"active\"}");
            } else {
                write(exchange, 204, "");
            }
        });
        server.createContext("/v1/sessions/s-1/execs:lookup", exchange ->
                write(exchange, 200, execRecord("e-1", "s-1", "op-1", "running")));
        server.createContext("/v1/sessions/s-1/exec:async", exchange -> {
            execPosts.incrementAndGet();
            write(exchange, 503, errorBody());
        });
        server.start();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Client client = client(server);
            AsyncSandboxSession session = AsyncSandboxSession.open(
                    client,
                    SessionOptions.builder().profile("python")
                            .heartbeat(false, 0, 0).build(),
                    executor).get();
            ExecRecord found = session.getExecByOperation("op-1").get();
            assertEquals("e-1", found.execId);
            try {
                session.runAsync("print(1)", "python", 30, "op-submit").join();
                fail("submit failure should carry the stable operation identity");
            } catch (java.util.concurrent.CompletionException error) {
                assertInstanceOf(ExecRecoveryException.class, error.getCause());
                ExecRecoveryException recovery = (ExecRecoveryException) error.getCause();
                assertEquals("s-1", recovery.sessionId());
                assertEquals("op-submit", recovery.operationId());
                assertEquals("submit", recovery.phase());
            }
            assertEquals(1, execPosts.get());
            session.close().get();
        } finally {
            executor.shutdownNow();
            server.stop(0);
        }
    }

    @Test
    void syncLegacyRunAsyncGeneratesStableOperationIdAcrossRetries() throws Exception {
        AtomicInteger posts = new AtomicInteger();
        List<String> operationIds = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = server();
        installSessionRoute(server);
        server.createContext("/v1/sessions/s-1/exec:async", exchange -> {
            posts.incrementAndGet();
            operationIds.add(readOperationId(exchange));
            write(exchange, 503, errorBody());
        });
        server.start();
        try (SandboxSession session = SandboxSession.open(
                client(server, 3), "python", 120, 0, 0, false)) {
            ExecRecoveryException error = assertThrows(ExecRecoveryException.class,
                    () -> session.runAsync("print(1)", "python", 30));
            assertEquals(3, posts.get());
            assertEquals(3, operationIds.size());
            assertFalse(operationIds.get(0).isBlank());
            assertTrue(operationIds.stream().allMatch(operationIds.get(0)::equals));
            assertEquals(operationIds.get(0), error.operationId());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void asyncLegacyRunAsyncGeneratesStableOperationIdAcrossRetries() throws Exception {
        AtomicInteger posts = new AtomicInteger();
        List<String> operationIds = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = server();
        installSessionRoute(server);
        server.createContext("/v1/sessions/s-1/exec:async", exchange -> {
            posts.incrementAndGet();
            operationIds.add(readOperationId(exchange));
            write(exchange, 503, errorBody());
        });
        server.start();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AsyncSandboxSession session = AsyncSandboxSession.open(
                    client(server, 3), SessionOptions.builder().profile("python")
                            .heartbeat(false, 0, 0).build(), executor).get();
            try {
                session.runAsync("print(1)", "python", 30).join();
                fail("submit failure should carry the generated operation identity");
            } catch (java.util.concurrent.CompletionException error) {
                assertInstanceOf(ExecRecoveryException.class, error.getCause());
                ExecRecoveryException recovery = (ExecRecoveryException) error.getCause();
                assertEquals(3, posts.get());
                assertEquals(3, operationIds.size());
                assertFalse(operationIds.get(0).isBlank());
                assertTrue(operationIds.stream().allMatch(operationIds.get(0)::equals));
                assertEquals(operationIds.get(0), recovery.operationId());
            }
            session.close().get();
        } finally {
            executor.shutdownNow();
            server.stop(0);
        }
    }

    @Test
    void definiteClientErrorIsNotReportedAsRecoverable() throws Exception {
        AtomicInteger posts = new AtomicInteger();
        HttpServer server = server();
        installSessionRoute(server);
        server.createContext("/v1/sessions/s-1/exec:async", exchange -> {
            posts.incrementAndGet();
            write(exchange, 400, "{\"error_code\":\"INVALID_ARGUMENT\",\"message\":\"invalid command\",\"request_id\":\"r\",\"details\":{}}");
        });
        server.start();
        try (SandboxSession session = SandboxSession.open(client(server, 3), "python", 120, 0, 0, false)) {
            ApiException error = assertThrows(ApiException.class,
                    () -> session.runAsync("print(1)", "python", 30));
            assertEquals("INVALID_ARGUMENT", error.errorCode());
            assertEquals(1, posts.get());
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer server() throws IOException {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    private static Client client(HttpServer server) {
        return client(server, 1);
    }

    private static Client client(HttpServer server, int maxAttempts) {
        return new Client("http://127.0.0.1:" + server.getAddress().getPort(), null,
                Duration.ofSeconds(2), maxAttempts, Duration.ZERO);
    }

    private static void installSessionRoute(HttpServer server) {
        server.createContext("/v1/sessions", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                write(exchange, 201, "{\"session_id\":\"s-1\",\"workspace_id\":\"w-1\",\"status\":\"active\"}");
            } else {
                write(exchange, 204, "");
            }
        });
    }

    private static String readOperationId(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("\\\"operation_id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(body);
        if (!matcher.find()) throw new IOException("exec request did not include operation_id");
        return matcher.group(1);
    }

    private static void installSessionAndExecRoutes(HttpServer server, ExchangeHandler pollHandler) {
        server.createContext("/v1/sessions", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                write(exchange, 201, "{\"session_id\":\"s-1\",\"workspace_id\":\"w-1\",\"status\":\"active\"}");
            } else {
                write(exchange, 204, "");
            }
        });
        server.createContext("/v1/sessions/s-1/exec:async", exchange ->
                write(exchange, 202, execRecord("e-1", "s-1", "op-1", "running")));
        server.createContext("/v1/sessions/s-1/execs/e-1", pollHandler::handle);
    }

    private static String execRecord(String execId, String sessionId, String operationId, String status) {
        return "{\"exec_id\":\"" + execId + "\",\"session_id\":\"" + sessionId
                + "\",\"operation_id\":\"" + operationId + "\",\"status\":\"" + status
                + "\",\"exit_code\":0,\"stdout\":\"done\",\"stderr\":\"\"}";
    }

    private static String errorBody() {
        return "{\"error_code\":\"TEMPORARY\",\"message\":\"temporary\","
                + "\"details\":{\"retryable\":true}}";
    }

    private static void write(HttpExchange exchange, int status, String payload) throws IOException {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        if (status == 204) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler { void handle(HttpExchange exchange) throws IOException; }
}
