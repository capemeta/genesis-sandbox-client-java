package io.genesis.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import com.sun.net.httpserver.HttpServer;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkspacePurgeTest {
    @Test void cleanupOwnershipIsClosedAndDoesNotGrantLogs() throws Exception {
        byte[] owner = "{\"session_id\":\"s\",\"workspace_id\":\"w\",\"profile_revision\":\"rev\",\"tenant_id\":\"t\",\"principal_id\":\"p\",\"user_id\":\"u\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals("w",WorkspacePurge.ownership(owner,"s").workspaceId);
        assertThrows(IOException.class,() -> WorkspacePurge.ownership(owner,"other"));
        String extra = new String(owner,StandardCharsets.UTF_8).replace("}",",\"stdout\":\"not granted\"}");
        assertThrows(IOException.class,() -> WorkspacePurge.ownership(extra.getBytes(StandardCharsets.UTF_8),"s"));
        assertEquals("w",WorkspacePurge.decode(proof().getBytes(StandardCharsets.UTF_8),null,REQUEST).workspaceId);
    }
    static final WorkspacePreparation.Request REQUEST = new WorkspacePreparation.Request("original","a".repeat(64));
    static String proof() {
        return "{\"operation_id\":\"original\",\"request_digest\":\""+REQUEST.requestDigest+"\",\"status\":\"unknown\","
            +"\"session_id\":\"s\",\"workspace_id\":\"w\",\"profile_revision\":\"rev\",\"tenant_id\":\"t\",\"principal_id\":\"p\",\"user_id\":\"u\"}";
    }
    @Test void originalLookupIsReadOnlyAndUnknownSubmissionIsSingleSend() throws Exception {
        AtomicInteger writes = new AtomicInteger(), reads = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/sessions/s/workspace", exchange -> {
            if (exchange.getRequestMethod().equals("GET")) { reads.incrementAndGet(); exchange.sendResponseHeaders(404,-1); }
            else {
                writes.incrementAndGet();
                assertEquals(REQUEST.operationId,JsonCodec.readStrict(exchange.getRequestBody().readAllBytes(),WorkspacePreparation.Request.class).operationId);
                byte[] body = proof().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        try {
            Client client = new Client("http://127.0.0.1:"+server.getAddress().getPort(),null);
            assertNull(client.lookupWorkspacePurge("s",REQUEST));
            assertEquals("unknown",client.purgeSessionWorkspace("s",REQUEST).status);
            assertEquals(1,reads.get()); assertEquals(1,writes.get());
        } finally { server.stop(0); }
    }
    @Test void strictProofRejectsSubstitutionMissingAndLegacyBool() throws Exception {
        assertEquals("unknown",WorkspacePurge.decode(proof().getBytes(StandardCharsets.UTF_8),"s",REQUEST).status);
        for (String invalid : new String[]{"{\"purged\":true}",proof().replace("\"user_id\":\"u\"","\"user_id\":null"),
                proof().replace("\"session_id\":\"s\"","\"session_id\":\"other\"")})
            assertThrows(IOException.class,() -> WorkspacePurge.decode(invalid.getBytes(StandardCharsets.UTF_8),"s",REQUEST));
    }
}
