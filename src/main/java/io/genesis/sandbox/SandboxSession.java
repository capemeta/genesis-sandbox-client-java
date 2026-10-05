package io.genesis.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.genesis.sandbox.SandboxModels.*;

/** High-level synchronous Session helper backed by the typed {@link Client}. */
public final class SandboxSession implements AutoCloseable {
    public static final int DEFAULT_SESSION_TTL = 300;
    public static final int DEFAULT_JOB_TIMEOUT = 60;
    public static final int DEFAULT_RENEW_INTERVAL = 30;
    public static final int DEFAULT_RENEW_EXTEND = 90;

    private static final Logger LOG = Logger.getLogger(SandboxSession.class.getName());

    private final Client client;
    private final String sessionId;
    private final String workspaceId;
    private final Map<String, String> operationByExecId = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler;
    private final ScheduledFuture<?> renewTask;
    private volatile Session session;

    private SandboxSession(Client client, Session session, SessionOptions options) {
        this.client = client;
        this.session = session;
        this.sessionId = required(session.sessionId, "session_id");
        this.workspaceId = required(session.workspaceId, "workspace_id");
        if (options.heartbeat && options.renewIntervalSeconds > 0 && options.renewExtendSeconds > 0) {
            scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "sandbox-session-renew-" + sessionId);
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

    public static SandboxSession open(Client client, String profile) throws Exception {
        return open(client, SessionOptions.builder().profile(profile).build());
    }

    public static SandboxSession open(Client client, String profile, String profileRevision) throws Exception {
        return open(client, SessionOptions.builder().profile(profile, profileRevision).build());
    }

    public static SandboxSession open(Client client, SessionOptions options) throws Exception {
        if (client == null || options == null) {
            throw new IllegalArgumentException("client and options are required");
        }
        Session session = client.createSession(options.toRequest());
        return new SandboxSession(client, session, options);
    }

    /** 只查询原会话，调用方核对结果后决定后续动作，不调用create。 */
    public Session lookupSession(String idempotencyKey) throws Exception {
        return client.lookupSession(idempotencyKey);
    }

    public static SandboxSession open(Client client, String profile, int ttlSeconds)
            throws Exception {
        return open(client, SessionOptions.builder().profile(profile).ttlSeconds(ttlSeconds).build());
    }

    public static SandboxSession open(Client client, String profile, int ttlSeconds,
                                      int renewIntervalSeconds, int renewExtendSeconds,
                                      boolean heartbeat) throws Exception {
        return open(client, SessionOptions.builder()
                .profile(profile)
                .ttlSeconds(ttlSeconds)
                .heartbeat(heartbeat, renewIntervalSeconds, renewExtendSeconds)
                .build());
    }

    public static SandboxSession open(Client client, String profile, String workspaceId,
                                      String workspaceRetention, int workspaceTtlSeconds,
                                      int ttlSeconds, int renewIntervalSeconds,
                                      int renewExtendSeconds, boolean heartbeat) throws Exception {
        SessionOptions.Builder builder = SessionOptions.builder()
                .profile(profile)
                .workspaceId(workspaceId)
                .ttlSeconds(ttlSeconds)
                .heartbeat(heartbeat, renewIntervalSeconds, renewExtendSeconds);
        if (workspaceRetention != null) {
            builder.retention(workspaceRetention, workspaceTtlSeconds);
        }
        return open(client, builder.build());
    }

    public static SandboxSession openWithHints(Client client, List<String> capabilities,
                                               boolean strict, String description,
                                               int ttlSeconds) throws Exception {
        return open(client, SessionOptions.builder()
                .hints(capabilities, strict, description)
                .ttlSeconds(ttlSeconds)
                .build());
    }

    public String getSessionId() { return sessionId; }
    public String getSandboxId() { return emptyIfNull(session.activeSandboxId); }
    public String workspaceId() { return workspaceId; }
    public Session getSession() { return session; }

    public ExecResult runPython(String code) throws Exception {
        return runPython(code, DEFAULT_JOB_TIMEOUT);
    }

    public ExecResult runPython(String code, int timeoutSeconds) throws Exception {
        return execute(new ExecRequest().code(code, "python").timeoutSeconds(timeoutSeconds));
    }

    public ExecResult runShell(String script) throws Exception {
        return runShell(script, DEFAULT_JOB_TIMEOUT);
    }

    public ExecResult runShell(String script, int timeoutSeconds) throws Exception {
        return execute(new ExecRequest()
                .command("sh", "-lc", required(script, "script"))
                .timeoutSeconds(timeoutSeconds));
    }

    public ExecResult runNode(String code) throws Exception {
        return runNode(code, DEFAULT_JOB_TIMEOUT);
    }

    public ExecResult runNode(String code, int timeoutSeconds) throws Exception {
        return execute(new ExecRequest().code(code, "javascript").timeoutSeconds(timeoutSeconds));
    }

    public ExecResult runCommand(String... command) throws Exception {
        return execute(new ExecRequest().command(command).timeoutSeconds(DEFAULT_JOB_TIMEOUT));
    }

    public ExecResult execute(ExecRequest request) throws Exception {
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
            throw new ExecRecoveryException(
                    sessionId, request.operationId, null, "submit", error);
        } catch (IOException error) {
            throw new ExecRecoveryException(
                    sessionId, request.operationId, null, "submit", error);
        }
        recordExecution(record);
        return ExecResult.from(record);
    }

