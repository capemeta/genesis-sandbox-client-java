package io.genesis.sandbox;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceLifecycleTest {
    @Test void canonicalSixFieldsMatchProtocolVectors() throws Exception {
        assertEquals("600867eb32528c3e0e110f9de2ec17e7e8c96b73428a54545934953a05b49442", WorkspaceLifecycle.requestDigest(new WorkspaceLifecycle.Request("original",0,"active",0,null,0)));
        assertEquals("abb14a40cc3f77bc822599e2a4b089677e01c3b66ff178513f3e71e84adba74a", WorkspaceLifecycle.requestDigest(new WorkspaceLifecycle.Request("terminal",7,"terminal",0,OffsetDateTime.parse("2026-10-01T08:30:12.123456+08:00").toInstant(),86400)));
    }
    @Test void malformedIntentAndClosedReceiptAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> WorkspaceLifecycle.requestDigest(new WorkspaceLifecycle.Request("original",0,"active",0,null,1)));
        assertThrows(java.io.IOException.class, () -> JsonCodec.readStrict("{\"state\":\"unknown\",\"receipt\":null,\"host_path\":\"/host\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8), WorkspaceLifecycle.Lookup.class));
    }
}
