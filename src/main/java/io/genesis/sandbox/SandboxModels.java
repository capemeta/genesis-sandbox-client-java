package io.genesis.sandbox;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strongly typed wire models for {@link Client}.
 *
 * <p>The classes deliberately do not expose Jackson types. Public fields keep Java 11 usage
 * concise and allow applications to construct uncommon advanced requests without SDK churn.
 */
public final class SandboxModels {
    private SandboxModels() {}

    public static final class ProfileReference {
        public String name;
        public String revision;
        public ProfileReference() {}
        public ProfileReference(String name) { this.name = requireText(name, "profile name"); }
        public ProfileReference revision(String value) { revision = blankToNull(value); return this; }
    }

    public static final class EnvironmentHints {
        public List<String> capabilities = new ArrayList<>();
        public boolean strict;
        public String description;
        public EnvironmentHints() {}
        public EnvironmentHints(List<String> values) {
            if (values == null || values.isEmpty()) throw new IllegalArgumentException("capabilities cannot be empty");
            capabilities.addAll(values);
        }
        public EnvironmentHints strict(boolean value) { strict = value; return this; }
        public EnvironmentHints description(String value) { description = blankToNull(value); return this; }
    }

    public static final class EnvironmentSelector {
        public ProfileReference profile;
        public EnvironmentHints hints;
        public EnvironmentSelector() {}
        public static EnvironmentSelector profile(String name) {
            EnvironmentSelector value = new EnvironmentSelector();
            value.profile = new ProfileReference(name);
            return value;
        }
        public static EnvironmentSelector exactProfile(String name, String revision) {
            EnvironmentSelector value = profile(name);
            value.profile.revision = requireText(revision, "profile revision");
            return value;
        }
        public static EnvironmentSelector defaults() {
            EnvironmentSelector value = new EnvironmentSelector();
            value.hints = new EnvironmentHints();
            return value;
        }
        public static EnvironmentSelector hints(List<String> capabilities, boolean strict, String description) {
            EnvironmentSelector value = new EnvironmentSelector();
            value.hints = new EnvironmentHints(capabilities).strict(strict).description(description);
            return value;
        }
    }

    public static final class ResolveEnvironmentRequest {
        public EnvironmentSelector environment;
        public Integer ttlSeconds;
        public ResolveEnvironmentRequest() {}
        public ResolveEnvironmentRequest(EnvironmentSelector environment) {
            this.environment = require(environment, "environment");
        }
        public ResolveEnvironmentRequest ttlSeconds(int value) {
            if (value < 60) throw new IllegalArgumentException("ttlSeconds must be at least 60");
            ttlSeconds = value; return this;
        }
    }

    public static final class EnvironmentResolution {
        public String resolutionId;
        public String profileName;
        public String profileRevision;
        public String selectionMode;
        public List<String> selectionReason = new ArrayList<>();
        public List<String> capabilities = new ArrayList<>();
        public boolean degraded;
        public Instant expiresAt;
    }

    public static final class EnvironmentCatalog {
        public List<CatalogCard> items = new ArrayList<>();
        public List<String> capabilityVocabulary = new ArrayList<>();
        public String defaultProfile;
        public int nextOffset;
        public int total;
    }

    public static final class CatalogCard {
        public String name;
        public String profileRevision;
        public boolean revisionAvailable;
        public String displayName;
        public String description;
        public List<String> capabilities = new ArrayList<>();
        public List<String> tags = new ArrayList<>();
        public List<String> useWhen = new ArrayList<>();
        public List<String> avoidWhen = new ArrayList<>();
        public CatalogCardLimits limits;
        public CatalogCardFeatures features;
    }

    public static final class CatalogCardLimits {
        public int maxExecTimeoutSeconds;
        public int maxSessionTtlSeconds;
        public int workspaceQuotaMb;
        public long maxLogBytes;
        public int maxConcurrentExecs;
    }

    public static final class CatalogCardFeatures {
        public boolean viewer;
        public boolean suspendResume;
    }

    public static final class RuntimeCapabilities {
        public boolean available;
        public String runtimeKind;
        public String defaultRuntime;
        public List<String> availableRuntimes = new ArrayList<>();
        public List<RuntimeProfile> runtimeProfiles = new ArrayList<>();
        public List<String> supportedTaskTypes = new ArrayList<>();
        public List<String> supportedLanguages = new ArrayList<>();
        public List<String> supportedStatePolicies = new ArrayList<>();
        public QueueCapabilities queue;
        public List<QueueSnapshot> queueSnapshots = new ArrayList<>();
        public ExecdCapabilities execd;
    }

