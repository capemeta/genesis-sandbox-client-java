package examples;

import io.genesis.sandbox.Client;
import io.genesis.sandbox.ExecResult;
import io.genesis.sandbox.SandboxSession;
import io.genesis.sandbox.SandboxModels.Session;
import io.genesis.sandbox.SandboxModels.WorkspaceFileInfo;
import io.genesis.sandbox.SandboxModels.WorkspaceFileList;

import java.nio.charset.StandardCharsets;
import java.util.logging.*;

/**
 * Production-grade synchronous SDK usage example for Session + WorkspaceFS.
 *
 * Demonstrates:
 *   - Session-backed helper reuses one workspace across multiple steps
 *   - try-with-resources cleanup
 *   - dormant create / lazy runtime / Suspend / resume ({@link #demoDormantSuspend})
 *   - Python / Shell / Node / Command execution
 *   - WorkspaceFS APIs work without a running container; runtime mounts the same workspace
 *
 * <p><b>Boundaries (avoid misreading this file):</b>
 * <ul>
 *   <li>Default path: open → write/exec → close. Suspend is optional.</li>
 *   <li>{@code sandbox_id} is the current Runtime: may be empty or change after Suspend.</li>
 *   <li>{@link ExecResult} only has exit/stdout/stderr; helper backfills {@code getSandboxId()}.</li>
 *   <li>{@code close()}/{@code deleteSession} does not delete Workspace under
 *       {@code explicit_delete}; {@link #demoDormantSuspend} shows explicit
 *       {@code deleteWorkspace} after close.</li>
 *   <li>Viewer/GUI and Job+Artifact are out of scope (see JobArtifactSync).</li>
 * </ul>
 */
public class ProductionSync {
    private static final Logger LOG = Logger.getLogger(ProductionSync.class.getName());

    public static void main(String[] args) throws Exception {
        configureLogging();

        String baseUrl = getenv("GENESIS_SANDBOX_BASE_URL", "http://127.0.0.1:18010");
        String apiKey = getenv("GENESIS_SANDBOX_API_KEY", "test-token-1");
        Client client = new Client(baseUrl, apiKey);

        demoPython(client);
        demoShell(client);
        demoNode(client);
        demoCommand(client);
        demoStateSharing(client);
        demoDormantSuspend(client);

        LOG.info("Session + WorkspaceFS sync demos completed.");
    }

    private static void demoPython(Client client) throws Exception {
        LOG.info("=== Session Sync: Python ===");
        try (SandboxSession sb = SandboxSession.open(client, "code-polyglot-basic")) {
            ExecResult r = sb.runPython(
                    "import sys, platform\n" +
                    "print(f'Python: {sys.version}')\n" +
                    "print(f'Platform: {platform.system()}')\n" +
                    "def fib(n):\n" +
                    "    a, b = 0, 1\n" +
                    "    for _ in range(n):\n" +
                    "        a, b = b, a + b\n" +
                    "    return a\n" +
                    "print('fib(30):', fib(30))\n"
            );
            System.out.println(r.stdout);
        }
    }

    private static void demoShell(Client client) throws Exception {
        LOG.info("=== Session Sync: Shell ===");
        try (SandboxSession sb = SandboxSession.open(client, "code-polyglot-basic")) {
            ExecResult r = sb.runShell(
                    "#!/bin/sh\n" +
                    "echo '=== System Info ==='\n" +
                    "uname -a\n" +
                    "echo '=== Disk ==='\n" +
                    "df -h /\n"
            );
            System.out.println(r.stdout);
        }
    }

    private static void demoNode(Client client) throws Exception {
        LOG.info("=== Session Sync: Node.js ===");
        try (SandboxSession sb = SandboxSession.open(client, "code-polyglot-basic")) {
            ExecResult r = sb.runNode(
                    "const os = require('os');\n" +
                    "console.log('Node:', process.version, '| Platform:', os.platform());\n" +
                    "console.log('Squares:', Array.from({length: 5}, (_, i) => i * i));\n"
            );
            System.out.println(r.stdout);
        }
    }

    private static void demoCommand(Client client) throws Exception {
        LOG.info("=== Session Sync: Raw Command ===");
        try (SandboxSession sb = SandboxSession.open(client, "code-polyglot-basic")) {
            ExecResult r = sb.runCommand("python", "-c", "import json; print(json.dumps({'ok': True, 'n': 42}))");
            System.out.println(r.stdout);
        }
    }

    /** 共享的是 Session Workspace，不是“永远同一个 sandbox_id”。 */
    private static void demoStateSharing(Client client) throws Exception {
        LOG.info("=== Session Sync: Multi-task on one session workspace ===");
        try (SandboxSession sb = SandboxSession.open(client, "code-polyglot-basic")) {
            System.out.printf(
                    "before any exec: session=%s sandbox=%s workspace=%s%n",
                    sb.getSessionId(), quote(sb.getSandboxId()), sb.workspaceId()
            );
            WorkspaceFileInfo info = sb.writeFile(
                    "shared/state.txt",
                    "hello from task 1\n".getBytes(StandardCharsets.UTF_8)
            );
            System.out.println("write: " + info.path + " sha256=" + info.sha256);

            WorkspaceFileList listing = sb.listFiles(".", true, 100, 0);
            System.out.println("workspace entries:");
            for (WorkspaceFileInfo entry : listing.entries) {
                System.out.printf("- %s %s (%d bytes)%n", entry.kind, entry.path, entry.size);
            }

            WorkspaceFileInfo meta = sb.statFile("shared/state.txt");
            String content = sb.readFileText("shared/state.txt");
            System.out.println("stat=" + meta.path + " sha256=" + meta.sha256);
            System.out.println("shared state via WorkspaceFS: " + content.trim());

            ExecResult r = sb.runPython(
                    "from pathlib import Path\n" +
                    "print(Path('shared/state.txt').read_text())\n"
            );
            System.out.printf(
                    "after exec: sandbox=%s (may change across Suspend/resume)%nruntime sees same workspace: %s%n",
                    sb.getSandboxId(), r.stdout.trim()
            );
        }
    }

