package examples;

import io.genesis.sandbox.ApiException;
import io.genesis.sandbox.Client;
import io.genesis.sandbox.ExecResult;
import io.genesis.sandbox.SandboxSession;
import io.genesis.sandbox.SessionOptions;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/** 推荐的 Java Session 调用方式。 */
public final class Quickstart {
    public static void main(String[] args) throws Exception {
        Client client = new Client(
                getenv("GENESIS_SANDBOX_BASE_URL", "http://127.0.0.1:18010"),
                "test-token-1",
                Duration.ofSeconds(30),
                3,
                Duration.ofMillis(200));

        SessionOptions sessionOptions = SessionOptions.builder()
                .profile("code-polyglot-basic")
                .idempotencyKey("quickstart-" + UUID.randomUUID())
                .build();
        try (SandboxSession sandbox = SandboxSession.open(client, sessionOptions)) {
            sandbox.writeFile("input/name.txt", "Genesis".getBytes(StandardCharsets.UTF_8));
            ExecResult result = sandbox.runPython(
                    "from pathlib import Path\n"
                            + "name = Path('/workspace/input/name.txt').read_text()\n"
                            + "print(f'hello {name}')");
            if (!result.ok()) {
                throw new IllegalStateException(
                        "execution failed: exit=" + result.exitCode
                                + " code=" + result.errorCode + " stderr=" + result.stderr);
            }
            System.out.print(result.stdout);

            String execId = sandbox.runAsync("print('async complete')", "python", 30);
            sandbox.waitExec(execId, 200L, Duration.ofMinutes(1));

            sandbox.suspend();
            sandbox.resume();
            // resume 已显式确认新 Runtime 与原 Workspace 绑定。
            sandbox.runPython("print('resumed')");
        } catch (ApiException error) {
            System.err.printf(
                    "API error code=%s status=%d request_id=%s retryable=%s: %s%n",
                    error.errorCode(), error.statusCode(), error.requestId(),
                    error.retryable(), error.getMessage());
            throw error;
        }
    }

    private static String getenv(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