    public static final class RuntimeProfile {
        public String name;
        public String image;
        public String runtime;
        public PlatformSpec platform;
        public List<String> taskTypes = new ArrayList<>();
        public List<String> languages = new ArrayList<>();
        public List<String> operations = new ArrayList<>();
        public boolean reusable;
        public ViewerPolicy viewer;
    }

    public static final class PlatformSpec {
        public String os;
        public String architecture;
        public String variant;
    }

    public static final class ViewerPolicy {
        public boolean supported;
        public String defaultKind;
        public List<String> allowedKinds = new ArrayList<>();
        public int port;
        public int tokenTtlSeconds;
        public boolean renewOnAccess;
        public List<String> artifacts = new ArrayList<>();
    }

    public static final class QueueSnapshot {
        public String poolKey;
        public int active;
        public int queued;
    }

    public static final class QueueCapabilities {
        public int maxWaiting;
        public int defaultWaitSeconds;
        public int maxWaitSeconds;
    }

    public static final class ExecdCapabilities {
        public boolean command;
        public boolean session;
        public boolean files;
        public boolean metrics;
    }

    public static final class SubmitJobRequest {
        public EnvironmentSelector environment;
        public String resolutionId;
        public String code;
        public List<String> command;
        public Integer timeoutSeconds;
        public Integer queueWaitTimeoutSeconds;
        public String workspaceId;
        public String sessionId;
        public String idempotencyKey;
        public URI callbackUrl;
        public List<String> inputArtifactIds = new ArrayList<>();
        public Map<String, String> env = new LinkedHashMap<>();
        public Map<String, String> metadata = new LinkedHashMap<>();

        public SubmitJobRequest code(String value) { code = requireText(value, "code"); command = null; return this; }
        public SubmitJobRequest command(String... value) { command = commandList(value); code = null; return this; }
        public SubmitJobRequest environment(EnvironmentSelector value) { environment = value; resolutionId = null; return this; }
        public SubmitJobRequest resolutionId(String value) { resolutionId = requireText(value, "resolutionId"); environment = null; return this; }
        public SubmitJobRequest timeoutSeconds(int value) { timeoutSeconds = positive(value, "timeoutSeconds"); return this; }
        public SubmitJobRequest idempotencyKey(String value) { idempotencyKey = requireText(value, "idempotencyKey"); return this; }
        public SubmitJobRequest inputArtifact(String id) { inputArtifactIds.add(requireText(id, "artifact id")); return this; }
        public SubmitJobRequest env(String key, String value) { env.put(requireText(key, "env key"), value); return this; }
        public SubmitJobRequest metadata(String key, String value) { metadata.put(requireText(key, "metadata key"), value); return this; }
        void validate() { exactlyOne(code, command, "job"); }
    }

    public static final class JobResult {
        public String jobId;
        public String sandboxId;
        public String tenantId;
        public String workspaceId;
        public String taskType;
        public String operation;
        public String status;
        public int exitCode;
        public String stdout;
        public String stderr;
        public String errorCode;
        public String error;
        public long durationMs;
        public boolean stdoutTruncated;
        public boolean stderrTruncated;
        public String logsUrl;
        public String idempotencyKey;
        public Map<String, Object> effectivePolicy = new LinkedHashMap<>();
        public EffectiveEnvironment effectiveEnvironment;
        public List<Artifact> outputArtifacts = new ArrayList<>();
        public boolean terminal() {
            return "succeeded".equals(status) || "failed".equals(status)
                    || "cancelled".equals(status) || "timed_out".equals(status)
                    || "interrupted".equals(status);
        }
    }

    public static final class JobList {
        public List<JobResult> items = new ArrayList<>();
        public int nextOffset;
        public int total;
    }

    public static final class Artifact {
        public String artifactId;
        public String tenantId;
        public String workspaceId;
        public String jobId;
        public String name;
        public long size;
        public String sha256;
        public String mime;
        public Instant createdAt;
    }

