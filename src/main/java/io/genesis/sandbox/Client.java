package io.genesis.sandbox;

import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static io.genesis.sandbox.SandboxModels.*;

/**
 * Thread-safe Java 11 client for the Genesis Sandbox HTTP API.
 *
 * <p>JSON is an implementation detail: public methods accept and return typed models.
 * Binary artifacts and SSE streams stay streaming/binary.
 */
public final class Client {
    private final String baseUrl;
    private final String token;
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final int maxAttempts;
    private final Duration retryBaseDelay;

    public Client(String baseUrl, String token) {
        this(baseUrl, token, Duration.ofSeconds(30), 3, Duration.ofMillis(200));
    }

    public Client(String baseUrl, String token, Duration requestTimeout,
                  int maxAttempts, Duration retryBaseDelay) {
        URI parsed = URI.create(baseUrl);
        if (parsed.getHost() == null
                || (!"http".equals(parsed.getScheme()) && !"https".equals(parsed.getScheme()))) {
            throw new IllegalArgumentException("baseUrl must be an absolute http(s) URL");
        }
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token;
        this.requestTimeout = requestTimeout;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryBaseDelay = retryBaseDelay == null || retryBaseDelay.isNegative()
                ? Duration.ofMillis(200) : retryBaseDelay;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public RuntimeCapabilities getRuntimeCapabilities()
            throws IOException, InterruptedException {
        return sendJson("GET", "/v1/sandbox/capabilities", null, RuntimeCapabilities.class);
    }

    public List<RuntimeProfile> listRuntimeProfiles()
            throws IOException, InterruptedException {
        return sendJson("GET", "/v1/runtime-profiles", null,
                new TypeReference<List<RuntimeProfile>>() {});
    }

    public SandboxLease lease(LeaseRequest request) throws IOException, InterruptedException {
        return sendJson("POST", "/v1/sandboxes:lease", require(request, "request"), SandboxLease.class);
    }

    public void release(String sandboxId) throws IOException, InterruptedException {
        sendVoid("POST", "/v1/sandboxes/" + segment(sandboxId) + ":release", null);
    }

    public void destroy(String sandboxId) throws IOException, InterruptedException {
        sendVoid("DELETE", "/v1/sandboxes/" + segment(sandboxId), null);
    }

    public List<SandboxLease> listSandboxes() throws IOException, InterruptedException {
        return sendJson("GET", "/v1/sandboxes", null, new TypeReference<List<SandboxLease>>() {});
    }

    public SandboxLease getSandbox(String sandboxId) throws IOException, InterruptedException {
        return sendJson("GET", "/v1/sandboxes/" + segment(sandboxId), null, SandboxLease.class);
    }

    public SandboxLease renew(String sandboxId, int extendSeconds) throws IOException, InterruptedException {
        return sendJson("POST", "/v1/sandboxes/" + segment(sandboxId) + ":renew",
                new RenewRequest(extendSeconds), SandboxLease.class);
    }

    public JobResult submitJob(SubmitJobRequest request) throws IOException, InterruptedException {
        require(request, "request").validate();
        return sendJson("POST", "/v1/jobs", request, JobResult.class);
    }

    public JobResult waitJob(String jobId, long pollIntervalMs)
            throws IOException, InterruptedException, TimeoutException {
        return waitJob(jobId, pollIntervalMs, Duration.ofMinutes(5));
    }

    public JobResult waitJob(String jobId, long pollIntervalMs, Duration maxWait)
            throws IOException, InterruptedException, TimeoutException {
        validateWait(pollIntervalMs, maxWait);
        long deadline = System.nanoTime() + maxWait.toNanos();
        while (System.nanoTime() < deadline) {
            JobResult result = getJob(jobId);
            if (result.terminal()) return result;
            Thread.sleep(pollIntervalMs);
        }
        throw new TimeoutException("job " + jobId + " did not complete within " + maxWait);
    }

    public JobList listJobs(String status, Integer limit, Integer offset)
            throws IOException, InterruptedException {
        Query query = new Query("/v1/jobs")
                .add("status", status)
                .addPositive("limit", limit)
                .addPositive("offset", offset);
        return sendJson("GET", query.value(), null, JobList.class);
    }

    public JobResult getJob(String jobId) throws IOException, InterruptedException {
        return sendJson("GET", "/v1/jobs/" + segment(jobId), null, JobResult.class);
    }

    public void cancelJob(String jobId) throws IOException, InterruptedException {
        sendVoid("POST", "/v1/jobs/" + segment(jobId) + ":cancel", null);
    }

    public SandboxExecResult execSession(String sandboxId, ExecRequest request)
            throws IOException, InterruptedException {
        require(request, "request").validate();
        return sendJson("POST", "/v1/sandboxes/" + segment(sandboxId) + "/sessions",
                request, SandboxExecResult.class);
    }

    public Artifact uploadJobFile(String jobId, String name, byte[] content)
            throws IOException, InterruptedException {
        String path = "/v1/jobs/" + segment(jobId) + "/files?name=" + queryValue(name);
        byte[] response = sendBytes("POST", path, require(content, "content"),
                "application/octet-stream", null, false, requestTimeout, 1);
        return JsonCodec.read(response, Artifact.class);
    }

    public byte[] downloadArtifact(String artifactId) throws IOException, InterruptedException {
        return sendBytes("GET", "/v1/artifacts/" + segment(artifactId), null,
                null, null, true, requestTimeout, maxAttempts);
    }

    public List<Artifact> listJobArtifacts(String jobId, Integer offset, Integer limit)
            throws IOException, InterruptedException {
        Query query = new Query("/v1/jobs/" + segment(jobId) + "/artifacts")
                .addPositive("offset", offset)
                .addPositive("limit", limit);
        return sendJson("GET", query.value(), null, new TypeReference<List<Artifact>>() {});
    }

    public InputStream jobLogs(String jobId, int cursor) throws IOException, InterruptedException {
        Query query = new Query("/v1/jobs/" + segment(jobId) + "/logs");
        if (cursor > 0) query.add("cursor", Integer.toString(cursor));
        return openEventStream(query.value());
    }

    public SseStream jobLogEvents(String jobId, int cursor) throws IOException, InterruptedException {
        return new SseStream(jobLogs(jobId, cursor));
    }

    public ViewerDescriptor startGui(String sandboxId, StartGuiRequest request)
            throws IOException, InterruptedException {
        return sendJson("POST", "/v1/sandboxes/" + segment(sandboxId) + "/gui:start",
                request, ViewerDescriptor.class);
    }

    public void stopGui(String sandboxId) throws IOException, InterruptedException {
        sendVoid("POST", "/v1/sandboxes/" + segment(sandboxId) + "/gui:stop", null);
    }

    public ViewerDescriptor getViewer(String sandboxId) throws IOException, InterruptedException {
        return sendJson("GET", "/v1/sandboxes/" + segment(sandboxId) + "/viewer",
                null, ViewerDescriptor.class);
    }

    public ViewerDescriptor waitViewer(String sandboxId, String kind, Duration maxWait,
                                       Duration pollInterval)
            throws IOException, InterruptedException, TimeoutException {
        if (maxWait == null || maxWait.isZero() || maxWait.isNegative()
                || pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("maxWait and pollInterval must be positive");
        }
        long deadline = System.nanoTime() + maxWait.toNanos();
        ApiException lastError = null;
        while (System.nanoTime() < deadline) {
            try {
                ViewerDescriptor viewer = getViewer(sandboxId);
                if (viewer.ready && (kind == null || kind.equals(viewer.kind))) return viewer;
            } catch (ApiException error) {
                lastError = error;
            }
            Thread.sleep(pollInterval.toMillis());
        }
        TimeoutException timeout = new TimeoutException("viewer not ready within " + maxWait);
        if (lastError != null) timeout.initCause(lastError);
        throw timeout;
    }

    public SandboxLease patchSandbox(String sandboxId, Map<String, Object> metadataPatch,
                                     long resourceVersion)
            throws IOException, InterruptedException {
        Map<String, String> headers = Collections.singletonMap("If-Match", Long.toString(resourceVersion));
        return sendJson("PATCH", "/v1/sandboxes/" + segment(sandboxId) + "/metadata",
                require(metadataPatch, "metadataPatch"), headers, SandboxLease.class);
    }

    public Workspace createWorkspace(CreateWorkspaceRequest request)
            throws IOException, InterruptedException {
        require(request, "request").validate();
        return sendJson("POST", "/v1/workspaces", require(request, "request"), Workspace.class);
    }

    public WorkspaceModels.SharedStorageResource inspectSharedStorageResource(String storageRef, String resourceId)
            throws IOException, InterruptedException {
        WorkspaceModels.opaque(storageRef); WorkspaceModels.opaque(resourceId);
        byte[] body = sendBytes("GET", "/v1/storage-resources/" + segment(storageRef) + "/" + segment(resourceId),
                null, null, null, true, requestTimeout, maxAttempts);
        WorkspaceModels.SharedStorageResource result = JsonCodec.readStrict(body, WorkspaceModels.SharedStorageResource.class);
        if (result == null) throw new IOException("shared storage response is null");
        try { result.validate(storageRef, resourceId); }
        catch (IllegalArgumentException error) { throw new IOException("invalid shared storage response", error); }
        return result;
    }

    public WorkspaceModels.WorkspaceViewFacts getWorkspaceView(String sessionId) throws IOException, InterruptedException {
        return workspaceView("GET", sessionId, "");
    }

    public WorkspacePreparation.Receipt prepareWorkspaceView(String sessionId, WorkspacePreparation.Request request)
            throws IOException, InterruptedException {
        require(request, "request").validate();
        byte[] body = sendBytes("POST", "/v1/sessions/" + segment(sessionId) + "/workspace-view?action=prepare",
            JsonCodec.write(request), "application/json", null, false, requestTimeout, 1);
        return WorkspacePreparation.decode(body, sessionId, request);
    }

    public WorkspacePreparation.Receipt lookupWorkspacePreparation(String sessionId, WorkspacePreparation.Request request)
            throws IOException, InterruptedException {
        require(request, "request").validate();
        byte[] body;
        try {
            body = sendBytes("GET", "/v1/sessions/" + segment(sessionId) + "/workspace-view/preparations/"
                + segment(request.operationId) + "?request_digest=" + queryValue(request.requestDigest),
                null, null, null, true, requestTimeout, 1);
        } catch (ApiException error) {
            if (error.statusCode() == 404) return null;
            throw error;
        }
        return WorkspacePreparation.decode(body, sessionId, request);
    }

    public WorkspaceModels.WorkspaceViewFacts sealWorkspaceView(String sessionId) throws IOException, InterruptedException {
        return workspaceView("POST", sessionId, "?action=seal");
    }

    private WorkspaceModels.WorkspaceViewFacts workspaceView(String method, String sessionId, String query)
            throws IOException, InterruptedException {
        byte[] body = sendBytes(method, "/v1/sessions/" + segment(sessionId) + "/workspace-view" + query,
                null, null, null, "GET".equals(method), requestTimeout, "GET".equals(method) ? maxAttempts : 1);
        WorkspaceModels.WorkspaceViewFacts facts = JsonCodec.readStrict(body, WorkspaceModels.WorkspaceViewFacts.class);
        if (facts == null || !sessionId.equals(facts.sessionId) || blank(facts.workspaceId)
                || blank(facts.os) || blank(facts.arch) || blank(facts.viewState))
            throw new IOException("workspace view response missing bound identity or required facts");
        return facts;
    }

    public Session getSessionHistory(String sessionId) throws IOException, InterruptedException {
        byte[] body = sendBytes("GET", "/v1/sessions/" + segment(sessionId) + "/history",
                null, null, null, true, requestTimeout, maxAttempts);
        Session result = JsonCodec.readStrict(body, Session.class);
        if (result == null || !sessionId.equals(result.sessionId) || blank(result.status))
            throw new IOException("session history response identity mismatch");
        return result;
    }

    public WorkspacePurge.Receipt purgeSessionWorkspace(String sessionId, WorkspacePreparation.Request request) throws IOException, InterruptedException {
        request.validate();
        byte[] body = sendBytes("POST", "/v1/sessions/" + segment(sessionId) + "/workspace:purge",
                JsonCodec.write(request), "application/json", null, false, requestTimeout, 1);
        return WorkspacePurge.decode(body, sessionId, request);
    }

    public WorkspacePurge.Ownership getSessionOwnership(String sessionId) throws IOException, InterruptedException {
        return WorkspacePurge.ownership(sendBytes("GET","/v1/sessions/"+segment(sessionId)+"/ownership",
                null,"application/json",null,false,requestTimeout,1),sessionId);
    }

    public WorkspacePurge.Receipt lookupWorkspacePurge(String sessionId, WorkspacePreparation.Request request) throws IOException, InterruptedException {
        request.validate();
        try {
            byte[] body = sendBytes("GET", "/v1/sessions/" + segment(sessionId) + "/workspace/purges/"
                    + segment(request.operationId) + "?request_digest=" + request.requestDigest,
                    null, "application/json", null, false, requestTimeout, 1);
            return WorkspacePurge.decode(body, sessionId, request);
        } catch (ApiException error) {
            if (error.statusCode() == 404) return null;
            throw error;
        }
    }

    public Workspace getWorkspace(String workspaceId) throws IOException, InterruptedException {
        return sendJson("GET", "/v1/workspaces/" + segment(workspaceId), null, Workspace.class);
    }

    public WorkspacePurge.Receipt lookupStoragePurge(String workspaceId, WorkspacePreparation.Request request) throws IOException, InterruptedException {
        request.validate();
        try {
            byte[] body = sendBytes("GET", "/v1/workspaces/"+segment(workspaceId)+"/purges/"+segment(request.operationId)
                    +"?request_digest="+request.requestDigest,null,"application/json",null,false,requestTimeout,1);
            var receipt = WorkspacePurge.decode(body,null,request);
            if (!workspaceId.equals(receipt.workspaceId)) throw new IOException("Original storage purge workspace mismatch");
            return receipt;
        } catch (ApiException error) {
            if (error.statusCode() == 404) return null;
            throw error;
        }
    }
    public WorkspaceLifecycle.Receipt controlWorkspaceLifecycle(String workspaceId, WorkspaceLifecycle.Request request) throws IOException, InterruptedException {
        String digest = WorkspaceLifecycle.requestDigest(request);
        byte[] body = sendBytes("POST", "/v1/workspaces/" + segment(workspaceId) + "/lifecycle",
                JsonCodec.write(request), "application/json", null, false, requestTimeout, 1);
        var receipt = JsonCodec.readStrict(body, WorkspaceLifecycle.Receipt.class);
        if (receipt == null) throw new IOException("Missing lifecycle receipt");
        receipt.validate(workspaceId, request.operationId());
        if (!digest.equals(receipt.requestDigest()) || receipt.revision() != request.expectedRevision() + 1 || !request.state().equals(receipt.state())) throw new IOException("Lifecycle receipt differs from original intent");
        if ("terminal".equals(request.state()) && (!request.terminalAt().equals(receipt.terminalAt)
                || !request.terminalAt().plusSeconds(request.retentionSeconds()).equals(receipt.deadline)))
            throw new IOException("Lifecycle receipt differs from parent terminal time or retention");
        return receipt;
    }
    public WorkspaceLifecycle.Lookup lookupWorkspaceLifecycle(String workspaceId, String operationId) throws IOException, InterruptedException {
        WorkspaceLifecycle.identity(operationId);
        byte[] body = sendBytes("GET", "/v1/workspaces/" + segment(workspaceId) + "/lifecycle:lookup?operation_id=" + segment(operationId),
                null, null, null, true, requestTimeout, maxAttempts);
        var lookup = JsonCodec.readStrict(body, WorkspaceLifecycle.Lookup.class);
        if (lookup == null) throw new IOException("Missing lifecycle lookup");
        lookup.validate(workspaceId, operationId);
        return lookup;
    }

    public void deleteWorkspace(String workspaceId) throws IOException, InterruptedException {
        sendVoid("DELETE", "/v1/workspaces/" + segment(workspaceId), null);
    }

    public Session createSession(CreateSessionRequest request) throws IOException, InterruptedException {
        return sendJson("POST", "/v1/sessions", require(request, "request"), Session.class);
    }

    /** 404 仅表示未找到，不证明从未创建；此方法绝不自动重建会话。 */
    public Session lookupSession(String idempotencyKey) throws IOException, InterruptedException {
        String key = requireText(idempotencyKey, "idempotencyKey");
        if (key.length() > 256 || key.chars().anyMatch(value -> value < 32 || value == 127))
            throw new IllegalArgumentException("idempotency key must be a bounded stable identity");
        byte[] body;
        try {
            body = sendBytes("GET", "/v1/sessions:lookup?idempotency_key=" + queryValue(key),
                null, null, null, true, requestTimeout, maxAttempts);
        } catch (ApiException error) {
            if (error.statusCode() == 404) return null;
            throw error;
        }
        Session result = JsonCodec.readStrict(body, Session.class);
        if (result == null || !key.equals(result.idempotencyKey) || blank(result.sessionId)
                || blank(result.workspaceId) || blank(result.tenantId) || blank(result.runtimeProfile)
                || blank(result.statePolicy) || blank(result.status) || result.createdAt == null
                || result.expiresAt == null || result.resourceVersion < 1
                || result.resourceVersion > WorkspaceModels.MAX_SAFE_INTEGER)
            throw new IOException("session lookup response does not prove original identity");
        return result;
    }

    public Session getSession(String sessionId) throws IOException, InterruptedException {
        return sendJson("GET", "/v1/sessions/" + segment(sessionId), null, Session.class);
    }

    public void deleteSession(String sessionId) throws IOException, InterruptedException {
        sendVoid("DELETE", "/v1/sessions/" + segment(sessionId), null);
    }

    public Session suspendSession(String sessionId) throws IOException, InterruptedException {
        return sendJson("POST", "/v1/sessions/" + segment(sessionId) + ":suspend",
                Collections.emptyMap(), Session.class);
    }

    /** 恢复或核验运行态；结果未知时不自动再次恢复。 */
    public Session resumeSession(String sessionId) throws IOException, InterruptedException {
        byte[] response = sendBytes("POST", "/v1/sessions/" + segment(sessionId) + ":resume",
                null, "application/json", null, false, requestTimeout, 1);
        return JsonCodec.read(response, Session.class);
    }

    public Session renewSession(String sessionId, int extendSeconds)
            throws IOException, InterruptedException {
        return renewSession(sessionId, extendSeconds, Duration.ofSeconds(30));
    }

    /**
     * Renews once with a bounded timeout. Renewal is intentionally not retried because an
     * incremental TTL extension is not guaranteed to be idempotent.
     */
    public Session renewSession(String sessionId, int extendSeconds, Duration timeout)
            throws IOException, InterruptedException {
        return sendJson("POST", "/v1/sessions/" + segment(sessionId) + ":renew",
                new RenewRequest(extendSeconds), Session.class, timeout, 1);
    }

    public ExecRecord execNamedSession(String sessionId, ExecRequest request)
            throws IOException, InterruptedException {
        require(request, "request").validate();
        long executionSeconds = request.timeoutSeconds == null ? 180L : request.timeoutSeconds.longValue();
        Duration executionTimeout = Duration.ofSeconds(executionSeconds + 15L);
        Duration timeout = requestTimeout.compareTo(executionTimeout) >= 0
                ? requestTimeout : executionTimeout;
        return sendJson("POST", "/v1/sessions/" + segment(sessionId) + "/exec",
                request, ExecRecord.class, timeout, 1);
    }

    public ExecRecord execSessionAsync(String sessionId, ExecRequest request)
            throws IOException, InterruptedException {
        require(request, "request").validate();
        return sendJson("POST", "/v1/sessions/" + segment(sessionId) + "/exec:async",
                request, ExecRecord.class);
    }

    public ExecutionGovernance.Proof queryWorkspaceMaintenanceProof(String workspaceId, ExecutionGovernance.Query query)
            throws IOException, InterruptedException {
        require(query,"query").validate(workspaceId);
        byte[] body = sendBytes("POST", "/v1/workspaces/" + segment(workspaceId) + "/maintenance-proof:query",
                JsonCodec.write(query), "application/json", Collections.emptyMap(), false, requestTimeout, 1);
        return ExecutionGovernance.decode(body,query);
    }

    public ExecRecord getExec(String sessionId, String execId)
            throws IOException, InterruptedException {
        return sendJson("GET", "/v1/sessions/" + segment(sessionId)
                + "/execs/" + segment(execId), null, ExecRecord.class);
    }

    /** Looks up the durable execution receipt scoped to a Session and operation identity. */
    public ExecRecord getExecByOperation(String sessionId, String operationId)
            throws IOException, InterruptedException {
        Query query = new Query("/v1/sessions/" + segment(sessionId) + "/execs:lookup")
                .add("operation_id", operationId);
        requireText(operationId, "operationId");
        return ExecutionReceipts.record(sendJson("GET", query.value(), null, ExecRecord.class), sessionId, null, operationId);
    }

    public ExecRecordList listExecs(String sessionId, String status, Integer limit, String cursor)
            throws IOException, InterruptedException {
        if (limit != null && (limit < 1 || limit > 100)) throw new IllegalArgumentException("Execution page limit must be 1..100");
        if (cursor != null && cursor.isBlank()) throw new IllegalArgumentException("Execution page cursor cannot be blank");
        if (status != null) ExecutionReceipts.validateStatus(status);
        Query query = new Query("/v1/sessions/" + segment(sessionId) + "/execs")
                .add("status", status)
                .addPositive("limit", limit)
                .add("cursor", cursor);
        return ExecutionReceipts.page(sendJson("GET", query.value(), null, ExecRecordList.class), sessionId, limit);
    }

    public ExecRecord cancelExec(String sessionId, String execId)
            throws IOException, InterruptedException {
        return ExecutionReceipts.record(sendJson("POST", "/v1/sessions/" + segment(sessionId)
                        + "/execs/" + segment(execId) + ":cancel",
                Collections.emptyMap(), ExecRecord.class), sessionId, execId, null);
    }

    public InputStream streamExecLogs(String sessionId, String execId, int cursor)
            throws IOException, InterruptedException {
        Query query = new Query("/v1/sessions/" + segment(sessionId)
                + "/execs/" + segment(execId) + "/logs");
        if (cursor > 0) query.add("cursor", Integer.toString(cursor));
        return openEventStream(query.value());
    }

    public SseStream execLogEvents(String sessionId, String execId, int cursor)
            throws IOException, InterruptedException {
        return new SseStream(streamExecLogs(sessionId, execId, cursor));
    }

    public SessionContext getSessionContext(String sessionId)
            throws IOException, InterruptedException {
        return sendJson("GET", "/v1/sessions/" + segment(sessionId) + "/context",
                null, SessionContext.class);
    }

    public SessionContext patchSessionContext(String sessionId, SessionContext patch)
            throws IOException, InterruptedException {
        return sendJson("PATCH", "/v1/sessions/" + segment(sessionId) + "/context",
                require(patch, "patch"), SessionContext.class);
    }

    public EnvironmentCatalog getCatalog(String capability, String tag,
                                         Integer limit, Integer offset)
            throws IOException, InterruptedException {
        Query query = new Query("/v1/environment/catalog")
                .add("capability", capability)
                .add("tag", tag)
                .addPositive("limit", limit)
                .addPositive("offset", offset);
        return sendJson("GET", query.value(), null, EnvironmentCatalog.class);
    }

    public EnvironmentResolution resolveEnvironment(ResolveEnvironmentRequest request)
            throws IOException, InterruptedException {
        require(require(request, "request").environment, "request.environment");
        return sendJson("POST", "/v1/environment:resolve",
                request, EnvironmentResolution.class);
    }

    public WorkspaceFileInfo uploadSessionFile(String sessionId, String workspacePath,
                                               byte[] content)
            throws IOException, InterruptedException {
        return uploadSessionFile(sessionId, workspacePath, content, null, false);
    }

    public WorkspaceFileInfo uploadSessionFile(String sessionId, String workspacePath,
                                               byte[] content, String ifMatch,
                                               boolean createOnly)
            throws IOException, InterruptedException {
        String path = "/v1/sessions/" + segment(sessionId)
                + "/files?path=" + queryValue(workspacePath);
        Map<String, String> headers = new LinkedHashMap<>();
        if (ifMatch != null && !ifMatch.isBlank()) headers.put("If-Match", ifMatch);
        if (createOnly) headers.put("If-None-Match", "*");
        byte[] response = sendBytes("PUT", path, require(content, "content"),
                "application/octet-stream", headers, false, requestTimeout, 1);
        return JsonCodec.read(response, WorkspaceFileInfo.class);
    }

    public byte[] downloadSessionFile(String sessionId, String workspacePath)
            throws IOException, InterruptedException {
        String path = "/v1/sessions/" + segment(sessionId)
                + "/files?path=" + queryValue(workspacePath);
        return sendBytes("GET", path, null, null, null, true, requestTimeout, maxAttempts);
    }

    public WorkspaceFileList listSessionFiles(String sessionId, String workspacePath,
                                              boolean recursive, Integer limit, Integer offset)
            throws IOException, InterruptedException {
        Query query = new Query("/v1/sessions/" + segment(sessionId) + "/files:list")
                .add("path", workspacePath)
                .add("recursive", Boolean.toString(recursive))
                .addPositive("limit", limit)
                .addPositive("offset", offset);
        return sendJson("GET", query.value(), null, WorkspaceFileList.class);
    }

    public WorkspaceFileInfo statSessionFile(String sessionId, String workspacePath)
            throws IOException, InterruptedException {
        String path = "/v1/sessions/" + segment(sessionId)
                + "/files:stat?path=" + queryValue(workspacePath);
        return sendJson("GET", path, null, WorkspaceFileInfo.class);
    }

    public WorkspaceFileInfo mkdirSessionDir(String sessionId, String workspacePath)
            throws IOException, InterruptedException {
        String path = "/v1/sessions/" + segment(sessionId)
                + "/dirs?path=" + queryValue(workspacePath);
        return sendJson("POST", path, null, WorkspaceFileInfo.class);
    }

    public WorkspaceFileInfo setSessionFileExecutable(String sessionId, String workspacePath,
            boolean executable, String ifMatch, Boolean expectedExecutable) throws IOException, InterruptedException {
        Map<String, String> headers = new LinkedHashMap<>();
        if (ifMatch != null) {
            WorkspaceModels.digest(ifMatch);
            headers.put("If-Match", ifMatch);
        }
        String path = "/v1/sessions/" + segment(sessionId) + "/files:executable?path=" + queryValue(workspacePath);
        byte[] body = sendBytes("PATCH", path, JsonCodec.write(new WorkspaceModels.ExecutableRequest(executable, expectedExecutable)),
                "application/json", headers, false, requestTimeout, 1);
        WorkspaceFileInfo result = JsonCodec.readStrict(body, WorkspaceFileInfo.class);
        if (result == null || result.executable == null || result.executable != executable)
            throw new IOException("executable permission result not confirmed");
        return result;
    }

    public void removeSessionFile(String sessionId, String workspacePath, boolean recursive)
            throws IOException, InterruptedException {
        removeSessionFile(sessionId, workspacePath, recursive, null);
    }

    public void removeSessionFile(String sessionId, String workspacePath, boolean recursive, String ifMatch)
            throws IOException, InterruptedException {
        Query query = new Query("/v1/sessions/" + segment(sessionId) + "/files")
                .add("path", workspacePath)
                .add("recursive", Boolean.toString(recursive));
        Map<String, String> headers = new LinkedHashMap<>();
        if (ifMatch != null) { WorkspaceModels.digest(ifMatch); headers.put("If-Match", ifMatch); }
        // 删除后的未知结果只交给调用方核对，绝不重复删除可能已经被重建的目标。
        sendBytes("DELETE", query.value(), null, null, headers, false, requestTimeout, 1);
    }

    public DependencyBuild buildDependencies(BuildDependencyRequest request)
            throws IOException, InterruptedException {
        require(request, "request");
        boolean hasResolution = request.resolutionId != null && !request.resolutionId.isBlank();
        if ((request.environment != null) == hasResolution) {
            throw new IllegalArgumentException(
                    "exactly one of request.environment or request.resolutionId is required");
        }
        if (request.environment != null
                && (request.environment.profile == null
                || request.environment.hints != null
                || request.environment.profile.name == null
                || request.environment.profile.name.isBlank()
                || request.environment.profile.revision == null
                || request.environment.profile.revision.isBlank())) {
            throw new IllegalArgumentException(
                    "request.environment must contain an exact profile name and revision");
        }
        return sendJson("POST", "/v1/dependencies:build",
                request, DependencyBuild.class);
    }

    public DependencyBuild getDependencyBuild(String fingerprint)
            throws IOException, InterruptedException {
        return sendJson("GET", "/v1/dependencies/" + segment(fingerprint),
                null, DependencyBuild.class);
    }

    public AuditEventList listAuditEvents(String principalId, String keyId, String action,
                                          String resourceType, String resourceId,
                                          Integer limit, Integer offset)
            throws IOException, InterruptedException {
        Query query = new Query("/v1/audits")
                .add("principal_id", principalId)
                .add("key_id", keyId)
                .add("action", action)
                .add("resource_type", resourceType)
                .add("resource_id", resourceId)
                .addPositive("limit", limit)
                .addPositive("offset", offset);
        return sendJson("GET", query.value(), null, AuditEventList.class);
    }

    /** Snapshot metadata is currently free-form in OpenAPI and therefore represented as a map. */
    public Map<String, Object> createSnapshot(String sandboxId)
            throws IOException, InterruptedException {
        return sendJson("POST", "/v1/sandboxes/" + segment(sandboxId) + "/snapshots",
                null, new TypeReference<Map<String, Object>>() {});
    }

    public SandboxLease restoreSnapshot(String snapshotId)
            throws IOException, InterruptedException {
        return sendJson("POST", "/v1/snapshots/" + segment(snapshotId) + "/restore",
                null, SandboxLease.class);
    }

    private <T> T sendJson(String method, String path, Object body, Class<T> responseType)
            throws IOException, InterruptedException {
        return sendJson(method, path, body, Collections.emptyMap(), responseType);
    }

    private <T> T sendJson(String method, String path, Object body,
                           Map<String, String> headers, Class<T> responseType)
            throws IOException, InterruptedException {
        return sendJson(method, path, body, headers, responseType, requestTimeout, maxAttempts);
    }

    private <T> T sendJson(String method, String path, Object body, Class<T> responseType,
                           Duration timeout, int attempts)
            throws IOException, InterruptedException {
        return sendJson(method, path, body, Collections.emptyMap(), responseType, timeout, attempts);
    }

    private <T> T sendJson(String method, String path, Object body,
                           Map<String, String> headers, Class<T> responseType,
                           Duration timeout, int attempts)
            throws IOException, InterruptedException {
        byte[] payload = body == null ? null : JsonCodec.write(body);
        boolean retryableRequest = isReadOnly(method);
        byte[] response = sendBytes(method, path, payload, "application/json", headers,
                retryableRequest, timeout, attempts);
        return response.length == 0 ? null : JsonCodec.read(response, responseType);
    }

    private <T> T sendJson(String method, String path, Object body, TypeReference<T> responseType)
            throws IOException, InterruptedException {
        byte[] payload = body == null ? null : JsonCodec.write(body);
        byte[] response = sendBytes(method, path, payload, "application/json", null,
                isReadOnly(method), requestTimeout, maxAttempts);
        return JsonCodec.read(response, responseType);
    }

    private void sendVoid(String method, String path, Object body)
            throws IOException, InterruptedException {
        byte[] payload = body == null ? null : JsonCodec.write(body);
        sendBytes(method, path, payload, "application/json", null,
                isReadOnly(method), requestTimeout, maxAttempts);
    }

    private byte[] sendBytes(String method, String path, byte[] data, String contentType,
                             Map<String, String> extraHeaders, boolean retryableRequest,
                             Duration timeout, int configuredAttempts)
            throws IOException, InterruptedException {
        // 幂等键只用于查询原操作，不构成未知副作用自动重发的许可。
        int attempts = retryableRequest && isReadOnly(method) ? Math.max(1, configuredAttempts) : 1;
        long delayMs = retryBaseDelay.toMillis();
        IOException lastError = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("request interrupted");
            try {
                HttpRequest.Builder builder = baseRequest(path).timeout(timeout);
                if (contentType != null) builder.header("Content-Type", contentType);
                if (extraHeaders != null) extraHeaders.forEach(builder::header);
                HttpRequest.BodyPublisher publisher = data == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(data);
                HttpResponse<byte[]> response = httpClient.send(
                        builder.method(method, publisher).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status >= 200 && status < 300) return response.body();
                ApiException error = new ApiException(status, response.body(), retryAfter(response));
                if (error.retryable() && attempt + 1 < attempts) {
                    sleepRetry(error.retryAfter(), delayMs);
                    delayMs = doubled(delayMs);
                    continue;
                }
                throw error;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw error;
            } catch (ApiException error) {
                throw error;
            } catch (IOException error) {
                lastError = error;
                if (attempt + 1 < attempts) {
                    sleepRetry(Duration.ZERO, delayMs);
                    delayMs = doubled(delayMs);
                    continue;
                }
            }
        }
        throw new TransportException("sandbox request failed after " + attempts + " attempts", lastError);
    }

