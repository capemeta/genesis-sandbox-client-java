package verification;

import io.genesis.sandbox.Client;
import io.genesis.sandbox.SandboxModels;
import io.genesis.sandbox.WorkspaceModels;
import io.genesis.sandbox.WorkspaceLifecycle;
import java.lang.reflect.Method;

/** 仅消费本地 Maven 已安装构件，不加入主SDK源码目录。 */
public final class InstalledSdkConsumer {
    public static void main(String[] args) throws Exception {
        SandboxModels.CreateWorkspaceRequest request = new SandboxModels.CreateWorkspaceRequest()
                .workspaceBinding(WorkspaceModels.WorkspaceBinding.shared("approved", "resource", 1))
                .retention("explicit_delete", null);
        if (!"shared".equals(request.workspaceBinding.mode)) throw new AssertionError("binding");
        for (String method : new String[]{"inspectSharedStorageResource", "getWorkspaceView", "prepareWorkspaceView",
                "sealWorkspaceView", "getSessionHistory", "purgeSessionWorkspace", "setSessionFileExecutable", "lookupSession",
                "controlWorkspaceLifecycle", "lookupWorkspaceLifecycle"}) {
            boolean found = false;
            for (Method candidate : Client.class.getMethods()) if (candidate.getName().equals(method)) found = true;
            if (!found) throw new AssertionError("missing installed API " + method);
        }
        String digest = WorkspaceLifecycle.requestDigest(
                new WorkspaceLifecycle.Request("original", 0, "active", 0, null, 0));
        if (!"600867eb32528c3e0e110f9de2ec17e7e8c96b73428a54545934953a05b49442".equals(digest))
            throw new AssertionError("installed lifecycle protocol digest");
        String source = Client.class.getProtectionDomain().getCodeSource().getLocation().toExternalForm();
        if (!source.endsWith("genesis-sandbox-client-java-0.3.0.jar"))
            throw new AssertionError("must consume installed jar, not source checkout: " + source);
        System.out.println("Installed SDK 0.3.0 consumer verified: " + source);
    }
}