    public static final class CreateWorkspaceRequest {
        public WorkspaceModels.WorkspaceBinding workspaceBinding;
        public String workspaceId;
        public String retentionMode;
        public Integer ttlSeconds;
        public Integer quotaMb;
        public Map<String, String> metadata = new LinkedHashMap<>();
        public CreateWorkspaceRequest workspaceId(String value) { workspaceId = blankToNull(value); return this; }
        public CreateWorkspaceRequest workspaceBinding(WorkspaceModels.WorkspaceBinding value) {
            require(value, "workspaceBinding").validate(); workspaceBinding = value; return this;
        }
        public CreateWorkspaceRequest retention(String mode, Integer ttl) {
            if (!"ttl".equals(mode) && !"explicit_delete".equals(mode)) {
                throw new IllegalArgumentException("retentionMode must be ttl or explicit_delete");
            }
            retentionMode = mode; ttlSeconds = ttl; return this;
        }
        void validate() {
            if (workspaceBinding != null) workspaceBinding.validate();
            if (retentionMode != null && !"ttl".equals(retentionMode) && !"explicit_delete".equals(retentionMode))
                throw new IllegalArgumentException("invalid workspace retention mode");
            if (ttlSeconds != null && ttlSeconds <= 0 || quotaMb != null && quotaMb <= 0)
                throw new IllegalArgumentException("workspace budgets must be positive");
        }
    }

    public static final class Workspace {
        public long lifecycleRevision;
        public String workspaceId;
        public String tenantId;
        public String userId;
        public String retentionMode;
        public Instant expiresAt;
        public int quotaMb;
        public Instant createdAt;
        public Map<String, String> metadata = new LinkedHashMap<>();
    }

    public static final class CreateSessionRequest {
        public String workspaceId;
        public EnvironmentSelector environment;
        public String resolutionId;
        public String idempotencyKey;
        public String statePolicy = "session";
        public Integer ttlSeconds;
        public String workspaceRetention;
        public Integer workspaceTtlSeconds;
        public Map<String, String> metadata = new LinkedHashMap<>();
        public CreateSessionRequest environment(EnvironmentSelector value) { environment = value; resolutionId = null; return this; }
        public CreateSessionRequest resolutionId(String value) { resolutionId = requireText(value, "resolutionId"); environment = null; return this; }
        public CreateSessionRequest workspaceId(String value) { workspaceId = blankToNull(value); return this; }
        public CreateSessionRequest ttlSeconds(int value) { ttlSeconds = positive(value, "ttlSeconds"); return this; }
        public CreateSessionRequest idempotencyKey(String value) { idempotencyKey = blankToNull(value); return this; }
    }

    public static final class Session {
        public String defaultCwd;
        public Map<String, String> metadata = new LinkedHashMap<>();
        public String sessionId;
        public String tenantId;
        public String userId;
        public String workspaceId;
        public String runtimeProfile;
        public String profileRevision;
        public EffectiveEnvironment effectiveEnvironment;
        public String statePolicy;
        public String activeSandboxId;
        public String status;
        public String idempotencyKey;
        public Instant createdAt;
        public Instant expiresAt;
        public long resourceVersion;
    }

    public static final class ExecRequest {
        public ExecutionGovernance.Trusted trustedGovernance;
        public ExecRequest trustedGovernance(ExecutionGovernance.Trusted value) { value.validate(); trustedGovernance=value; return this; }
        public String operationId = java.util.UUID.randomUUID().toString();
        public ExecRequest operationId(String value) { operationId = requireText(value, "operationId"); return this; }
        public List<String> command;
        public String code;
        public String language;
        public String workingDir;
        public String outputDirectory;
        public Map<String, String> env = new LinkedHashMap<>();
        public Integer timeoutSeconds;
        public URI callbackUrl;
        public ExecRequest code(String value, String language) {
            code = requireText(value, "code"); this.language = blankToNull(language); command = null; return this;
        }
        public ExecRequest command(String... value) { command = commandList(value); code = null; return this; }
        public ExecRequest workingDir(String value) { workingDir = blankToNull(value); return this; }
        public ExecRequest outputDirectory(String value) { outputDirectory = ExecutionOutputDirectory.validate(value); return this; }
        public ExecRequest env(String key, String value) { env.put(requireText(key, "env key"), value); return this; }
        public ExecRequest timeoutSeconds(int value) { timeoutSeconds = positive(value, "timeoutSeconds"); return this; }
        void validate() { exactlyOne(code, command, "exec"); ExecutionOutputDirectory.validate(outputDirectory); if (trustedGovernance != null) trustedGovernance.validate(); }
    }

    public static final class ExecRecord {
		public Boolean stopConfirmed;
		public String tenantId;
		public String userId;
        public String operationId;
        public String execId;
        public String sessionId;
        public String sandboxId;
        public String workspaceId;
        public String status;
        public int exitCode;
        public String errorCode;
        public String stdout;
        public String stderr;
        public boolean stdoutTruncated;
        public boolean stderrTruncated;
        public String workingDir;
        public EffectiveEnvironment effectiveEnvironment;
        public String logsUrl;
        public long durationMs;
        public Instant createdAt;
        public Instant startedAt;
        public Instant finishedAt;
        public boolean terminal() {
            return "succeeded".equals(status) || "failed".equals(status)
                    || "cancelled".equals(status) || "timed_out".equals(status)
                    || "interrupted".equals(status);
        }
    }