    private InputStream openEventStream(String path) throws IOException, InterruptedException {
        long delayMs = retryBaseDelay.toMillis();
        IOException lastError = null;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                HttpResponse<InputStream> response = httpClient.send(
                        baseRequest(path).header("Accept", "text/event-stream").GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() >= 200 && response.statusCode() < 300) return response.body();
                try (InputStream body = response.body()) {
                    byte[] payload = body.readNBytes(64 * 1024);
                    ApiException error = new ApiException(
                            response.statusCode(), payload, retryAfter(response));
                    if (error.retryable() && attempt + 1 < maxAttempts) {
                        sleepRetry(error.retryAfter(), delayMs);
                        delayMs = doubled(delayMs);
                        continue;
                    }
                    throw error;
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw error;
            } catch (ApiException error) {
                throw error;
            } catch (IOException error) {
                lastError = error;
                if (attempt + 1 < maxAttempts) {
                    sleepRetry(Duration.ZERO, delayMs);
                    delayMs = doubled(delayMs);
                    continue;
                }
            }
        }
        throw new TransportException("open sandbox event stream failed", lastError);
    }

    private HttpRequest.Builder baseRequest(String path) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Accept", "application/json")
                .header("User-Agent", "genesis-sandbox-java/0.3.0");
        if (token != null && !token.isEmpty()) builder.header("Authorization", "Bearer " + token);
        return builder;
    }

    private static boolean isReadOnly(String method) {
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }

    private static Duration retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After").map(value -> {
            try {
                return Duration.ofSeconds(Math.max(0L, Long.parseLong(value)));
            } catch (NumberFormatException ignored) {
                try {
                    Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                    Duration duration = Duration.between(Instant.now(), at);
                    return duration.isNegative() ? Duration.ZERO : duration;
                } catch (DateTimeParseException alsoIgnored) {
                    return Duration.ZERO;
                }
            }
        }).orElse(Duration.ZERO);
    }

    private static void sleepRetry(Duration retryAfter, long fallbackMs)
            throws InterruptedException {
        long delay = retryAfter == null || retryAfter.isZero()
                ? Math.max(0L, fallbackMs) : retryAfter.toMillis();
        try {
            Thread.sleep(delay);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw error;
        }
    }

    private static long doubled(long value) {
        return value > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : value * 2;
    }

    private static void validateWait(long pollIntervalMs, Duration maxWait) {
        if (pollIntervalMs <= 0 || maxWait == null || maxWait.isZero() || maxWait.isNegative()) {
            throw new IllegalArgumentException("pollIntervalMs and maxWait must be positive");
        }
    }

    private static String segment(String value) {
        return percentEncode(requireText(value, "path value"));
    }

    private static String queryValue(String value) {
        return percentEncode(requireText(value, "query value"));
    }

    private static String percentEncode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder result = new StringBuilder(bytes.length);
        for (byte raw : bytes) {
            int c = raw & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~') {
                result.append((char) c);
            } else {
                result.append('%');
                result.append(Character.toUpperCase(Character.forDigit((c >>> 4) & 0xf, 16)));
                result.append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return result.toString();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String requireText(String value, String name) {
        if (blank(value)) throw new IllegalArgumentException(name + " cannot be blank");
        return value;
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static final class RenewRequest {
        public int extendSeconds;
        private RenewRequest(int value) {
            if (value <= 0) throw new IllegalArgumentException("extendSeconds must be positive");
            extendSeconds = value;
        }
    }

    private static final class Query {
        private final StringBuilder value;
        private boolean first = true;
        private Query(String path) { value = new StringBuilder(path); }
        private Query add(String name, String item) {
            if (!blank(item)) {
                value.append(first ? '?' : '&').append(name).append('=').append(queryValue(item));
                first = false;
            }
            return this;
        }
        private Query addPositive(String name, Integer item) {
            return item != null && item > 0 ? add(name, item.toString()) : this;
        }
        private String value() { return value.toString(); }
    }
}
