package io.genesis.sandbox;

/**
 * Result of a sandbox code execution.
 *
 * <p>Immutable value object; constructed by {@link SandboxSession} and
 * {@link AsyncSandboxSession} after a job completes.
 */
public final class ExecResult {

    public final int     exitCode;
    public final String  stdout;
    public final String  stderr;
    public final String  errorCode;        // timeout/cancelled/oom or empty
    public final boolean stdoutTruncated;
    public final boolean stderrTruncated;
    public final EffectiveEnvironment effectiveEnvironment;

    public ExecResult(int exitCode, String stdout, String stderr, String errorCode,
                      boolean stdoutTruncated, boolean stderrTruncated,
                      EffectiveEnvironment effectiveEnvironment) {
        this.exitCode         = exitCode;
        this.stdout           = stdout   != null ? stdout   : "";
        this.stderr           = stderr   != null ? stderr   : "";
        this.errorCode        = errorCode != null ? errorCode : "";
        this.stdoutTruncated  = stdoutTruncated;
        this.stderrTruncated  = stderrTruncated;
        this.effectiveEnvironment = effectiveEnvironment;
    }

    static ExecResult from(SandboxModels.ExecRecord record) {
        return new ExecResult(
                record.exitCode,
                record.stdout,
                record.stderr,
                record.errorCode,
                record.stdoutTruncated,
                record.stderrTruncated,
                record.effectiveEnvironment
        );
    }

    /**
     * Returns {@code true} only for a genuinely successful execution.
     * 与 Go/Python SDK 对齐：取消/超时等异常终止会置 errorCode（如 EXEC_CANCELLED），
     * 即使 exitCode 为 0 也不是成功执行。
     */
    public boolean ok() { return exitCode == 0 && errorCode.isEmpty(); }

    @Override
    public String toString() {
        String preview = stdout.length() > 60 ? stdout.substring(0, 60) + "\u2026" : stdout;
        return String.format("ExecResult{exitCode=%d, errorCode=%s, stdout=%s}",
                exitCode, errorCode, preview.replace("\n", "\\n"));
    }
}
