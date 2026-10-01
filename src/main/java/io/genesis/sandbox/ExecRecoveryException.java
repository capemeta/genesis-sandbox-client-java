package io.genesis.sandbox;

import java.io.IOException;

/** An ExecRecord could not be observed; its operation identity can resume lookup. */
public final class ExecRecoveryException extends IOException {
    private final String sessionId;
    private final String operationId;
    private final String execId;
    private final String phase;

    public ExecRecoveryException(String sessionId, String operationId, String execId,
                                 String phase, IOException cause) {
        super("exec " + phase + " requires recovery (session_id=" + sessionId
                + ", operation_id=" + operationId
                + (execId == null || execId.isBlank() ? "" : ", exec_id=" + execId) + ")", cause);
        this.sessionId = sessionId;
        this.operationId = operationId;
        this.execId = execId;
        this.phase = phase;
    }

    public String sessionId() { return sessionId; }
    public String operationId() { return operationId; }
    public String execId() { return execId; }
    public String phase() { return phase; }
}
