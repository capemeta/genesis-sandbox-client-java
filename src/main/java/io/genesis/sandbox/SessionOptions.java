package io.genesis.sandbox;

import java.util.ArrayList;
import java.util.List;

/** Validated options for opening a synchronous or asynchronous Session. */
public final class SessionOptions {
    final String profile;
    final String profileRevision;
    final List<String> hints;
    final boolean strict;
    final String description;
    final String resolutionId;
    final String workspaceId;
    final String workspaceRetention;
    final int workspaceTtlSeconds;
    final int ttlSeconds;
    final String idempotencyKey;
    final boolean heartbeat;
    final int renewIntervalSeconds;
    final int renewExtendSeconds;

    private SessionOptions(Builder builder) {
        this.profile = builder.profile;
        this.profileRevision = builder.profileRevision;
        this.hints = List.copyOf(builder.hints);
        this.strict = builder.strict;
        this.description = builder.description;
        this.resolutionId = builder.resolutionId;
        this.workspaceId = builder.workspaceId;
        this.workspaceRetention = builder.workspaceRetention;
        this.workspaceTtlSeconds = builder.workspaceTtlSeconds;
        this.ttlSeconds = builder.ttlSeconds;
        this.idempotencyKey = builder.idempotencyKey;
        this.heartbeat = builder.heartbeat;
        this.renewIntervalSeconds = builder.renewIntervalSeconds;
        this.renewExtendSeconds = builder.renewExtendSeconds;
    }

    public static Builder builder() { return new Builder(); }

    SandboxModels.CreateSessionRequest toRequest() {
        SandboxModels.CreateSessionRequest request = new SandboxModels.CreateSessionRequest();
        if (profile != null && profileRevision != null) {
            request.environment(SandboxModels.EnvironmentSelector.exactProfile(profile, profileRevision));
        } else if (profile != null) {
            request.environment(SandboxModels.EnvironmentSelector.profile(profile));
        } else if (!hints.isEmpty()) {
            request.environment(SandboxModels.EnvironmentSelector.hints(hints, strict, description));
        } else if (resolutionId != null) {
            request.resolutionId(resolutionId);
        }
        request.workspaceId = workspaceId;
        if (workspaceRetention != null) {
            request.workspaceRetention = workspaceRetention;
            request.workspaceTtlSeconds = workspaceTtlSeconds > 0 ? workspaceTtlSeconds : null;
        }
        request.idempotencyKey = idempotencyKey;
        request.statePolicy = "session";
        request.ttlSeconds = ttlSeconds;
        return request;
    }

    public static final class Builder {
        private String profile;
        private String profileRevision;
        private List<String> hints = new ArrayList<>();
        private boolean strict;
        private String description;
        private String resolutionId;
        private String workspaceId;
        private String workspaceRetention;
        private int workspaceTtlSeconds;
        private int ttlSeconds = SandboxSession.DEFAULT_SESSION_TTL;
        private String idempotencyKey;
        private boolean heartbeat = true;
        private int renewIntervalSeconds = SandboxSession.DEFAULT_RENEW_INTERVAL;
        private int renewExtendSeconds = SandboxSession.DEFAULT_RENEW_EXTEND;

        public Builder profile(String value) {
            return profile(value, null);
        }

        public Builder profile(String name, String revision) {
            profile = requireText(name, "profile");
            profileRevision = blankToNull(revision);
            hints = new ArrayList<>();
            resolutionId = null;
            return this;
        }

        public Builder hints(List<String> values, boolean strict, String description) {
            if (values == null || values.isEmpty()) {
                throw new IllegalArgumentException("hints cannot be empty");
            }
            this.hints = new ArrayList<>();
            for (String value : values) {
                this.hints.add(requireText(value, "hint"));
            }
            this.strict = strict;
            this.description = blankToNull(description);
            this.profile = null;
            this.profileRevision = null;
            this.resolutionId = null;
            return this;
        }

        public Builder resolutionId(String value) {
            resolutionId = requireText(value, "resolutionId");
            profile = null;
            profileRevision = null;
            hints = new ArrayList<>();
            return this;
        }

        public Builder workspaceId(String value) { workspaceId = blankToNull(value); return this; }
        public Builder idempotencyKey(String value) { idempotencyKey = blankToNull(value); return this; }

        public Builder retention(String mode, int ttlSeconds) {
            if (!"ttl".equals(mode) && !"explicit_delete".equals(mode)) {
                throw new IllegalArgumentException("retention must be ttl or explicit_delete");
            }
            if ("ttl".equals(mode) && ttlSeconds <= 0) {
                throw new IllegalArgumentException("workspace ttl must be positive");
            }
            workspaceRetention = mode;
            workspaceTtlSeconds = ttlSeconds;
            return this;
        }

        public Builder ttlSeconds(int value) {
            if (value <= 0) throw new IllegalArgumentException("ttlSeconds must be positive");
            ttlSeconds = value;
            return this;
        }

        public Builder heartbeat(boolean enabled, int intervalSeconds, int extendSeconds) {
            if (intervalSeconds < 0 || extendSeconds < 0) {
                throw new IllegalArgumentException("heartbeat values cannot be negative");
            }
            heartbeat = enabled;
            renewIntervalSeconds = intervalSeconds;
            renewExtendSeconds = extendSeconds;
            return this;
        }

        public SessionOptions build() { return new SessionOptions(this); }

        private static String requireText(String value, String name) {
            String normalized = blankToNull(value);
            if (normalized == null) throw new IllegalArgumentException(name + " cannot be blank");
            return normalized;
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }
    }
}
