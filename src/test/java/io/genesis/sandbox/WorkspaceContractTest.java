package io.genesis.sandbox;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 本地HTTP协议测试不代表共享挂载或容器隔离验收。 */
class WorkspaceContractTest {
    @Test void storageIdentityPreservesUnsignedFactsAndRejectsInvalidProof() throws Exception {
        WorkspaceModels.SharedStorageIdentity identity = JsonCodec.readStrict(
            ("{\"source_identity\":\"" + "a".repeat(64) + "\",\"device_id\":18446744073709551615,"
                + "\"root_inode\":1,\"backing_device_id\":2,\"backing_inode\":3,\"filesystem\":\"ext4\","
                + "\"filesystem_uuid\":\"fs-id\",\"capacity_bytes\":33554432,\"inode_capacity\":100}")
                .getBytes(StandardCharsets.UTF_8), WorkspaceModels.SharedStorageIdentity.class);
        identity.validate("a".repeat(64));
        assertEquals(new java.math.BigInteger("18446744073709551615"), identity.deviceId);
        assertThrows(IllegalArgumentException.class, () -> identity.validate("b".repeat(64)));
        identity.deviceId = new java.math.BigInteger("18446744073709551616");
        assertThrows(IllegalArgumentException.class, () -> identity.validate("a".repeat(64)));
        assertThrows(IOException.class, () -> JsonCodec.readStrict("{\"device_id\":1.0}".getBytes(StandardCharsets.UTF_8),
            WorkspaceModels.SharedStorageIdentity.class));
    }

    @Test void closedBindingDoesNotAcceptPathsOwnerOrUnsafeVersion() throws Exception {
        WorkspaceModels.WorkspaceBinding shared = WorkspaceModels.WorkspaceBinding.shared("approved", "resource", 1);
        String body = new String(JsonCodec.write(shared), StandardCharsets.UTF_8);
        assertEquals("{\"mode\":\"shared\",\"storage_ref\":\"approved\",\"resource_id\":\"resource\",\"binding_version\":1}", body);
        assertThrows(IllegalArgumentException.class, () -> WorkspaceModels.WorkspaceBinding.shared("D:/host", "resource", 1));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceModels.WorkspaceBinding.shared("approved", "..", 1));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceModels.WorkspaceBinding.shared("approved", "resource", 9007199254740992L));
        WorkspaceModels.WorkspaceBinding isolated = WorkspaceModels.WorkspaceBinding.isolated();
        isolated.storageRef = "approved";
        assertThrows(IllegalArgumentException.class, isolated::validate);
        for (String extra : new String[]{"host_path", "owner_tenant_id", "owner_user_id", "owner_run_id"}) {
            byte[] invalid = ("{\"mode\":\"isolated\",\"" + extra + "\":\"self\"}").getBytes(StandardCharsets.UTF_8);
            assertThrows(IOException.class, () -> JsonCodec.readStrict(invalid, WorkspaceModels.WorkspaceBinding.class));
        }
        assertThrows(IOException.class, () -> JsonCodec.readStrict("{\"binding_version\":1.0}".getBytes(StandardCharsets.UTF_8), WorkspaceModels.WorkspaceBinding.class));
        assertThrows(IOException.class, () -> JsonCodec.readStrict("{\"binding_version\":\"1\"}".getBytes(StandardCharsets.UTF_8), WorkspaceModels.WorkspaceBinding.class));
        assertThrows(IOException.class, () -> JsonCodec.readStrict("{\"purged\":\"true\"}".getBytes(StandardCharsets.UTF_8), WorkspacePurge.Receipt.class));
        assertThrows(IOException.class, () -> JsonCodec.readStrict("{\"mode\":\"isolated\",\"mode\":\"shared\"}".getBytes(StandardCharsets.UTF_8), WorkspaceModels.WorkspaceBinding.class));
    }

    @Test void workspaceControlsUseActualRoutesAndNeverReplayUnknownMutation() throws Exception {
        AtomicInteger chmod = new AtomicInteger();
        AtomicInteger purge = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/storage-resources/approved/resource", exchange -> {
            byte[] body = ("{\"storage_ref\":\"approved\",\"resource_id\":\"resource\",\"workspace_id\":\"ws\","
                + "\"provisioner_id\":\"native\",\"binding_version\":1,\"source_identity\":\"" + "a".repeat(64)
                + "\",\"budget_bytes\":33554432,\"persistent\":true,\"hard_quota\":true,\"quota_mb\":32,"
                + "\"owner_tenant_id\":\"tenant\",\"owner_user_id\":\"user\",\"owner_run_id\":\"run\","
                + "\"hard_quota_verified\":false,\"shared_attachment\":false}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/v1/sessions/s/files:executable", exchange -> {
            chmod.incrementAndGet();
            assertEquals("PATCH", exchange.getRequestMethod());
            assertEquals("path=work%2Frun.sh", exchange.getRequestURI().getRawQuery());
            assertEquals("b".repeat(64), exchange.getRequestHeaders().getFirst("If-Match"));
            assertEquals("{\"executable\":true,\"expected_executable\":false}", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(503, -1); exchange.close();
        });
        server.createContext("/v1/sessions/s/workspace:purge", exchange -> {
            purge.incrementAndGet(); exchange.sendResponseHeaders(503, -1); exchange.close();
        });
        server.createContext("/v1/sessions/s/workspace-view", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            if ("GET".equals(exchange.getRequestMethod())) assertNull(query);
            else assertTrue("action=prepare".equals(query) || "action=seal".equals(query));
            if ("action=prepare".equals(query)) {
                byte[] body = WorkspacePreparationTest.unknown().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
                return;
            }
            byte[] body = "{\"session_id\":\"s\",\"workspace_id\":\"ws\",\"os\":\"linux\",\"arch\":\"amd64\",\"view_state\":\"sealed\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/v1/sessions/s/history", exchange -> {
            byte[] body = "{\"session_id\":\"s\",\"status\":\"closed\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            Client client = new Client("http://127.0.0.1:" + server.getAddress().getPort(), null,
                Duration.ofSeconds(5), 4, Duration.ZERO);
            assertFalse(client.inspectSharedStorageResource("approved", "resource").sharedAttachment);
            assertEquals("sealed", client.getWorkspaceView("s").viewState);
            assertEquals("unknown", client.prepareWorkspaceView("s", new WorkspacePreparation.Request("prepare:1", "a".repeat(64))).status);
            assertEquals("sealed", client.sealWorkspaceView("s").viewState);
            assertEquals("closed", client.getSessionHistory("s").status);
            assertThrows(IOException.class, () -> client.setSessionFileExecutable("s", "work/run.sh", true, "b".repeat(64), false));
            assertThrows(IOException.class, () -> client.purgeSessionWorkspace("s", new WorkspacePreparation.Request("purge:1", "a".repeat(64))));
            assertEquals(1, chmod.get()); assertEquals(1, purge.get());
        } finally { server.stop(0); }
    }
}
