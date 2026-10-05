package io.genesis.sandbox;

import java.io.IOException;

/** 原删除只承认持久身份；unknown 不触发自动重放。 */
public final class WorkspacePurge {
    private WorkspacePurge() {}
    public static final class Receipt {
        public String operationId, requestDigest, status, sessionId, workspaceId;
        public String profileRevision, tenantId, principalId, userId;
    }
    public static final class Ownership {
        public String sessionId, workspaceId, tenantId, principalId, userId, profileRevision;
    }
    static Ownership ownership(byte[] body, String session) throws IOException {
        var value = JsonCodec.readStrict(body,Ownership.class);
        var tree = JsonCodec.MAPPER.readTree(body);
        for (String field : new String[]{"session_id","workspace_id","tenant_id","principal_id","user_id","profile_revision"})
            if (tree == null || !tree.has(field) || !tree.get(field).isTextual() || tree.get(field).asText().isEmpty())
                throw new IOException("Missing original session ownership identity");
        if (value == null || !session.equals(value.sessionId)) throw new IOException("Original session ownership mismatch");
        return value;
    }
    static Receipt decode(byte[] body, String session, WorkspacePreparation.Request request) throws IOException {
        Receipt receipt = JsonCodec.readStrict(body, Receipt.class);
        var tree = JsonCodec.MAPPER.readTree(body);
        for (String field : new String[]{"operation_id","request_digest","status","session_id","workspace_id",
                "profile_revision","tenant_id","principal_id","user_id"})
            if (tree == null || !tree.has(field) || !tree.get(field).isTextual() || tree.get(field).asText().isEmpty())
                throw new IOException("Purge receipt missing nonempty string identity");
        if (receipt == null || (session != null && !session.equals(receipt.sessionId)) || !request.operationId.equals(receipt.operationId)
                || !request.requestDigest.equals(receipt.requestDigest)
                || !("unknown".equals(receipt.status) || "purged".equals(receipt.status)))
            throw new IOException("Original purge receipt identity mismatch");
        return receipt;
    }
}
