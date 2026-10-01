# Genesis Sandbox Java SDK

Genesis Sandbox 的官方 Java 客户端 SDK。本仓库独立于服务端仓库 `genesis-sandbox` 演进，
服务端只维护 HTTP 协议与实现；Go SDK 同样在独立仓库 `genesis-sandbox-client-go` 维护。

发布坐标为 `io.genesis:genesis-sandbox-client-java:0.1.0`，Java 包名为
`io.genesis.sandbox`，最低 Java 11。HTTP 使用 JDK `HttpClient`，JSON 使用
Jackson 2.21 LTS；应用无需自行构造或解析 JSON。

## 文件

| 文件 | 说明 |
| --- | --- |
| `src/main/java/io/genesis/sandbox/Client.java` | Java 11+ 类型化 Client，覆盖 jobs / workspaces / sessions / session WorkspaceFS |
| `src/main/java/io/genesis/sandbox/SandboxModels.java` | 与 OpenAPI 对齐的请求、响应 DTO；公共类型不暴露 Jackson API |
| `src/main/java/io/genesis/sandbox/SandboxSession.java` | 同步 helper（休眠创建、懒启 Runtime、Suspend、心跳、WorkspaceFS） |
| `src/main/java/io/genesis/sandbox/AsyncSandboxSession.java` | 异步 helper（CompletableFuture） |
| `src/main/java/io/genesis/sandbox/SessionOptions.java` | 经过校验的 Session 参数构建器；profile / hints / resolutionId 互斥 |
| `src/main/java/io/genesis/sandbox/ExecResult.java`、`EffectiveEnvironment.java` | 执行结果值对象与生效环境 |
| `src/main/java/io/genesis/sandbox/SseStream.java`、`SseEvent.java` | SSE 日志流的增量解码与事件 |
| `src/main/java/io/genesis/sandbox/ApiException.java` | 带 status/error_code/request_id/details/retryable 的类型化 API 错误 |
| `src/main/java/io/genesis/sandbox/ExecRecoveryException.java` | 执行状态不确定时携带 sessionId、operationId 和可选 execId 的恢复异常 |
| `src/main/java/io/genesis/sandbox/TransportException.java` | 重试耗尽后的传输层错误（保留最后一次失败原因） |
| `src/main/java/examples/Quickstart.java` | 推荐应用入口 |
| `src/main/java/examples/ProductionSync.java` | `Session + WorkspaceFS` 主路径示例（含 Suspend / 边界说明） |
| `src/main/java/examples/JobArtifactSync.java` | `单次 Job + Artifact` 无状态链路示例 |
| `src/main/java/examples/ProductionAsync.java` | 异步并发示例；Suspend 完整注释见 ProductionSync |

## 编译与运行

```powershell
mvn package
```

仓库自带 Maven wrapper（`mvnw` / `mvnw.cmd`），CI 与无 Maven 环境可使用 `.\mvnw package`。
examples 只参与编译校验、不打进发布 jar；在检出本仓库后可直接编译运行
`examples/Quickstart.java`，应用侧则通过 Maven/Gradle 引入发布坐标后按其写法调用。
本地与服务端联调时先 `mvn install` 安装到本地仓库，再在应用中引用坐标。
Jackson 版本由 SDK 的 BOM 统一管理，不要在应用中单独混用不同 minor 版本。

可选环境变量：

```powershell
$env:GENESIS_SANDBOX_BASE_URL="http://127.0.0.1:18010"
$env:GENESIS_SANDBOX_API_KEY="test-token-1"
```

## 示例建议（避免误读）

应用代码优先使用 `try (SandboxSession sandbox = SandboxSession.open(...))`。请求失败时
捕获 `ApiException`，通过 `details()` 读取结构化详情，不要解析异常文本或原始 JSON。
执行提交或状态观察遇到不确定的网络错误时，捕获 `ExecRecoveryException`，用其
`sessionId()`、`operationId()` 和可选 `execId()` 调用 `getExecByOperation` 或重新观察。
若需跨进程恢复，调用 `runAsync` 时传入并自行保存稳定 `operationId`；SDK 不会因状态查询失败取消远端执行。
`Client.waitJob` 默认最多等待 5 分钟，也可传入
显式 `Duration`。异步 helper 的 `close()` 在删除失败时以 exceptional future 返回。
日志优先使用 `jobLogEvents` / `execLogEvents` 返回的 `SseStream`，并通过
try-with-resources 及时关闭。
复杂 Session 参数使用经过校验的 `SessionOptions.builder()`；profile、hints 和
resolutionId 采用“最后一次设置生效”的互斥语义。

`ProductionSync.java` 展示 `Session + WorkspaceFS` 主路径：

- 默认：`open` → 写文件/执行 → `close`。`suspend` 可释放运行容器，之后可显式 `resume`；首次命令也支持懒启动。
- `sandbox_id` 可空、可换；持久身份是 `session_id` / `workspace_id`。
- `ExecResult` 包含 errorCode、truncation 标志和 EffectiveEnvironment。
- Low-level `Client` 使用 `SandboxModels.SubmitJobRequest`、`JobResult`、`Session`、
  `ExecRecord`、`WorkspaceFileInfo` 等 DTO，不接受 `String jsonPayload`。
- `close`/`deleteSession` 在默认 `explicit_delete` 下**不会**删除 Workspace；需要时再 `deleteWorkspace`（demo 已演示）。

`JobArtifactSync.java` 只展示 `Job + Artifact`。

Session WorkspaceFS：`uploadSessionFile`（含 If-Match / createOnly）、`downloadSessionFile`、`listSessionFiles`、`statSessionFile`、`mkdirSessionDir`、`removeSessionFile`、`suspendSession`、`resumeSession`；helper 另有同名语义方法。

依赖构建必须绑定已解析环境：

```java
EnvironmentResolution resolution = client.resolveEnvironment(
        new ResolveEnvironmentRequest(
                EnvironmentSelector.hints(List.of("runtime.python"), true, null)));
BuildDependencyRequest request = new BuildDependencyRequest();
request.resolutionId = resolution.resolutionId;
request.language = "python";
request.lockfile = requirementsLock;
DependencyBuild build = client.buildDependencies(request);
```

如果从 Catalog 选择 Card，先使用
`EnvironmentSelector.exactProfile(card.name, card.profileRevision)` 精确 Resolve。
