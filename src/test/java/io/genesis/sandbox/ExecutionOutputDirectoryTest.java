package io.genesis.sandbox;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionOutputDirectoryTest {
    @Test void requestSerializesOptionalOutputAndRejectsInvalidRegions() throws Exception {
        var request = new SandboxModels.ExecRequest().command("python", "--version");
        assertFalse(JsonCodec.MAPPER.readTree(JsonCodec.write(request)).has("output_directory"));
        request.outputDirectory("output/a-1._:safe"); request.validate();
        assertEquals("output/a-1._:safe", JsonCodec.MAPPER.readTree(JsonCodec.write(request)).get("output_directory").asText());
        request.outputDirectory(null); request.validate();
        assertFalse(JsonCodec.MAPPER.readTree(JsonCodec.write(request)).has("output_directory"));
        request.outputDirectory("output/" + "a".repeat(128)).validate();
        for (String invalid : new String[]{"", "output", "output/", "output/..", "output/a/b", "work/a",
                "payload/a", "/output/a", "output\\a", "output/a\n", "output/a ", "output/" + "a".repeat(129)}) {
            assertThrows(IllegalArgumentException.class, () -> request.outputDirectory(invalid));
            request.outputDirectory = invalid;
            assertThrows(IllegalArgumentException.class, request::validate);
        }
    }

    @Test void synchronousAndAsynchronousExecCarryOutputWithoutMutatingCallIdentity() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var bodies = new ArrayList<String>();
        server.createContext("/v1/sessions/s/exec", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"exec_id\":\"e\",\"operation_id\":\"original\",\"session_id\":\"s\",\"status\":\"succeeded\",\"exit_code\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var client = new Client("http://127.0.0.1:" + server.getAddress().getPort(), null, Duration.ofSeconds(5), 4, Duration.ZERO);
            var request = new SandboxModels.ExecRequest().operationId("original").command("python").outputDirectory("output/original");
            client.execNamedSession("s", request);
            client.execSessionAsync("s", request);
            assertEquals(2, bodies.size());
            for (String body : bodies) {
                assertEquals("output/original", JsonCodec.MAPPER.readTree(body).get("output_directory").asText());
                assertEquals("original", JsonCodec.MAPPER.readTree(body).get("operation_id").asText());
            }
            request.outputDirectory = "";
            assertThrows(IllegalArgumentException.class, () -> client.execNamedSession("s", request));
            assertEquals(2, bodies.size());
        } finally { server.stop(0); }
    }

    @Test void failedWriteWithOutputIsStillSentOnlyOnce() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        server.createContext("/v1/sessions/s/exec", exchange -> {
            calls.incrementAndGet(); exchange.sendResponseHeaders(503, -1); exchange.close();
        });
        server.start();
        try {
            var client = new Client("http://127.0.0.1:" + server.getAddress().getPort(), null, Duration.ofSeconds(5), 4, Duration.ZERO);
            assertThrows(java.io.IOException.class, () -> client.execNamedSession("s",
                new SandboxModels.ExecRequest().command("python").outputDirectory("output/original")));
            assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }
}