    public WorkspaceFileInfo writeFile(String workspacePath, byte[] content) throws Exception {
        return writeFile(workspacePath, content, null, false);
    }

    public WorkspaceFileInfo writeFile(String workspacePath, byte[] content,
                                       String ifMatch, boolean createOnly) throws Exception {
        ensureOpen();
        return client.uploadSessionFile(sessionId, workspacePath, content, ifMatch, createOnly);
    }

    public byte[] readFile(String workspacePath) throws Exception {
        ensureOpen();
        return client.downloadSessionFile(sessionId, workspacePath);
    }

    public String readFileText(String workspacePath) throws Exception {
        return new String(readFile(workspacePath), StandardCharsets.UTF_8);
    }

    public WorkspaceFileList listFiles(String workspacePath, boolean recursive, Integer limit, Integer offset)
            throws Exception {
        ensureOpen();
        return client.listSessionFiles(sessionId, workspacePath, recursive, limit, offset);
    }

    public WorkspaceFileInfo statFile(String workspacePath) throws Exception {
        ensureOpen();
        return client.statSessionFile(sessionId, workspacePath);
    }

    public WorkspaceFileInfo mkdir(String workspacePath) throws Exception {
        ensureOpen();
        return client.mkdirSessionDir(sessionId, workspacePath);
    }

    public WorkspaceFileInfo setExecutable(String workspacePath, boolean executable,
                                           String ifMatch, Boolean expectedExecutable) throws Exception {
        ensureOpen();
        return client.setSessionFileExecutable(sessionId, workspacePath, executable, ifMatch, expectedExecutable);
    }

    public void remove(String workspacePath, boolean recursive) throws Exception {
        ensureOpen();
        client.removeSessionFile(sessionId, workspacePath, recursive);
    }

    public void remove(String workspacePath, boolean recursive, String ifMatch) throws Exception {
        ensureOpen(); client.removeSessionFile(sessionId, workspacePath, recursive, ifMatch);
    }

    /** Releases the runtime while retaining the logical Session and Workspace. */
    public Session suspend() throws Exception {
        ensureOpen();
        session = client.suspendSession(sessionId);
        return session;
    }

    /** Restores the short-lived runtime while preserving the logical Session and Workspace. */
    public Session resume() throws Exception {
        ensureOpen();
        session = client.resumeSession(sessionId);
        return session;
    }

    public String runAsync(String code, String language, int timeoutSeconds) throws Exception {
        return runAsync(code, language, timeoutSeconds, null);
    }

    /** Submit with a caller-retained operation identity for recovery after client restart. */
    public String runAsync(String code, String language, int timeoutSeconds,
                           String operationId) throws Exception {
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
            throw new ExecRecoveryException(
                    sessionId, stableOperationId, null, "submit", error);
        } catch (IOException error) {
            throw new ExecRecoveryException(
                    sessionId, stableOperationId, null, "submit", error);
        }
        if (record.execId == null || record.execId.isBlank()) {
            throw new ExecRecoveryException(sessionId, stableOperationId, null, "submit",
                    new IOException("async exec response did not include exec_id"));
        }
        operationByExecId.put(record.execId, stableOperationId);
        return record.execId;
    }

    /** Recover an execution receipt using its stable operation identity. */
    public ExecRecord getExecByOperation(String operationId) throws Exception {
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
    }

    public ExecResult waitExec(String execId, long pollIntervalMs) throws Exception {
        return waitExec(execId, pollIntervalMs, Duration.ofMinutes(5));
    }

    public ExecResult waitExec(String execId, long pollIntervalMs, Duration maxWait)
            throws Exception {
        return waitExec(execId, pollIntervalMs, maxWait, operationByExecId.get(execId));
    }

    public ExecResult waitExec(String execId, long pollIntervalMs, Duration maxWait,
                               String operationId) throws Exception {
        if (pollIntervalMs <= 0 || maxWait == null || maxWait.isZero() || maxWait.isNegative()) {
            throw new IllegalArgumentException("pollIntervalMs and maxWait must be positive");
        }
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
                throw new ExecRecoveryException(
                        sessionId, operationId, execId, "observe", error);
            }
        }
    }

    private static void ensureOperationId(ExecRequest request) {
        if (request == null) throw new IllegalArgumentException("request is required");
        if (request.operationId == null || request.operationId.isBlank()) {
            request.operationId(java.util.UUID.randomUUID().toString());
        }
    }

    @Override
    public void close() throws Exception {
        if (!closed.compareAndSet(false, true)) return;
        stopHeartbeat();
        try {
            client.deleteSession(sessionId);
        } catch (Exception error) {
            closed.set(false);
            throw error;
        }
    }

    private void renew(int extendSeconds) {
        if (closed.get()) return;
        try {
            session = client.renewSession(
                    sessionId, extendSeconds, Duration.ofSeconds(10));
        } catch (InterruptedException error) {
            stopHeartbeat();
            Thread.currentThread().interrupt();
        } catch (Exception error) {
            // 未知续期结果必须先核对，不让后续定时tick继续叠加TTL。
            stopHeartbeat();
            LOG.log(Level.WARNING, "session heartbeat failed for " + sessionId, error);
        }
    }

    private void recordExecution(ExecRecord record) {
        if (record.sandboxId != null && !record.sandboxId.isBlank()) {
            session.activeSandboxId = record.sandboxId;
        }
    }

    private void stopHeartbeat() {
        if (renewTask != null) renewTask.cancel(true);
        if (scheduler != null) scheduler.shutdownNow();
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

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}
