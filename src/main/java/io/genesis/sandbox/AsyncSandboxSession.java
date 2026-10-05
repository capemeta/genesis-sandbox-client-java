package io.genesis.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.genesis.sandbox.SandboxModels.*;

/** CompletableFuture-based Session helper. Blocking HTTP work runs only on the supplied executor. */
public final class AsyncSandboxSession {
    private static final Logger LOG = Logger.getLogger(AsyncSandboxSession.class.getName());

    private final Client client;
    private final ExecutorService ioExecutor;
    private final String sessionId;
    private final String workspaceId;
    private final Map<String, String> operationByExecId = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler;
    private final ScheduledFuture<?> renewTask;
    private volatile Session session;

    private AsyncSandboxSession(Client client, Session session, ExecutorService executor,
                                SessionOptions options) {
        this.client = client;
        this.session = session;
        this.ioExecutor = executor;
        this.sessionId = required(session.sessionId, "session_id");
        this.workspaceId = required(session.workspaceId, "workspace_id");
        if (options.heartbeat && options.renewIntervalSeconds > 0 && options.renewExtendSeconds > 0) {
            scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "async-sandbox-renew-" + sessionId);
                thread.setDaemon(true);
                return thread;
            });
            renewTask = scheduler.scheduleWithFixedDelay(
                    () -> renew(options.renewExtendSeconds),
                    options.renewIntervalSeconds,
                    options.renewIntervalSeconds,
                    TimeUnit.SECONDS
            );
        } else {
            scheduler = null;
            renewTask = null;
        }
    }

    public static CompletableFuture<AsyncSandboxSession> open(Client client, String profile) {
        return open(client, SessionOptions.builder().profile(profile).build(),
                ForkJoinPool.commonPool());
    }

    public static CompletableFuture<AsyncSandboxSession> open(
            Client client, SessionOptions options, ExecutorService executor) {
        if (client == null || options == null || executor == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("client, options and executor are required"));
        }
        return supply(executor, () -> new AsyncSandboxSession(
                client, client.createSession(options.toRequest()), executor, options));
    }

    public static CompletableFuture<AsyncSandboxSession> open(
            Client client, String profile, int ttlSeconds, ExecutorService executor) {
        return open(client, SessionOptions.builder()
                .profile(profile)
                .ttlSeconds(ttlSeconds)
                .build(), executor);
    }

    public static CompletableFuture<AsyncSandboxSession> open(
            Client client, String profile, int ttlSeconds, ExecutorService executor,
            int renewIntervalSeconds, int renewExtendSeconds, boolean heartbeat) {
        return open(client, SessionOptions.builder()
                .profile(profile)
                .ttlSeconds(ttlSeconds)
                .heartbeat(heartbeat, renewIntervalSeconds, renewExtendSeconds)
                .build(), executor);
    }

    public static CompletableFuture<AsyncSandboxSession> open(
            Client client, String profile, String workspaceId, int ttlSeconds,
            ExecutorService executor, int renewIntervalSeconds,
            int renewExtendSeconds, boolean heartbeat) {
        return open(client, SessionOptions.builder()
                .profile(profile)
                .workspaceId(workspaceId)
                .ttlSeconds(ttlSeconds)
                .heartbeat(heartbeat, renewIntervalSeconds, renewExtendSeconds)
                .build(), executor);
    }

    public static CompletableFuture<AsyncSandboxSession> openWithHints(
            Client client, List<String> capabilities, boolean strict, String description,
            int ttlSeconds, ExecutorService executor) {
        return open(client, SessionOptions.builder()
                .hints(capabilities, strict, description)
                .ttlSeconds(ttlSeconds)
                .build(), executor);
    }

    public String getSessionId() { return sessionId; }
    public String getSandboxId() { return session.activeSandboxId == null ? "" : session.activeSandboxId; }
    public String getWorkspaceId() { return workspaceId; }
    public Session getSession() { return session; }

    public CompletableFuture<ExecResult> runPython(String code) {
        return runPython(code, SandboxSession.DEFAULT_JOB_TIMEOUT);
    }

    public CompletableFuture<ExecResult> runPython(String code, int timeoutSeconds) {
        return execute(new ExecRequest().code(code, "python").timeoutSeconds(timeoutSeconds));
    }

    public CompletableFuture<ExecResult> runShell(String script) {
        return runShell(script, SandboxSession.DEFAULT_JOB_TIMEOUT);
    }

    public CompletableFuture<ExecResult> runShell(String script, int timeoutSeconds) {
        return execute(new ExecRequest()
                .command("sh", "-lc", required(script, "script"))
                .timeoutSeconds(timeoutSeconds));
    }

    public CompletableFuture<ExecResult> runNode(String code) {
        return runNode(code, SandboxSession.DEFAULT_JOB_TIMEOUT);
    }

    public CompletableFuture<ExecResult> runNode(String code, int timeoutSeconds) {
        return execute(new ExecRequest().code(code, "javascript").timeoutSeconds(timeoutSeconds));
    }

    public CompletableFuture<ExecResult> runCommand(String... command) {
        return execute(new ExecRequest().command(command)
                .timeoutSeconds(SandboxSession.DEFAULT_JOB_TIMEOUT));
    }

    public CompletableFuture<ExecResult> execute(ExecRequest request) {
        return supply(ioExecutor, () -> {
            ensureOpen();
            ensureOperationId(request);
            ExecRecord record;
            try {
                record = client.execNamedSession(sessionId, request);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw error;
            } catch (ApiException error) {
                if (!error.retryable()) throw error;
                throw new ExecRecoveryException(sessionId, request.operationId, null, "submit", error);
            } catch (IOException error) {
                throw new ExecRecoveryException(sessionId, request.operationId, null, "submit", error);
            }
            recordExecution(record);
            return ExecResult.from(record);
        });
    }

    public CompletableFuture<WorkspaceFileInfo> writeFile(String path, byte[] content) {
        return writeFile(path, content, null, false);
    }

    public CompletableFuture<WorkspaceFileInfo> writeFile(
            String path, byte[] content, String ifMatch, boolean createOnly) {
        return supply(ioExecutor, () -> {
            ensureOpen();
            return client.uploadSessionFile(sessionId, path, content, ifMatch, createOnly);
        });
    }

    public CompletableFuture<byte[]> readFile(String path) {
        return supply(ioExecutor, () -> {
            ensureOpen();
            return client.downloadSessionFile(sessionId, path);
        });
    }

    public CompletableFuture<String> readFileText(String path) {
        return readFile(path).thenApply(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    public CompletableFuture<WorkspaceFileList> listFiles(
            String path, boolean recursive, Integer limit, Integer offset) {
        return supply(ioExecutor,
                () -> client.listSessionFiles(sessionId, path, recursive, limit, offset));
    }

    public CompletableFuture<WorkspaceFileInfo> statFile(String path) {
        return supply(ioExecutor, () -> client.statSessionFile(sessionId, path));
    }

    public CompletableFuture<WorkspaceFileInfo> mkdir(String path) {
        return supply(ioExecutor, () -> client.mkdirSessionDir(sessionId, path));
    }

    public CompletableFuture<Session> lookupSession(String idempotencyKey) {
        return supply(ioExecutor, () -> client.lookupSession(idempotencyKey));
    }

    public CompletableFuture<WorkspaceFileInfo> setExecutable(String path, boolean executable,
                                                               String ifMatch, Boolean expectedExecutable) {
        return supply(ioExecutor, () -> {
            ensureOpen();
            return client.setSessionFileExecutable(sessionId, path, executable, ifMatch, expectedExecutable);
        });
    }

    public CompletableFuture<Void> remove(String path, boolean recursive) {
        return run(ioExecutor, () -> client.removeSessionFile(sessionId, path, recursive));
    }

    public CompletableFuture<Void> remove(String path, boolean recursive, String ifMatch) {
        return run(ioExecutor, () -> { ensureOpen(); client.removeSessionFile(sessionId, path, recursive, ifMatch); });
    }

    public CompletableFuture<Session> suspend() {
        return supply(ioExecutor, () -> {
            ensureOpen();
            session = client.suspendSession(sessionId);
            return session;
        });
    }

    public CompletableFuture<Session> resume() {
        return supply(ioExecutor, () -> {
            ensureOpen();
            session = client.resumeSession(sessionId);
            return session;
        });
    }

    public CompletableFuture<String> runAsync(String code, String language, int timeoutSeconds) {
        return runAsync(code, language, timeoutSeconds, null);
    }

    /** Submit with a caller-retained operation identity for recovery after client restart. */
    public CompletableFuture<String> runAsync(
            String code, String language, int timeoutSeconds, String operationId) {
        return supply(ioExecutor, () -> {
            ensureOpen();
            String stableOperationId = operationId == null || operationId.isBlank()
                    ? java.util.UUID.randomUUID().toString() : operationId;
            ExecRequest request = new ExecRequest().code(code, language).timeoutSeconds(timeoutSeconds);
            request.operationId(stableOperationId);
            ExecRecord record;
            try {
                record = client.execSessionAsync(sessionId, request);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw error;
            } catch (ApiException error) {
                if (!error.retryable()) throw error;
                throw new ExecRecoveryException(sessionId, stableOperationId, null, "submit", error);
            } catch (IOException error) {
                throw new ExecRecoveryException(sessionId, stableOperationId, null, "submit", error);
            }
            if (record.execId == null || record.execId.isBlank()) {
                throw new ExecRecoveryException(sessionId, stableOperationId, null, "submit",
                        new IOException("async exec response did not include exec_id"));
            }
            operationByExecId.put(record.execId, stableOperationId);
            return record.execId;
        });
    }

    /** Recover an execution receipt using its stable operation identity. */
    public CompletableFuture<ExecRecord> getExecByOperation(String operationId) {
        return supply(ioExecutor, () -> {
            ensureOpen();
            required(operationId, "operationId");
            try {
                ExecRecord record = client.getExecByOperation(sessionId, operationId);
                if (record.execId != null && !record.execId.isBlank()) {
                    operationByExecId.put(record.execId, operationId);
                }
                return record;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw error;
            } catch (ApiException error) {
                if (!error.retryable()) throw error;
                throw new ExecRecoveryException(sessionId, operationId, null, "lookup", error);
            } catch (IOException error) {
                throw new ExecRecoveryException(sessionId, operationId, null, "lookup", error);
            }
        });
    }

    public CompletableFuture<ExecResult> waitExec(String execId, long pollIntervalMs) {
        return waitExec(execId, pollIntervalMs, Duration.ofMinutes(5));
    }

    public CompletableFuture<ExecResult> waitExec(
            String execId, long pollIntervalMs, Duration maxWait) {
        return waitExec(execId, pollIntervalMs, maxWait, operationByExecId.get(execId));
    }

    public CompletableFuture<ExecResult> waitExec(
            String execId, long pollIntervalMs, Duration maxWait, String operationId) {
        if (pollIntervalMs <= 0 || maxWait == null || maxWait.isZero() || maxWait.isNegative()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("pollIntervalMs and maxWait must be positive"));
        }
        return supply(ioExecutor, () -> {
            long deadline = System.nanoTime() + maxWait.toNanos();
            while (System.nanoTime() < deadline) {
                ExecRecord record = observeExec(execId, operationId);
                if (record.operationId != null && !record.operationId.isBlank()) {
                    operationByExecId.put(execId, record.operationId);
                }
                if (record.terminal()) {
                    recordExecution(record);
                    return ExecResult.from(record);
                }
                Thread.sleep(pollIntervalMs);
            }
            throw new TimeoutException("exec " + execId + " did not complete within " + maxWait);
        });
    }

    private ExecRecord observeExec(String execId, String operationId)
            throws InterruptedException, IOException {
        int retries = 2;
        long delayMs = 200;
        for (int attempt = 0; ; attempt++) {
            try {
                return client.getExec(sessionId, execId);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw error;
            } catch (IOException error) {
                boolean retryable = error instanceof TransportException
                        || (error instanceof ApiException && ((ApiException) error).retryable());
                if (retryable && attempt < retries) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw interrupted;
                    }
                    delayMs = Math.min(delayMs * 2, 2_000);
                    continue;
                }
                if (error instanceof ApiException && !((ApiException) error).retryable()) {
                    throw error;
                }
                throw new ExecRecoveryException(sessionId, operationId, execId, "observe", error);
            }
        }
    }

    private static void ensureOperationId(ExecRequest request) {
        if (request == null) throw new IllegalArgumentException("request is required");
        if (request.operationId == null || request.operationId.isBlank()) {
            request.operationId(java.util.UUID.randomUUID().toString());
        }
    }

    public CompletableFuture<Void> close() {
        if (!closed.compareAndSet(false, true)) return CompletableFuture.completedFuture(null);
        if (renewTask != null) renewTask.cancel(true);
        if (scheduler != null) scheduler.shutdownNow();
        return run(ioExecutor, () -> client.deleteSession(sessionId))
                .whenComplete((ignored, error) -> {
                    if (error != null) closed.set(false);
                });
    }

    private void renew(int extendSeconds) {
        if (closed.get()) return;
        try {
            session = client.renewSession(sessionId, extendSeconds, Duration.ofSeconds(10));
        } catch (InterruptedException error) {
            if (renewTask != null) renewTask.cancel(false);
            if (scheduler != null) scheduler.shutdown();
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            // 未知续期结果必须先核对，不让后续定时tick继续叠加TTL。
            if (renewTask != null) renewTask.cancel(false);
            if (scheduler != null) scheduler.shutdown();
            LOG.log(Level.WARNING, "async session heartbeat failed for " + sessionId, error);
        }
    }

    private void recordExecution(ExecRecord record) {
        if (record.sandboxId != null && !record.sandboxId.isBlank()) {
            session.activeSandboxId = record.sandboxId;
        }
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("session is closed");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " cannot be blank");
        }
        return value;
    }

    private static <T> CompletableFuture<T> supply(
            ExecutorService executor, ThrowingSupplier<T> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        }, executor);
    }

    private static CompletableFuture<Void> run(
            ExecutorService executor, ThrowingRunnable runnable) {
        return CompletableFuture.runAsync(() -> {
            try {
                runnable.run();
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        }, executor);
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> { T get() throws Exception; }

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Exception; }
}
