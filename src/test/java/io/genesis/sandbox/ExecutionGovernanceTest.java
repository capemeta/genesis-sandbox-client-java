package io.genesis.sandbox;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionGovernanceTest {
    private static final String CONTEXT="{\"tenant_id\":\"tenant\",\"user_id\":\"user\",\"run_id\":\"run\",\"trace_id\":\"trace\",\"authorization_scope\":\"scope\",\"decision_reference\":null,\"subject_kind\":\"enterprise\",\"invocation_id\":null,\"execution_id\":null,\"attempt_id\":null}";
    private static ExecutionGovernance.Query query() throws Exception {
        return JsonCodec.read(("{\"context\":"+CONTEXT+",\"operation_id\":\"original\",\"workspace_id\":\"workspace\",\"source_identity\":\""+"a".repeat(64)+"\",\"claim_digest\":\""+"b".repeat(64)+"\"}").getBytes(StandardCharsets.UTF_8),ExecutionGovernance.Query.class);
    }
    private static String proof() { return "{\"context\":"+CONTEXT+",\"operation_id\":\"original\",\"claim_digest\":\""+"b".repeat(64)+"\",\"source_identity\":\""+"a".repeat(64)+"\",\"root_device\":1,\"root_inode\":2,\"calls\":[],\"leaves\":["+leaf("work",3)+","+leaf("output",4)+"]}"; }
    private static String leaf(String path,long inode) {return "{\"path\":\""+path+"\",\"device\":1,\"inode\":"+inode+",\"uid\":65532,\"gid\":65532,\"mode\":504,\"directory\":true,\"call_id\":null}";}
    @Test void identitiesRejectSeparatorsAndInvalidUtf8() {
        for(String value:new String[]{"a\u2028b","a\u2029b","a\t","\ud800","中".repeat(86)}) assertThrows(IllegalArgumentException.class,()->ExecutionGovernance.identity(value));
        ExecutionGovernance.identity("中".repeat(85)); ExecutionGovernance.identity("identity😀");
    }
    @Test void strictOriginalProofAndNullableFieldPresence() throws Exception {
        var query=query(); query.validate("workspace");
        assertEquals(2,ExecutionGovernance.decode(proof().getBytes(StandardCharsets.UTF_8),query).leaves.size());
        String unregistered=proof().replace("\"leaves\":[","\"leaves\":["+leaf("output/unregistered",8).replace("\"uid\":65532","\"uid\":0").replace("\"gid\":65532","\"gid\":100001")+",");
        assertThrows(java.io.IOException.class,()->ExecutionGovernance.decode(unregistered.getBytes(StandardCharsets.UTF_8),query));
        for(String invalid:new String[]{proof().replace("\"decision_reference\":null,",""),proof().replace("\"stop_confirmed\":true","\"stop_confirmed\":false"),proof().replace("\"root_inode\":2","\"root_inode\":0"),proof().replace("\"operation_id\":\"original\"","\"operation_id\":\"forged\""),proof().replace("\"call_id\":null","\"call_id\":\"unregistered\"")}) {
            if (invalid.equals(proof())) continue;
            assertThrows(java.io.IOException.class,()->ExecutionGovernance.decode(invalid.getBytes(StandardCharsets.UTF_8),query));
        }
    }
    @Test void trustedExecutionCarriesFullOriginalContextAndRejectsReadOnlyWork() throws Exception {
        String executionContext=CONTEXT.replace("\"invocation_id\":null","\"invocation_id\":\"invocation\"").replace("\"execution_id\":null","\"execution_id\":\"execution\"").replace("\"attempt_id\":null","\"attempt_id\":\"attempt\"");
        String json="{\"context\":"+executionContext+",\"lease_ref\":{\"provider_id\":\"genesis\",\"lease_id\":\"lease\",\"workspace\":{\"workspace_key\":\"key\",\"provider_id\":\"genesis\",\"resource_id\":\"resource\",\"scope\":\"scope\",\"generation\":1},\"generation\":1,\"expires_at\":\"2099-01-01T00:00:00Z\"},\"platform_operation_id\":\"original\",\"request_digest\":\""+"c".repeat(64)+"\",\"source_identity\":\""+"a".repeat(64)+"\",\"workspace_id\":\"workspace\",\"authorized_work_write\":true}";
        var trusted=JsonCodec.read(json.getBytes(StandardCharsets.UTF_8),ExecutionGovernance.Trusted.class);
        var request=new SandboxModels.ExecRequest().command("python").trustedGovernance(trusted); request.validate();
        assertTrue(new String(JsonCodec.write(request),StandardCharsets.UTF_8).contains("\"trusted_governance\""));
        trusted.authorizedWorkWrite=false; assertThrows(IllegalArgumentException.class,request::validate);
    }
    @Test void publicHttpQueryIsSingleSendAndIncludesNullContext() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); var sends=new AtomicInteger();
        server.createContext("/v1/workspaces/workspace/maintenance-proof:query",exchange->{
            sends.incrementAndGet(); assertEquals("POST",exchange.getRequestMethod());
            String sent=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8); assertTrue(sent.contains("\"decision_reference\":null"));
            if(sends.get()>1){exchange.sendResponseHeaders(503,-1);exchange.close();return;}
            byte[] bytes=proof().getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });server.start();
        try {
            var client=new Client("http://127.0.0.1:"+server.getAddress().getPort(),null,Duration.ofSeconds(5),3,Duration.ZERO);
            assertEquals(2,client.queryWorkspaceMaintenanceProof("workspace",query()).leaves.size()); assertEquals(1,sends.get());
            assertThrows(java.io.IOException.class,()->client.queryWorkspaceMaintenanceProof("workspace",query())); assertEquals(2,sends.get());
        }finally{server.stop(0);}
    }
}
