# Java SDK 0.3.0 契约与独立构件验收

日期：2026-10-01。范围为 N19 的 Java SDK，不将本地协议测试视为容器隔离通过。

## 参照与已有修改

- SDK 基线：`2d06007ccfacdc57065fb5d056222bdbb2867bb6`。
- 参照当前 Genesis 服务 `internal/runtime/port/shared_storage.go`、`workspace_view.go`、`environment.go`、`types.go` 和 `internal/interfaces/http/router.go`、`handler/session.go` 的真实 HTTP v1 接口与字段。
- `ExecResult.java` 已有另一程序的修改，保留 `exitCode == 0 && errorCode.isEmpty()`，补回归测试，未覆盖或回滚。
- `IntegrationTest.java` 是已有未跟踪文件，保留其 15 项真实服务测试，修复门控初始化与遗漏的 lookup：原先用 getSession 代替 lookup，现直接查原幂等 key 并检查返回 key、原会话 ID 和不存在 key 的 404。

## 关键点、代码与验证

| 关键点 | 代码 | 有效验证 |
| --- | --- | --- |
| shared/isolated 封闭绑定，不接收宿主路径或请求自报 owner | `WorkspaceModels.WorkspaceBinding`、`CreateWorkspaceRequest`、`Client.createWorkspace` | 未知 owner/path 字段、非法引用、版本溢出、重复键、小数字面量拒绝 |
| 服务批准资源 inspect 保留 false 能力事实，不自动降级 | `Client.inspectSharedStorageResource`、`SharedStorageResource` | 本地 HTTP 实际路由、必需能力事实验证、原引用匹配；uint64 用 BigInteger 无损接收并拒绝越界/小数/来源冲突 |
| prepare/seal/history/purge 与 executable/mkdir | `Client`、`SandboxModels.WorkspaceFileInfo`、`SandboxSession`、`AsyncSandboxSession` | 实际路由、PATCH 请求与 If-Match/CAS 字段；prepare/seal/chmod/purge 不重试未知修改；现有 mkdir 接口复用 |
| 原会话恢复不创建新会话 | `Client.lookupSession`、同步/异步 Session | 原 key URL 编码、404 返回 null、503 有限只读重试、返回 key 冲突/未知字段拒绝；所有分支 create 次数为 0 |
| 执行失败与停止证明分别核验 | `ExecResult.ok`、`ExecStatusModelTest`、`ExecutionReceipts` | cancelled/timeout/oom/runtime error 回归；`ExecRecord.stopConfirmed` 保留服务显式 Boolean，不从终态或 exitCode 推导；原 session/exec/operation 冲突拒绝 |
| 所有写请求禁止自动重放未知副作用 | `Client.sendBytes` 最终方法门禁、`WriteRetryPolicyTest`、`ExecRecoveryTest` | create/exec/resume/renew/PUT/DELETE 在 503 后仅发送一次；带幂等键 job 和 operation_id 的执行也不重发，保留原恢复身份 |
| 续期未知暂停定时器；删除缺失不伪报成功 | 同步/异步 `renew`、`Client.sendBytes`、`WriteRetryPolicyTest`、已有 `IntegrationTest` | 实际 HTTP 503 后检查两种调度器任务取消/关闭；DELETE 404 保留 WORKSPACE_PATH_NOT_FOUND；真实集成 stat 缺失断言收紧为该原始 code/404 |
| 独立构件消费，不以源码编译冒充安装 | `verification/consumer` | Maven install 后独立工程依赖 0.3.0，反射检查公开方法，验证 CodeSource 实际为 Maven 仓库 JAR |

## 执行结果

JDK `25.0.4`，SDK 保留既有 `compiler.release=11`。以下命令均成功：

```powershell
.\mvnw.cmd install -q '-DargLine=-Djdk.net.unixdomain.tmpdir=D:/Work/workspace/go/sandbox/genesis-sandbox-client-java/target'
.\mvnw.cmd -f verification/consumer/pom.xml compile exec:java -q
```

最终测试共 53 项：38 通过、15 项真实服务门控跳过、0 失败。独立消费者输出的构件来源：

`file:/D:/Work/workspace/MavenSpace/io/genesis/genesis-sandbox-client-java/0.3.0/genesis-sandbox-client-java-0.3.0.jar`

首次默认 Maven 测试的本机 JDK25 内部 Unix-domain pipe 初始化报 `Invalid argument: connect`，导致本地 HttpClient/HttpServer 测试失败。按本机 JDK 实际 `UnixDomainSocketsUtil.getTempDir` 所读取的属性，用上述明确短临时目录重跑通过；未更换执行后端或在 SDK 自动降级。

## 待实机验证

- 当前没有启用 `GENESIS_SANDBOX_INTEGRATION=1`，15 项真实服务测试没有运行。真实容器 shared/isolated、硬配额、挂载、停止与异常恢复不能从本地 HTTP 测试推定通过。
- `lookupSession` 的 404 只表示服务当前未找到，不能证明从未创建；服务响应丢失或不可用时仍交由调用者核对，不自动 create。
- SDK 已传递服务的 `stop_confirmed` 持久事实；缺失或 false 不构成停止证明。SDK 本地 HTTP/DTO 测试没有核验服务进程或容器的物理停止，不能替代服务实机验证。
- 本轮仅安装本机 0.3.0 构件，没有公共推送或发布 tag。