    public static final class SandboxExecResult {
        public String execId;
        public String status;
        public String errorCode;
        public int exitCode;
        public String stdout;
        public String stderr;
        public boolean stdoutTruncated;
        public boolean stderrTruncated;
        public String environment;
        public String sessionId;
        public String workspaceId;
        public String sandboxId;
        public String cwd;
        public EffectiveEnvironment effectiveEnvironment;
        public boolean terminal() {
            return "succeeded".equals(status) || "failed".equals(status)
                    || "cancelled".equals(status) || "timed_out".equals(status)
                    || "interrupted".equals(status);
        }
    }

    public static final class ExecRecordList {
        public List<ExecRecord> items = new ArrayList<>();
        public String nextCursor;
        public int total;
    }

    public static final class SessionContext {
        public String cwd;
        public SessionContext() {}
        public SessionContext cwd(String value) { cwd = blankToNull(value); return this; }
    }

    public static final class WorkspaceFileInfo {
        public Boolean executable;
        public String path;
        public String sandboxPath;
        public String environment;
        public String name;
        public String kind;
        public long size;
        public String sha256;
        public String mime;
        public Instant modTime;
    }

    public static final class WorkspaceFileList {
        public String path;
        public List<WorkspaceFileInfo> entries = new ArrayList<>();
        public boolean truncated;
        public int limit;
        public int offset;
    }

    public static final class BuildDependencyRequest {
        public EnvironmentSelector environment;
        public String resolutionId;
        public String language;
        public String manifest;
        public String lockfile;
        public List<String> packages = new ArrayList<>();
    }

    public static final class DependencyBuild {
        public String fingerprint;
        public String tenantId;
        public String workspaceId;
        public String profileName;
        public String profileRevision;
        public String language;
        public String status;
        public String cachePath;
        public boolean readOnly;
        public String error;
        public Instant createdAt;
        public Instant updatedAt;
    }

    public static final class AuditEvent {
        public String eventId;
        public String tenantId;
        public String userId;
        public String principalId;
        public String keyId;
        public String action;
        public String resourceType;
        public String resourceId;
        public String status;
        public String message;
        public String traceId;
        public Instant createdAt;
    }

    public static final class AuditEventList {
        public List<AuditEvent> items = new ArrayList<>();
        public int nextOffset;
        public int total;
    }

    public static final class StartGuiRequest {
        public String kind = "novnc";
        public String resolution;
        public Integer ttlSeconds;
        public Map<String, String> metadata = new LinkedHashMap<>();
    }

    public static final class ViewerDescriptor {
        public String sandboxId;
        public String runtimeProfile;
        public String status;
        public String kind;
        public boolean ready;
        public URI pageUrl;
        public URI websocketUrl;
        public URI proxyUrl;
        public String accessToken;
        public String tokenHeader;
        public Instant expiresAt;
        public boolean renewOnAccess;
    }

    public static final class LeaseRequest {
        public String workspaceId;
        public EnvironmentSelector environment;
        public String resolutionId;
        public Map<String, String> metadata = new LinkedHashMap<>();
    }

    public static final class SandboxLease {
        public String sandboxId;
        public String leaseId;
        public String tenantId;
        public String userId;
        public String workspaceId;
        public String runtimeProfile;
        public String profileRevision;
        public EffectiveEnvironment effectiveEnvironment;
        public String status;
        public Instant createdAt;
        public Instant expiresAt;
        public Map<String, Object> effectivePolicy = new LinkedHashMap<>();
        public Map<String, String> metadata = new LinkedHashMap<>();
        public long resourceVersion;
    }

    private static List<String> commandList(String[] values) {
        if (values == null || values.length == 0) throw new IllegalArgumentException("command cannot be empty");
        List<String> result = new ArrayList<>(Arrays.asList(values));
        for (String value : result) requireText(value, "command item");
        return result;
    }

    private static void exactlyOne(String code, List<String> command, String target) {
        boolean hasCode = code != null && !code.isBlank();
        boolean hasCommand = command != null && !command.isEmpty();
        if (hasCode == hasCommand) throw new IllegalArgumentException(target + " requires exactly one of code or command");
    }

    private static int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static String requireText(String value, String name) {
        String normalized = blankToNull(value);
        if (normalized == null) throw new IllegalArgumentException(name + " cannot be blank");
        return normalized;
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
