package io.genesis.sandbox;

import org.junit.jupiter.api.Test;

import static io.genesis.sandbox.SandboxModels.ExecRecord;
import static io.genesis.sandbox.SandboxModels.JobResult;
import static io.genesis.sandbox.SandboxModels.SandboxExecResult;

final class ExecStatusModelTest {
    @Test
    void commonTerminalStatesAreRecognizedAcrossExecutionModels() {
        JobResult job = new JobResult();
        job.status = "timed_out";
        if (!job.terminal()) throw new AssertionError("timed_out job must be terminal");
        job.status = "interrupted";
        if (!job.terminal()) throw new AssertionError("interrupted job must be terminal");

        ExecRecord exec = new ExecRecord();
        exec.status = "timed_out";
        if (!exec.terminal()) throw new AssertionError("timed_out exec must be terminal");
        exec.status = "interrupted";
        if (!exec.terminal()) throw new AssertionError("interrupted exec must be terminal");

        SandboxExecResult synchronous = new SandboxExecResult();
        synchronous.status = "timed_out";
        if (!synchronous.terminal()) throw new AssertionError("timed_out synchronous result must be terminal");
        synchronous.status = "interrupted";
        if (!synchronous.terminal()) throw new AssertionError("interrupted synchronous result must be terminal");
    }
}
