package io.genesis.sandbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 原授权、原停止与来源事实；不在SDK创建资源或补造执行回执。 */
public final class ExecutionGovernance {
    private ExecutionGovernance() {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static final class Context {
        public String tenantId,userId,runId,traceId,authorizationScope,decisionReference,subjectKind,invocationId,executionId,attemptId;
        public String tenantId(){return tenantId;} public String userId(){return userId;} public String runId(){return runId;}
        public String authorizationScope(){return authorizationScope;} public String executionId(){return executionId;}
        void validate(boolean execution) {
            for (String id : new String[]{tenantId, userId, runId, traceId, authorizationScope}) identity(id);
            if (!"enterprise".equals(subjectKind)) invalid();
            if (decisionReference != null) identity(decisionReference);
            if (execution || invocationId != null || executionId != null || attemptId != null) {
                identity(invocationId); identity(executionId); identity(attemptId);
            }
        }
    }
    public static final class WorkspaceRef {
        public String workspaceKey,providerId,resourceId,scope; public Long generation;
        public String providerId(){return providerId;} public String scope(){return scope;}
        void validate() { identity(workspaceKey); identity(providerId); identity(resourceId); identity(scope); number(generation,1); }
    }
    public static final class LeaseRef {
        public String providerId,leaseId; public WorkspaceRef workspace; public Long generation; public Instant expiresAt;
        void validate(Context context) {
            identity(providerId); identity(leaseId); number(generation,1);
            if (workspace == null || expiresAt == null) invalid();
            workspace.validate();
            if (!providerId.equals(workspace.providerId()) || !context.authorizationScope().equals(workspace.scope())) invalid();
        }
    }
    public static final class Trusted {
        public Context context; public LeaseRef leaseRef; public String platformOperationId,requestDigest,sourceIdentity,workspaceId; public Boolean authorizedWorkWrite;
        public void validate() {
            if (context == null || leaseRef == null) invalid();
            context.validate(true); leaseRef.validate(context); identity(platformOperationId); identity(workspaceId);
            digest(requestDigest); digest(sourceIdentity);
            if (!Boolean.TRUE.equals(authorizedWorkWrite)) invalid();
        }
    }
    public static final class Query {
        public Context context; public String operationId,workspaceId,sourceIdentity,claimDigest;
        public Context context(){return context;} public String operationId(){return operationId;} public String claimDigest(){return claimDigest;} public String sourceIdentity(){return sourceIdentity;}
        public void validate(String workspace) {
            identity(workspace); identity(workspaceId); identity(operationId); digest(sourceIdentity); digest(claimDigest);
            if (context == null || !workspace.equals(workspaceId)) invalid();
            context.validate(false);
        }
    }
    public static final class Call {
        public String callId,operationId,requestDigest,executionId,outputDirectory; public Context context; public LeaseRef leaseRef;
        public Long uid,gid,rootDevice,rootInode; public Boolean authorizedWorkWrite,stopConfirmed;
        public String callId(){return callId;} public String outputDirectory(){return outputDirectory;}
        public Long uid(){return uid;} public Long gid(){return gid;} public Long rootDevice(){return rootDevice;} public Long rootInode(){return rootInode;}
        public Boolean authorizedWorkWrite(){return authorizedWorkWrite;}
        void validate(Context current) {
            identity(callId); identity(operationId); digest(requestDigest); identity(executionId);
            if (context == null || leaseRef == null) invalid();
            context.validate(true); leaseRef.validate(context);
            number(uid,1); number(gid,1); number(rootDevice,0); number(rootInode,1);
            if (!Objects.equals(executionId,context.executionId()) || !Boolean.TRUE.equals(stopConfirmed) || authorizedWorkWrite == null
                    || outputDirectory == null || !outputDirectory.matches("output/[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                    || !Objects.equals(context.tenantId(),current.tenantId()) || !Objects.equals(context.userId(),current.userId())
                    || !Objects.equals(context.runId(),current.runId()) || !Objects.equals(context.authorizationScope(),current.authorizationScope())) invalid();
        }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static final class Leaf {
        public String path,callId; public Long device,inode,uid,gid,mode; public Boolean directory;
        public String path(){return path;} public String callId(){return callId;} public Long device(){return device;} public Long inode(){return inode;}
        public Long uid(){return uid;} public Long gid(){return gid;} public Long mode(){return mode;} public Boolean directory(){return directory;}
        void validate() {
            number(device,0); number(inode,1); number(uid,0); number(gid,0); number(mode,0);
            if (mode > 4095 || directory == null || path == null || !path.matches("(?:work|output)(?:/[^/]+)*")) invalid();
            for (String part : path.split("/")) {
                if (part.equals(".") || part.equals("..") || part.indexOf('\\') >= 0 || part.codePoints().anyMatch(c -> c <= 31 || c == 127)) invalid();
            }
            if (callId != null) identity(callId);
        }
    }
    public static final class Proof {
        public Context context; public String operationId,claimDigest,sourceIdentity; public Long rootDevice,rootInode; public List<Call> calls; public List<Leaf> leaves;
        public void validate(Query query) {
            if (context == null) invalid();
            context.validate(false); number(rootDevice,0); number(rootInode,1);
            if (!sameContext(context,query.context()) || !Objects.equals(operationId,query.operationId())
                    || !Objects.equals(claimDigest,query.claimDigest()) || !Objects.equals(sourceIdentity,query.sourceIdentity()) || calls == null || leaves == null) invalid();
            var byCall = new HashMap<String,Call>(); var uids = new HashSet<Long>();
            for (Call call : calls) {
                if (call == null) invalid();
                call.validate(query.context());
                if (byCall.putIfAbsent(call.callId(),call) != null || !uids.add(call.uid())) invalid();
            }
            var paths = new HashSet<String>();
            for (Leaf leaf : leaves) {
                if (leaf == null) invalid();
                leaf.validate(); if (!paths.add(leaf.path())) invalid();
                if (!leaf.device().equals(rootDevice) || leaf.callId()==null && (leaf.uid()!=0 && leaf.uid()!=65532
                        || leaf.path().startsWith("output/") && leaf.uid()==0 && leaf.gid()>=100001)) invalid();
                if (leaf.callId() != null) {
                    Call call = byCall.get(leaf.callId()); if (call == null) invalid();
                    boolean root = leaf.path().equals(call.outputDirectory()) && leaf.directory() && leaf.uid() == 0
                            && leaf.gid().equals(call.gid()) && leaf.device().equals(call.rootDevice()) && leaf.inode().equals(call.rootInode()) && leaf.mode() == 0770;
                    boolean owned = leaf.uid().equals(call.uid()) && (leaf.path().startsWith(call.outputDirectory()+"/") && (leaf.gid().equals(call.gid()) || leaf.gid()==65532)
                            || leaf.path().startsWith("work/") && call.authorizedWorkWrite() && (leaf.gid().equals(call.gid()) || leaf.gid() == 65532));
                    if (!root && !owned) invalid();
                }
            }
            if (!paths.contains("work") || !paths.contains("output")) invalid();
            for (Call call:calls) if (leaves.stream().noneMatch(leaf -> leaf.path().equals(call.outputDirectory()) && Objects.equals(leaf.callId(),call.callId()))) invalid();
        }
    }
    static Proof decode(byte[] bytes, Query query) throws IOException {
        try {
            // 闭合字段必须齐全，null只允许协议明确的可空身份。
            var reader = JsonCodec.MAPPER.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            var tree=reader.readTree(bytes);
            requireFields(tree, "context operation_id claim_digest source_identity root_device root_inode calls leaves");
            requireContext(tree.get("context"));
            for (var call:tree.get("calls")) {
                requireFields(call,"call_id context lease_ref operation_id request_digest execution_id output_directory uid gid root_device root_inode authorized_work_write stop_confirmed");
                requireContext(call.get("context"));
                requireFields(call.get("lease_ref"),"provider_id lease_id workspace generation expires_at");
                requireFields(call.get("lease_ref").get("workspace"),"workspace_key provider_id resource_id scope generation");
            }
            for (var leaf:tree.get("leaves")) requireFields(leaf,"path device inode uid gid mode directory call_id");
            Proof proof = reader.treeToValue(tree,Proof.class); proof.validate(query); return proof;
        } catch (IllegalArgumentException | NullPointerException error) { throw new IOException("Invalid original governance proof",error); }
    }
    private static void requireContext(com.fasterxml.jackson.databind.JsonNode node) { requireFields(node,"tenant_id user_id run_id trace_id authorization_scope decision_reference subject_kind invocation_id execution_id attempt_id"); }
    private static void requireFields(com.fasterxml.jackson.databind.JsonNode node,String names) {
        if (node==null || !node.isObject() || node.size()!=names.split(" ").length) invalid();
        for (String name:names.split(" ")) if (!node.has(name)) invalid();
    }
    private static boolean sameContext(Context left,Context right) {
        if (right==null) return false;
        return Objects.equals(left.tenantId,right.tenantId) && Objects.equals(left.userId,right.userId) && Objects.equals(left.runId,right.runId)
                && Objects.equals(left.traceId,right.traceId) && Objects.equals(left.authorizationScope,right.authorizationScope)
                && Objects.equals(left.decisionReference,right.decisionReference) && Objects.equals(left.subjectKind,right.subjectKind)
                && Objects.equals(left.invocationId,right.invocationId) && Objects.equals(left.executionId,right.executionId) && Objects.equals(left.attemptId,right.attemptId);
    }
    static void identity(String value) {
        if (value == null || value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > 256) invalid();
        for (int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if (c<=31 || c==127 || c=='\u2028' || c=='\u2029') invalid();
            if (Character.isHighSurrogate(c)) { if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) invalid(); }
            else if (Character.isLowSurrogate(c)) invalid();
        }
    }
    static void digest(String value) { if (value == null || !value.matches("[0-9a-f]{64}")) invalid(); }
    static void number(Long value,long minimum) { if (value == null || value < minimum || value > 9007199254740991L) invalid(); }
    private static void invalid() { throw new IllegalArgumentException("Invalid governance contract or original binding"); }
}
