package io.genesis.sandbox;

// 真实服务集成测试：GENESIS_SANDBOX_INTEGRATION=1 时启用的本地/远端沙箱服务。
//
// 前置：服务已启动（scripts/start-dev-local.bat），并设置：
//   GENESIS_SANDBOX_INTEGRATION=1
//   GENESIS_SANDBOX_BASE_URL=http://127.0.0.1:18010
//   GENESIS_SANDBOX_API_KEY=<users.local.yaml 中的个人 key>
//
// 覆盖矩阵：能力发现、会话生命周期（幂等 lookup/续租/挂起/恢复/清理）、
// 短任务同步执行、长任务异步执行与轮询、operation_id 恢复、取消、
// 文件往返、多会话并发、单会话并发 exec、错误路径、一次性 Job。

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "GENESIS_SANDBOX_INTEGRATION", matches = "1")
class IntegrationTest {

    private static final String BASE_URL =
            System.getenv().getOrDefault("GENESIS_SANDBOX_BASE_URL", "http://127.0.0.1:18010");
    private static final String API_KEY = System.getenv().getOrDefault("GENESIS_SANDBOX_API_KEY", "");

    private static Client client;

    private final List<SandboxSession> sessions = new ArrayList<>();

    @BeforeAll
    static void requireApiKey() {
        assertTrue(API_KEY != null && !API_KEY.isBlank(), "GENESIS_SANDBOX_API_KEY is required for integration tests");
        client = new Client(BASE_URL, API_KEY);
    }

    @AfterEach
    void cleanupSessions() throws Exception {
        // 活跃 exec 会让 close 撞 SESSION_HAS_ACTIVE_EXECS；清理尽力而为。
        for (SandboxSession session : sessions) {
            try {
                session.close();
            } catch (Exception ignored) {
                try {
                    client.deleteSession(session.getSessionId());
                } catch (Exception ignored2) {
                    // 会话可能已被业务测试删除。
                }
            }
        }
    }

    /** 打开一个禁用心跳、TTL 工作区的确定性会话并注册清理。 */
    private SandboxSession openSession() throws Exception {
        SessionOptions options = new SessionOptions.Builder()
                .profile("code-polyglot-basic")
                .ttlSeconds(600)
                .retention("ttl", 900)
                .heartbeat(false, 0, 0)
                .idempotencyKey("it-java-" + UUID.randomUUID())
                .build();
        SandboxSession session = SandboxSession.open(client, options);
        sessions.add(session);
        return session;
    }

