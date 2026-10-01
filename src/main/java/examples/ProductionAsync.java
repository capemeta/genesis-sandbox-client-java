package examples;

import io.genesis.sandbox.Client;
import io.genesis.sandbox.AsyncSandboxSession;
import io.genesis.sandbox.ExecResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.logging.*;

/**
 * Production-grade asynchronous SDK usage example for Java.
 *
 * Demonstrates:
 *   - CompletableFuture async chaining
 *   - Concurrent fan-out execution using CompletableFuture.allOf
 *   - Batch execution with Semaphore concurrency throttling
 *   - Proper resource cleanup under async context
 *   - dormant create / Suspend / resume ({@link #demoDormantSuspend}；心跳说明见该函数注释)
 */
public class ProductionAsync {
    private static final Logger LOG = Logger.getLogger(ProductionAsync.class.getName());

    public static void main(String[] args) throws Exception {
        configureLogging();

        String baseUrl = getenv("GENESIS_SANDBOX_BASE_URL", "http://127.0.0.1:18010");
        String apiKey  = getenv("GENESIS_SANDBOX_API_KEY",  "test-token-1");
        Client client  = new Client(baseUrl, apiKey);

        // Standard Executor for custom thread management
        ExecutorService ioExecutor = Executors.newFixedThreadPool(10);

        try {
            demoPythonAsync(client, ioExecutor).join();
            demoFanoutConcurrent(client, ioExecutor).join();
            demoBatchThrottled(client, ioExecutor).join();
            demoDormantSuspend(client, ioExecutor).join();
        } finally {
            ioExecutor.shutdown();
            if (!ioExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        }

        LOG.info("All async demos completed.");
    }

    private static CompletableFuture<Void> demoPythonAsync(Client client, ExecutorService executor) {
        LOG.info("=== Async: Python ===");
        return AsyncSandboxSession.open(client, "code-polyglot-basic", 300, executor)
                .thenCompose(sb -> sb.runPython("print('Async python task complete')")
                        .thenAccept(r -> System.out.println(r.stdout.trim()))
                        .exceptionally(ex -> {
                            LOG.severe("Job failed: " + ex.getMessage());
                            return null;
                        })
                        .thenCompose(v -> sb.close())
                );
    }

    private static CompletableFuture<Void> demoFanoutConcurrent(Client client, ExecutorService executor) {
        LOG.info("=== Async: Fan-out Concurrency ===");
        long start = System.currentTimeMillis();

        CompletableFuture<ExecResult> f1 = runOneAsync(client, "code-polyglot-basic", "print('Python job done')", executor);
        CompletableFuture<ExecResult> f2 = runOneAsync(client, "code-polyglot-basic", "echo 'Shell job done'", executor);
        CompletableFuture<ExecResult> f3 = runOneAsync(client, "code-polyglot-basic", "console.log('Node job done')", executor);

        return CompletableFuture.allOf(f1, f2, f3).thenAccept(v -> {
            try {
                System.out.println("F1: " + f1.get().stdout.trim());
                System.out.println("F2: " + f2.get().stdout.trim());
                System.out.println("F3: " + f3.get().stdout.trim());
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Error fetching concurrent result", e);
            }
            LOG.info("Fan-out completed in " + (System.currentTimeMillis() - start) + "ms");
        });
    }

    private static CompletableFuture<ExecResult> runOneAsync(
            Client client, String profile, String code, ExecutorService executor) {
        return AsyncSandboxSession.open(client, profile, 300, executor)
                .thenCompose(sb -> {
                    CompletableFuture<ExecResult> jobFuture = (profile.equals("code-polyglot-basic"))
                            ? sb.runShell(code)
                            : (profile.equals("code-polyglot-basic") ? sb.runNode(code) : sb.runPython(code));

                    // Make sure we close the Session helper when the job completes, regardless of success/failure
                    return jobFuture.whenComplete((r, ex) -> sb.close());
                });
    }

    private static CompletableFuture<Void> demoBatchThrottled(Client client, ExecutorService executor) {
        LOG.info("=== Async: Batch Throttled (6 tasks, max 3 concurrent) ===");

        Semaphore sem = new Semaphore(3);
        List<CompletableFuture<ExecResult>> futures = new ArrayList<>();

        for (int i = 0; i < 6; i++) {
            final int index = i;
            // Acquire slot before proceeding
            CompletableFuture<ExecResult> task = CompletableFuture.runAsync(() -> {
                try {
                    sem.acquire();
                } catch (InterruptedException e) {
                    throw new CompletionException(e);
                }
            }, executor).thenCompose(v ->
                runOneAsync(client, "code-polyglot-basic", "print('Task " + index + " complete')", executor)
                        .whenComplete((r, ex) -> sem.release())
            );
            futures.add(task);
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenAccept(v -> {
            for (int i = 0; i < futures.size(); i++) {
                try {
                    System.out.println("Task result " + i + ": " + futures.get(i).get().stdout.trim());
                } catch (Exception e) {
                    LOG.severe("Failed to read task result " + i + ": " + e.getMessage());
                }
            }
        });
    }

    /**
     * 异步版休眠 / Suspend / 再唤醒。
     *
     * <p>Suspend 场景与心跳规则与 {@code ProductionSync#demoDormantSuspend} 相同：
     * Suspend 只放 Runtime；心跳不要停（无 Runtime 时 renew 仍续 Session TTL）；
     * {@code close()} 才销毁 Session。
     */
    private static CompletableFuture<Void> demoDormantSuspend(Client client, ExecutorService executor) {
        LOG.info("=== Async: Dormant create + Suspend + resume ===");
        return AsyncSandboxSession.open(client, "code-polyglot-basic", 300, executor)
                .thenCompose(sb -> sb.writeFile(
                                "notes/async.txt",
                                "async persisted before runtime\n".getBytes(StandardCharsets.UTF_8)
                        )
                        .thenCompose(info -> {
                            System.out.println("write while dormant: " + info.path + " sha256=" + info.sha256);
                            System.out.printf("after create: session=%s sandbox=%s%n",
                                    sb.getSessionId(),
                                    sb.getSandboxId() == null || sb.getSandboxId().isBlank() ? "\"\"" : sb.getSandboxId());
                            return sb.runPython(
                                    "from pathlib import Path\n" +
                                    "print(Path('notes/async.txt').read_text())\n"
                            );
                        })
                        .thenCompose(r -> {
                            System.out.printf("after first exec: sandbox=%s%n%s%n", sb.getSandboxId(), r.stdout);
                            return sb.suspend();
                        })
                        .thenCompose(suspended -> {
                            System.out.printf(
                                    "after suspend: sandbox=%s active_sandbox_id=%s%n",
                                    sb.getSandboxId() == null || sb.getSandboxId().isBlank() ? "\"\"" : sb.getSandboxId(),
                                    suspended.activeSandboxId == null ? "\"\"" : suspended.activeSandboxId
                            );
                            return sb.readFileText("notes/async.txt");
                        })
                        .thenCompose(content -> {
                            System.out.println("read after suspend: " + content.trim());
                            return sb.resume();
                        })
                        .thenCompose(resumed -> {
                            LOG.info("explicitly resumed session runtime=" + resumed.activeSandboxId);
                            return sb.runPython(
                                    "from pathlib import Path\n" +
                                    "print('resumed:', Path('notes/async.txt').read_text().strip())\n"
                            );
                        })
                        .thenCompose(r -> {
                            System.out.printf("after resume: sandbox=%s%n%s%n", sb.getSandboxId(), r.stdout);
                            return sb.close();
                        })
                );
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