    /**
     * 休眠创建 → 无容器写文件 → 懒启 Runtime → Suspend → 再唤醒。
     *
     * <p><b>Suspend 适用场景</b>
     * <ul>
     *   <li>Agent 多轮对话空闲期：保留工作区文件，但不持续占用容器配额/CPU。</li>
     *   <li>长流程等待用户确认或外部回调：逻辑 Session 未结束，Runtime 可先放下。</li>
     *   <li>给其他任务腾并发容器额度：Suspend ≠ {@code close()}（不是删除 Session）。</li>
     * </ul>
     *
     * <p><b>Suspend 之后心跳要不要特殊处理？</b>
     * <ul>
     *   <li>不需要停心跳，也不需要换 API。</li>
     *   <li>helper 内置心跳继续调用 {@code renewSession}：
     *       始终延长 Session TTL；若没有 active Runtime，服务端跳过沙箱 lease 续期（预期行为）。</li>
     *   <li>错误做法：Suspend 后停心跳 → Session 仍可能因 TTL 到期被回收。</li>
     *   <li>{@code close()}/{@code deleteSession} 才会销毁 Session；Suspend 只释放 Runtime。</li>
     *   <li>{@code close} 默认不删 Workspace（explicit_delete）；本 demo 结束后显式 deleteWorkspace。</li>
     * </ul>
     */
    private static void demoDormantSuspend(Client client) throws Exception {
        LOG.info("=== Session Sync: Dormant create + Suspend + resume ===");
        // workspace_retention=explicit_delete 与服务端默认一致；显式写出便于对照。
        SandboxSession sb = SandboxSession.open(
                client,
                "code-polyglot-basic",
                null,
                "explicit_delete",
                0,
                SandboxSession.DEFAULT_SESSION_TTL,
                SandboxSession.DEFAULT_RENEW_INTERVAL,
                SandboxSession.DEFAULT_RENEW_EXTEND,
                true
        );
        String workspaceId = sb.workspaceId();
        try {
            System.out.printf(
                    "after create: session=%s sandbox=%s workspace=%s (empty sandbox means dormant)%n",
                    sb.getSessionId(),
                    quote(sb.getSandboxId()),
                    workspaceId
            );
            if (sb.getSandboxId() != null && !sb.getSandboxId().isBlank()) {
                LOG.warning("expected empty sandbox_id right after create, got " + sb.getSandboxId());
            }

            // 无容器也可写文件；响应 JSON 含 environment=workspace。
            WorkspaceFileInfo info = sb.writeFile(
                    "notes/hello.txt",
                    "persisted before runtime\n".getBytes(StandardCharsets.UTF_8)
            );
            System.out.println("write while dormant: " + info.path);
            String etag = info.sha256 == null ? "" : info.sha256;

            // 条件写：If-Match 匹配时才覆盖。
            if (!etag.isBlank()) {
                info = sb.writeFile(
                        "notes/hello.txt",
                        "updated with If-Match\n".getBytes(StandardCharsets.UTF_8),
                        etag,
                        false
                );
                System.out.println("conditional write ok: " + info.path + " sha256=" + info.sha256);
            }

            // 首次执行：懒启 Runtime；helper 回填 sandboxId。
            ExecResult r = sb.runPython(
                    "from pathlib import Path\n" +
                    "print(Path('notes/hello.txt').read_text())\n"
            );
            System.out.printf("after first exec: sandbox=%s%nruntime sees file:%n%s%n",
                    sb.getSandboxId(), r.stdout);

            // Suspend：释放容器；Session/Workspace/心跳继续。
            Session suspended = sb.suspend();
            System.out.printf(
                    "after suspend: sandbox=%s active_sandbox_id=%s%n",
                    quote(sb.getSandboxId()),
                    quote(suspended.activeSandboxId)
            );

            // Suspend 期间仍可读写 Workspace；元数据用 stat。
            WorkspaceFileInfo meta = sb.statFile("notes/hello.txt");
            String content = sb.readFileText("notes/hello.txt").trim();
            System.out.println("stat after suspend: " + meta.path + " sha256=" + meta.sha256);
            System.out.println("read after suspend (no container needed): " + content);

            Session resumed = sb.resume();
            LOG.info("explicitly resumed session runtime=" + resumed.activeSandboxId);
            r = sb.runPython(
                    "from pathlib import Path\n" +
                    "print('resumed:', Path('notes/hello.txt').read_text().strip())\n"
            );
            System.out.printf("after resume: sandbox=%s%n%s%n", sb.getSandboxId(), r.stdout);
        } finally {
            // close：停心跳 + deleteSession + 放 Runtime。不会自动删 Workspace。
            sb.close();
            try {
                client.deleteWorkspace(workspaceId);
                System.out.println("explicit deleteWorkspace(" + workspaceId + ") after close — demo cleanup done");
            } catch (Exception e) {
                LOG.warning("deleteWorkspace after close (demo cleanup): " + e.getMessage());
            }
        }
    }

    private static String quote(String value) {
        return value == null || value.isBlank() ? "\"\"" : value;
    }

    private static void configureLogging() {
        Logger root = Logger.getLogger("");
        root.setLevel(Level.INFO);
        for (Handler h : root.getHandlers()) {
            h.setFormatter(new SimpleFormatter());
        }
    }

    private static String getenv(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? fallback : v;
    }
}