    private static void deleteWorkspaceQuietly(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return;
        }
        try {
            client.deleteWorkspace(workspaceId);
        } catch (Exception ignored) {
            // 尽力释放租户配额；失败交由 TTL 清扫。
        }
    }

    @Test
    void catalogAndProfiles() throws Exception {
        client.getRuntimeCapabilities();
        var profiles = client.listRuntimeProfiles();
        assertTrue(profiles.size() > 0, "runtime profiles should not be empty");
        assertTrue(profiles.stream().anyMatch(p -> "code-polyglot-basic".equals(p.name)),
                "code-polyglot-basic missing");
    }

    @Test
    void sessionLifecycleAndShortExec() throws Exception {
        try (SandboxSession session = openSession()) {
            SandboxModels.Session info = client.getSession(session.getSessionId());
            assertEquals("active", info.status);
            assertEquals("session", info.statePolicy);
            assertNotNull(info.workspaceId);

            ExecResult result = session.runPython("print(1+1)");
            assertTrue(result.ok(), "exec failed: " + result);
            assertEquals("2", result.stdout.trim());

            deleteWorkspaceQuietly(info.workspaceId);
        }
    }

    @Test
    void idempotencyLookupRoundTrip() throws Exception {
        String idemKey = "it-java-lookup-" + UUID.randomUUID();
        SessionOptions options = new SessionOptions.Builder()
                .profile("code-polyglot-basic")
                .ttlSeconds(600)
                .retention("ttl", 900)
                .heartbeat(false, 0, 0)
                .idempotencyKey(idemKey)
                .build();
        try (SandboxSession session = SandboxSession.open(client, options)) {
            sessions.add(session);
            SandboxModels.Session recovered = client.lookupSession(idemKey);
            assertNotNull(recovered);
            assertEquals(session.getSessionId(), recovered.sessionId);
            assertEquals(idemKey, recovered.idempotencyKey);
            assertNull(client.lookupSession("it-java-absent-" + UUID.randomUUID()));
            deleteWorkspaceQuietly(session.workspaceId());
        }
    }

    @Test
    void filesRoundTrip() throws Exception {
        try (SandboxSession session = openSession()) {
            byte[] payload = "hello java integration".getBytes();
            session.writeFile("notes/input.txt", payload);

            ExecResult cat = session.runCommand("cat", "/workspace/notes/input.txt");
            assertTrue(cat.ok(), cat.toString());
            assertEquals("hello java integration", cat.stdout);

            assertEquals("hello java integration", session.readFileText("notes/input.txt"));

            SandboxModels.WorkspaceFileList listing = session.listFiles("notes", false, null, null);
            assertTrue(listing.entries.stream().anyMatch(e -> "input.txt".equals(e.name)), "listing should contain the uploaded file");

            SandboxModels.WorkspaceFileInfo stat = session.statFile("notes/input.txt");
            assertEquals(payload.length, stat.size);

            session.remove("notes/input.txt", false);
            ApiException missing = assertThrows(ApiException.class, () -> session.statFile("notes/input.txt"));
            assertEquals(404, missing.statusCode());
            assertEquals("WORKSPACE_PATH_NOT_FOUND", missing.errorCode());

            deleteWorkspaceQuietly(session.workspaceId());
        }
    }

    @Test
    void longAsyncExecPollingAndOperationLookup() throws Exception {
        try (SandboxSession session = openSession()) {
            String operationId = "op-" + UUID.randomUUID();
            String execId = session.runAsync(
                    "import time\ntime.sleep(8)\nprint('long-done')", "python", 60, operationId);

            Thread.sleep(3000);
            var mid = client.getExec(session.getSessionId(), execId);
            assertNotEquals("succeeded", mid.status);
            assertNotEquals("failed", mid.status);

            // operation_id 恢复路径与 exec_id 指向同一记录。
            var byOp = client.getExecByOperation(session.getSessionId(), operationId);
            assertEquals(execId, byOp.execId);

            ExecResult result = session.waitExec(execId, 500, Duration.ofSeconds(60));
            assertTrue(result.ok(), result.toString());
            assertEquals("long-done", result.stdout.trim());

            deleteWorkspaceQuietly(session.workspaceId());
        }
    }

    @Test
    void cancelLongExec() throws Exception {
        try (SandboxSession session = openSession()) {
            String execId = session.runAsync(
                    "import time\ntime.sleep(60)\nprint('nope')", "python", 90);
            Thread.sleep(2000);

            client.cancelExec(session.getSessionId(), execId);
            ExecResult result = session.waitExec(execId, 500, Duration.ofSeconds(60));
            assertTrue(!result.ok(), "cancelled exec must not report ok: " + result);
            assertTrue("cancelled".equals(result.errorCode) || "EXEC_CANCELLED".equals(result.errorCode),
                    "unexpected error code: " + result.errorCode);

            deleteWorkspaceQuietly(session.workspaceId());
        }
    }

    @Test
    void suspendResumeLifecycle() throws Exception {
        try (SandboxSession session = openSession()) {
            assertTrue(session.runPython("print('warm')").ok());

            session.suspend();
            SandboxModels.Session suspended = client.getSession(session.getSessionId());
            assertEquals("", suspended.activeSandboxId == null ? "" : suspended.activeSandboxId,
                    "suspend should stop runtime binding");

            // Java SDK 有显式 resume：恢复 runtime 后工作区文件仍在。
            session.writeFile("keep.txt", "persisted".getBytes());
            session.resume();
            ExecResult result = session.runPython("print(open('/workspace/keep.txt').read().strip())");
            assertTrue(result.ok(), result.toString());
            assertEquals("persisted", result.stdout.trim());

            deleteWorkspaceQuietly(session.workspaceId());
        }
    }

    @Test
    void suspendWithActiveExecConflicts() throws Exception {
        try (SandboxSession session = openSession()) {
            String execId = session.runAsync("import time\ntime.sleep(15)\nprint('x')", "python", 60);
            Thread.sleep(2000);
            try {
                ApiException error = assertThrows(ApiException.class,
                        () -> client.suspendSession(session.getSessionId()));
                assertTrue("SESSION_HAS_ACTIVE_EXEC".equals(error.errorCode())
                                || "SESSION_HAS_ACTIVE_EXECS".equals(error.errorCode()),
                        "unexpected error code: " + error.errorCode());
            } finally {
                try {
                    client.cancelExec(session.getSessionId(), execId);
                    session.waitExec(execId, 500, Duration.ofSeconds(30));
                } catch (Exception ignored) {
                    // 尽力取消，避免清理路径 409。
                }
            }
            deleteWorkspaceQuietly(session.workspaceId());
        }
    }

    @Test
    void renewExtendsExpiry() throws Exception {
        try (SandboxSession session = openSession()) {
            SandboxModels.Session before = client.getSession(session.getSessionId());
            // 续租语义：newExpiry = now + extend，只能延长（TTL 600s，续 900s 才可见）。
            SandboxModels.Session after = client.renewSession(session.getSessionId(), 900);
            Instant beforeExpiry = before.expiresAt;
            Instant afterExpiry = after.expiresAt;
            assertTrue(afterExpiry.isAfter(beforeExpiry),
                    "renew should extend expiry: before=" + beforeExpiry + " after=" + afterExpiry);

            deleteWorkspaceQuietly(session.workspaceId());
        }
    }

    @Test
    void concurrentSessions() throws Exception {
        // 全局 CPU 配额 4 核：3 并发留 1 核余量给 draining 残留，避免贴顶 429。
        int workers = 3;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int idx = 0; idx < workers; idx++) {
                final int i = idx;
                tasks.add(() -> {
                    SessionOptions options = new SessionOptions.Builder()
                            .profile("code-polyglot-basic")
                            .ttlSeconds(600)
                            .retention("ttl", 900)
                            .heartbeat(false, 0, 0)
                            .build();
                    try (SandboxSession session = SandboxSession.open(client, options)) {
                        StringBuilder outputs = new StringBuilder();
                        for (int j = 0; j < 2; j++) {
                            ExecResult result = session.runPython("print(" + i + "*100+" + j + ")");
                            assertTrue(result.ok(), result.toString());
                            outputs.append(result.stdout.trim()).append(",");
                        }
                        deleteWorkspaceQuietly(session.workspaceId());
                        return outputs.toString();
                    }
                });
            }
            List<Future<String>> futures = pool.invokeAll(tasks);
            for (int idx = 0; idx < workers; idx++) {
                assertEquals(idx * 100 + "," + (idx * 100 + 1) + ",", futures.get(idx).get());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentAsyncExecsInOneSession() throws Exception {
        try (SandboxSession session = openSession()) {
            int n = 3;
            List<String> execIds = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                execIds.add(session.runAsync(
                        "import time\ntime.sleep(" + (3 + i) + ")\nprint('c" + i + "')", "python", 60));
            }
            for (int i = 0; i < n; i++) {
                ExecResult result = session.waitExec(execIds.get(i), 500, Duration.ofSeconds(90));
                assertTrue(result.ok(), result.toString());
                assertEquals("c" + i, result.stdout.trim());
            }

            deleteWorkspaceQuietly(session.workspaceId());
        }
    }

    @Test
    void cleanupSemantics() throws Exception {
        SandboxSession session = openSession();
        String sessionId = session.getSessionId();
        String workspaceId = session.workspaceId();
        session.close();
        deleteWorkspaceQuietly(workspaceId);

        ApiException missing = assertThrows(ApiException.class, () -> client.getSession(sessionId));
        assertEquals(404, missing.statusCode());

        // 重复删除幂等：成功或 404/410，绝不允许 5xx。
        try {
            client.deleteSession(sessionId);
        } catch (ApiException error) {
            assertTrue(error.statusCode() < 500, "double delete must be idempotent");
        }

        assertThrows(Exception.class, () -> session.runPython("print('zombie')"));
    }

    @Test
    void unknownProfileRejected() throws Exception {
        SessionOptions options = new SessionOptions.Builder()
                .profile("no-such-profile")
                .heartbeat(false, 0, 0)
                .build();
        ApiException error = assertThrows(ApiException.class, () -> SandboxSession.open(client, options));
        assertEquals("PROFILE_NOT_FOUND", error.errorCode());
    }

    @Test
    void invalidTokenRejected() {
        Client bad = new Client(BASE_URL, "definitely-wrong");
        ApiException error = assertThrows(ApiException.class, bad::getRuntimeCapabilities);
        assertEquals(401, error.statusCode());
    }

    @Test
    void quickPythonJob() throws Exception {
        // Job 一次性执行：无会话，自动回收。
        SandboxModels.SubmitJobRequest request = new SandboxModels.SubmitJobRequest()
                .code("print('quick-42')");
        request.environment(SandboxModels.EnvironmentSelector.profile("code-polyglot-basic"));
        SandboxModels.JobResult submitted = client.submitJob(request);
        SandboxModels.JobResult result = client.waitJob(submitted.jobId, 500);
        assertEquals("succeeded", result.status);
        assertEquals("quick-42", result.stdout.trim());
    }

}
