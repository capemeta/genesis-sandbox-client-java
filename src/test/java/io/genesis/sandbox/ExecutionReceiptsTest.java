package io.genesis.sandbox;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.genesis.sandbox.SandboxModels.*;

class ExecutionReceiptsTest {
    @Test void cancellationRetainsExplicitStopRatherThanTerminalInference() throws Exception {
        ExecRecord record = JsonCodec.read("{\"exec_id\":\"original\",\"operation_id\":\"op\",\"session_id\":\"session\",\"status\":\"cancelled\",\"stop_confirmed\":false}".getBytes(), ExecRecord.class);
        assertFalse(ExecutionReceipts.record(record, "session", "original", "op").stopConfirmed);
        record.stopConfirmed = true;
        assertTrue(ExecutionReceipts.record(record, "session", "original", "op").stopConfirmed);
        record.execId = "foreign";
        assertThrows(IOException.class, () -> ExecutionReceipts.record(record, "session", "original", "op"));
    }
    @Test void duplicateOrCoercedStopAndFractionalIntegerAreRejected() {
        for (String body : new String[]{"{\"stop_confirmed\":false,\"stop_confirmed\":true}", "{\"stop_confirmed\":\"true\"}", "{\"duration_ms\":1.5}"}) {
            assertThrows(IOException.class, () -> JsonCodec.read(body.getBytes(), ExecRecord.class));
        }
    }
}
