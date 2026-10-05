package io.genesis.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatterBuilder;
import java.util.TreeMap;

/** 持续存储父任务状态；不续租或恢复计算实例。 */
public final class WorkspaceLifecycle {
    private WorkspaceLifecycle() {}
    public static final class Request {
        public String operationId; public long expectedRevision; public String state; public long holdSeconds;
        public Instant terminalAt; public long retentionSeconds;
        public Request() {}
        public Request(String operation, long revision, String status, long hold, Instant terminal, long retention) {
            operationId=operation; expectedRevision=revision; state=status; holdSeconds=hold; terminalAt=terminal; retentionSeconds=retention;
        }
        public String operationId() { return operationId; } public long expectedRevision() { return expectedRevision; }
        public String state() { return state; } public long holdSeconds() { return holdSeconds; }
        public Instant terminalAt() { return terminalAt; } public long retentionSeconds() { return retentionSeconds; }
        public void validate() {
            identity(operationId);
            if (expectedRevision < 0 || expectedRevision >= 9007199254740991L || holdSeconds < 0 || holdSeconds > 86400 || retentionSeconds < 0 || retentionSeconds > 30 * 86400) throw new IllegalArgumentException("Invalid lifecycle budgets");
            if ("terminal".equals(state)) {
                if (holdSeconds != 0 || retentionSeconds == 0 || terminalAt == null || !validTime(terminalAt)) throw new IllegalArgumentException("Invalid terminal lifecycle");
            } else if ((!"active".equals(state) && !"paused".equals(state)) || terminalAt != null || retentionSeconds != 0) throw new IllegalArgumentException("Invalid lifecycle state");
        }
    }
    public static final class Receipt {
        public String operationId; public String workspaceId; public long revision; public String state;
        public Instant deadline; public Instant terminalAt; public String requestDigest;
        public String requestDigest() { return requestDigest; } public long revision() { return revision; } public String state() { return state; }
        public void validate(String workspace, String operation) throws IOException {
            if (!workspace.equals(workspaceId) || !operation.equals(operationId) || revision < 1 || revision > 9007199254740991L || deadline == null || requestDigest == null || !requestDigest.matches("[0-9a-f]{64}")) throw new IOException("Lifecycle receipt identity mismatch");
            if ((!"active".equals(state) && !"paused".equals(state) && !"terminal".equals(state)) || "terminal".equals(state) != (terminalAt != null)) throw new IOException("Invalid lifecycle receipt state");
            if (!validTime(deadline) || terminalAt != null && (!validTime(terminalAt) || terminalAt.isAfter(deadline))) throw new IOException("Invalid lifecycle receipt time");
        }
    }
    public static final class Lookup {
        public String state; public Receipt receipt;
        public void validate(String workspace, String operation) throws IOException {
            if ("found".equals(state)) { if (receipt == null) throw new IOException("Missing original receipt"); receipt.validate(workspace, operation); }
            else if ((!"unknown".equals(state) && !"history_expired".equals(state)) || receipt != null) throw new IOException("Invalid lifecycle lookup");
        }
    }
    static void identity(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]{1,128}") || value.equals(".") || value.equals("..")) throw new IllegalArgumentException("Invalid lifecycle operation ID");
    }
    private static boolean validTime(Instant value) {
        int year = value.atOffset(ZoneOffset.UTC).getYear();
        return year >= 1 && year <= 9999 && value.getNano() % 1000 == 0;
    }
    public static String requestDigest(Request request) throws IOException {
        request.validate();
        var canonical = new TreeMap<String, Object>();
        canonical.put("expected_revision", request.expectedRevision()); canonical.put("hold_seconds", request.holdSeconds());
        canonical.put("operation_id", request.operationId()); canonical.put("retention_seconds", request.retentionSeconds());
        canonical.put("state", request.state());
        canonical.put("terminal_at", request.terminalAt() == null ? null : new DateTimeFormatterBuilder().appendInstant(6).toFormatter().format(request.terminalAt()));
        try {
            // 固定六字段包含null；共享JsonCodec默认省略null，不适用于规范摘要。
            var mapper = JsonCodec.MAPPER.copy().setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS);
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex=new StringBuilder(64); for(byte value:hash) hex.append(String.format("%02x", value & 255)); return hex.toString();
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
