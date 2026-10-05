package io.genesis.sandbox;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 工作区控制面契约；来源只用服务批准的不透明引用，不接受宿主路径。 */
public final class WorkspaceModels {
    private WorkspaceModels() {}
    static final long MAX_SAFE_INTEGER = 9007199254740991L;

    public static final class WorkspaceBinding {
        public String mode;
        public String storageRef;
        public String resourceId;
        public Long bindingVersion;

        public static WorkspaceBinding isolated() {
            WorkspaceBinding result = new WorkspaceBinding(); result.mode = "isolated"; return result;
        }

        public static WorkspaceBinding shared(String storageRef, String resourceId, long bindingVersion) {
            WorkspaceBinding result = new WorkspaceBinding();
            result.mode = "shared"; result.storageRef = storageRef; result.resourceId = resourceId;
            result.bindingVersion = bindingVersion; result.validate(); return result;
        }

        void validate() {
            if ("isolated".equals(mode)) {
                if (storageRef != null || resourceId != null || bindingVersion != null)
                    throw new IllegalArgumentException("isolated binding cannot reference shared storage");
            } else if ("shared".equals(mode)) {
                opaque(storageRef); opaque(resourceId);
                if (bindingVersion == null || bindingVersion <= 0 || bindingVersion > MAX_SAFE_INTEGER)
                    throw new IllegalArgumentException("binding version must be a positive safe integer");
            } else throw new IllegalArgumentException("invalid workspace binding mode");
        }
    }

    public static final class SharedStorageResource {
        public String storageRef;
        public String resourceId;
        public String workspaceId;
        public String provisionerId;
        public Long bindingVersion;
        public String sourceIdentity;
        public Long budgetBytes;
        public Boolean persistent;
        public Boolean hardQuota;
        public Integer quotaMb;
        public String ownerTenantId;
        public String ownerUserId;
        public String ownerRunId;
        public Boolean hardQuotaVerified;
        public Boolean sharedAttachment;
        public SharedStorageIdentity identity;

        void validate(String requestedDomain, String requestedResource) {
            if (!requestedDomain.equals(storageRef) || !requestedResource.equals(resourceId))
                throw new IllegalArgumentException("storage response identity mismatch");
            opaque(workspaceId); opaque(provisionerId);
            WorkspaceBinding.shared(storageRef, resourceId, bindingVersion == null ? 0 : bindingVersion);
            digest(sourceIdentity);
            if (budgetBytes == null || budgetBytes <= 0 || budgetBytes > MAX_SAFE_INTEGER
                    || quotaMb == null || quotaMb <= 0 || ownerTenantId == null || ownerTenantId.isBlank()
                    || ownerUserId == null || ownerUserId.isBlank() || ownerRunId == null || ownerRunId.isBlank()
                    || persistent == null || hardQuota == null || hardQuotaVerified == null || sharedAttachment == null)
                throw new IllegalArgumentException("storage response missing required facts");
            if (identity != null) identity.validate(sourceIdentity);
            // false 是真实能力事实，不由SDK擅自改为true或切换isolated。
            if (Boolean.TRUE.equals(hardQuotaVerified) && identity == null)
                throw new IllegalArgumentException("verified storage requires source identity facts");
        }
    }

    public static final class SharedStorageIdentity {
        public String sourceIdentity;
        public BigInteger deviceId;
        public BigInteger rootInode;
        public BigInteger backingDeviceId;
        public BigInteger backingInode;
        public String filesystem;
        public String filesystemUuid;
        public BigInteger capacityBytes;
        public BigInteger inodeCapacity;

        void validate(String expected) {
            if (!expected.equals(sourceIdentity) || filesystem == null || filesystem.isBlank()
                    || filesystemUuid == null || filesystemUuid.isBlank())
                throw new IllegalArgumentException("storage filesystem identity mismatch");
            for (BigInteger value : new BigInteger[] {deviceId, rootInode, backingDeviceId, backingInode, capacityBytes, inodeCapacity}) {
                if (value == null || value.signum() < 0 || value.bitLength() > 64)
                    throw new IllegalArgumentException("storage identity must contain unsigned 64-bit facts");
            }
        }
    }

    public static final class WorkspaceViewFacts {
        public String sessionId;
        public String workspaceId;
        public String profileRevision;
        public String os;
        public String arch;
        public String imageDigest;
        public Map<String, String> runtimeVersions = new LinkedHashMap<>();
        public Map<String, String> runtimeExecutables = new LinkedHashMap<>();
        public List<String> mechanisms = new ArrayList<>();
        public Map<String, Long> resourceLimits = new LinkedHashMap<>();
        public String networkMode;
        public String viewState;
        public List<String> readonlyRegions = new ArrayList<>();
    }

    static final class ExecutableRequest {
        public boolean executable;
        public Boolean expectedExecutable;
        ExecutableRequest(boolean value, Boolean expected) { executable = value; expectedExecutable = expected; }
    }

    static void opaque(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]{1,128}") || value.equals(".") || value.equals(".."))
            throw new IllegalArgumentException("storage identity must be an opaque reference");
    }
    static void digest(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("source identity must be a SHA-256 digest");
    }
}
