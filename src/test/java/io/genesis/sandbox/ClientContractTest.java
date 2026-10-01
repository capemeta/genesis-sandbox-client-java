package io.genesis.sandbox;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static io.genesis.sandbox.SandboxModels.JobResult;
import static io.genesis.sandbox.SandboxModels.LeaseRequest;
import static io.genesis.sandbox.SandboxModels.BuildDependencyRequest;
import static io.genesis.sandbox.SandboxModels.ResolveEnvironmentRequest;
import static io.genesis.sandbox.SandboxModels.SubmitJobRequest;

/** Transport and JSON contract test; run with assertions enabled. */
public final class ClientContractTest {
    /**
     * Allows running the same contract checks directly when Maven is unavailable.
     */
    public static void main(String[] args) throws Exception {
        ClientContractTest test = new ClientContractTest();
        test.requestModelsDoNotExposeCallerIdentity();
        test.leaseRequestMatchesClosedOpenApiSchema();
        test.resolveEnvironmentRequiresEnvironment();
        test.buildDependenciesRequiresImmutableEnvironment();
        test.decodesSseEvents();
        test.transportAndJsonContract();
    }

    @Test
    void requestModelsDoNotExposeCallerIdentity() {
        for (Class<?> requestType : new Class<?>[] { LeaseRequest.class, SubmitJobRequest.class }) {
            for (String fieldName : new String[] { "tenantId", "userId" }) {
                try {
                    requestType.getField(fieldName);
                    throw new AssertionError(requestType.getSimpleName() + " exposes " + fieldName);
                } catch (NoSuchFieldException expected) {
                    // Identity is derived exclusively from the Bearer key.
                }
            }
        }
    }

    @Test
    void leaseRequestMatchesClosedOpenApiSchema() throws Exception {
        for (String fieldName : new String[] { "riskLevel", "spec", "policyRequest" }) {
            try {
                LeaseRequest.class.getField(fieldName);
                throw new AssertionError("LeaseRequest exposes unsupported field " + fieldName);
            } catch (NoSuchFieldException expected) {
                // LeaseRequest must match the closed request schema in OpenAPI.
            }
        }
        String json = new String(JsonCodec.write(new LeaseRequest()), StandardCharsets.UTF_8);
        if (json.contains("risk_level") || json.contains("policy_request") || json.contains("\"spec\"")) {
            throw new AssertionError("LeaseRequest serialization contains fields rejected by the server: " + json);
        }
    }

    @Test
    void resolveEnvironmentRequiresEnvironment() throws Exception {
        Client client = new Client("http://127.0.0.1:1", null);
        try {
            client.resolveEnvironment(new ResolveEnvironmentRequest());
            throw new AssertionError("resolveEnvironment should reject a missing environment");
        } catch (IllegalArgumentException expected) {
            assertEquals("request.environment is required", expected.getMessage());
        }
    }

    @Test
    void buildDependenciesRequiresImmutableEnvironment() throws Exception {
        Client client = new Client("http://127.0.0.1:1", null);
        try {
            client.buildDependencies(new BuildDependencyRequest());
            throw new AssertionError("buildDependencies should reject a missing environment");
        } catch (IllegalArgumentException expected) {
            assertEquals(
                    "exactly one of request.environment or request.resolutionId is required",
                    expected.getMessage());
        }
    }

    @Test
    void transportAndJsonContract() throws Exception {
        AtomicInteger postCalls = new AtomicInteger();
        AtomicInteger idempotentPostCalls = new AtomicInteger();
        AtomicInteger getCalls = new AtomicInteger();
        AtomicInteger resumeCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/jobs", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                String requestBody = new String(
                        exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (requestBody.contains("\"idempotency_key\":\"request-1\"")) {
                    if (idempotentPostCalls.incrementAndGet() == 1) {
                        write(exchange, 503, errorBody());
                    } else {
                        write(exchange, 200, "{\"job_id\":\"job-2\",\"status\":\"queued\",\"future_field\":true}");
                    }
                    return;
                }
                postCalls.incrementAndGet();
                write(exchange, 503, errorBody());
                return;
            }
            write(exchange, 404, errorBody());
        });
        server.createContext("/v1/jobs/job-1", exchange -> {
            if (getCalls.incrementAndGet() == 1) {
                write(exchange, 503, errorBody());
                return;
            }
            write(exchange, 200, "{\"job_id\":\"job-1\",\"status\":\"succeeded\",\"future_field\":true}");
        });
        server.createContext("/v1/sessions/session-1:resume", exchange -> {
            resumeCalls.incrementAndGet();
            write(exchange, 200, "{\"session_id\":\"session-1\",\"workspace_id\":\"workspace-1\","
                    + "\"status\":\"active\",\"active_sandbox_id\":\"sandbox-1\"}");
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            Client client = new Client(
                    baseUrl, null, Duration.ofSeconds(2), 3, Duration.ofMillis(1));

            try {
                client.submitJob(new SubmitJobRequest().code("print(\"你好\")"));
                throw new AssertionError("submitJob should fail");
            } catch (ApiException error) {
                assertEquals("EXECD_UNAVAILABLE", error.errorCode());
                assertEquals("req_test", error.requestId());
                if (!error.retryable())
                    throw new AssertionError("error should be retryable");
                assertEquals("execd", error.details().get("component"));
            }
            assertEquals(1, postCalls.get());

            JobResult idempotentJob = client.submitJob(new SubmitJobRequest()
                    .code("print(1)")
                    .idempotencyKey("request-1"));
            assertEquals("job-2", idempotentJob.jobId);
            assertEquals(2, idempotentPostCalls.get());

            JobResult job = client.getJob("job-1");
            assertEquals("succeeded", job.status);
            assertEquals(2, getCalls.get());

            assertEquals("sandbox-1", client.resumeSession("session-1").activeSandboxId);
            assertEquals("sandbox-1", client.resumeSession("session-1").activeSandboxId);
            assertEquals(2, resumeCalls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void decodesSseEvents() throws Exception {
        byte[] source = "id: 7\nevent: stdout\ndata: {\"message\":\"hello\"}\n\n"
                .getBytes(StandardCharsets.UTF_8);
        try (SseStream stream = new SseStream(new ByteArrayInputStream(source))) {
            SseEvent event = stream.next().orElseThrow();
            assertEquals("7", event.id());
            assertEquals("stdout", event.event());
            if (!event.data().contains("hello")) {
                throw new AssertionError("unexpected SSE data: " + event.data());
            }
        }
    }

    private static String errorBody() {
        return "{\"error_code\":\"EXECD_UNAVAILABLE\","
                + "\"message\":\"execd unavailable\","
                + "\"request_id\":\"req_test\","
                + "\"details\":{\"retryable\":true,\"component\":\"execd\","
                + "\"nested\":{\"reason\":\"unreachable\"}}}";
    }

    private static void write(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, data.length);
        exchange.getResponseBody().write(data);
        exchange.close();
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + ", actual=" + actual);
        }
    }
}
