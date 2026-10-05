package io.genesis.sandbox;

import java.io.IOException;

/** 原准备持久回执；unknown不代表未执行，查询不分配或重新准备。 */
public final class WorkspacePreparation {
    private WorkspacePreparation() {}

    public static final class Request {
        public String operationId;
        public String requestDigest;
        public Request() {}
        public Request(String operation, String digest) { operationId = operation; requestDigest = digest; }
        public void validate() {
            if (operationId == null || !operationId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                    || requestDigest == null || !requestDigest.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Invalid original preparation identity or digest");
        }
    }

    public static final class Receipt {
        public String operationId, requestDigest, status, sessionId, workspaceId, sandboxId;
        public String profileRevision, tenantId, principalId, userId;
        public WorkspaceModels.WorkspaceViewFacts facts;
    }

    static Receipt decode(byte[] body, String session, Request request) throws IOException {
        Receipt receipt = JsonCodec.readStrict(body, Receipt.class);
        var tree = JsonCodec.MAPPER.readTree(body);
        for (String field : new String[]{"operation_id", "request_digest", "status", "session_id", "workspace_id",
                "sandbox_id", "profile_revision", "tenant_id", "principal_id", "user_id"})
            if (tree == null || !tree.has(field) || !tree.get(field).isTextual())
                throw new IOException("Preparation receipt missing string identity");
        if (receipt == null || !session.equals(receipt.sessionId) || !request.operationId.equals(receipt.operationId)
                || !request.requestDigest.equals(receipt.requestDigest) || empty(receipt.workspaceId)
                || empty(receipt.profileRevision) || empty(receipt.tenantId) || empty(receipt.principalId) || empty(receipt.userId))
            throw new IOException("Original preparation receipt identity mismatch");
        if ("unknown".equals(receipt.status)) {
            if (tree.has("facts")) throw new IOException("Unknown preparation must omit facts");
        } else if ("prepared".equals(receipt.status)) {
            var facts = receipt.facts;
            var node = tree.get("facts");
            if (node == null || !node.isObject() || facts == null || empty(receipt.sandboxId)
                    || !session.equals(facts.sessionId) || !receipt.workspaceId.equals(facts.workspaceId)
                    || !receipt.profileRevision.equals(facts.profileRevision) || !"prepared".equals(facts.viewState))
                throw new IOException("Prepared receipt requires original bound facts");
            for (String field : new String[]{"os", "arch", "image_digest", "network_mode"})
                if (!node.has(field) || !node.get(field).isTextual() || node.get(field).asText().isEmpty())
                    throw new IOException("Preparation facts missing environment identity");
            for (String field : new String[]{"mechanisms", "resource_limits", "runtime_versions", "runtime_executables"})
                if (!node.has(field) || node.get(field).isNull()) throw new IOException("Incomplete preparation facts");
            if (facts.resourceLimits == null || facts.mechanisms == null || facts.runtimeVersions == null || facts.runtimeExecutables == null)
                throw new IOException("Invalid preparation facts collection");
            for (Long value : facts.resourceLimits.values())
                if (value == null || value < 1 || value > 9007199254740991L) throw new IOException("Invalid preparation resource budget");
        } else throw new IOException("Invalid preparation receipt status");
        return receipt;
    }
    private static boolean empty(String value) { return value == null || value.isEmpty(); }
}
