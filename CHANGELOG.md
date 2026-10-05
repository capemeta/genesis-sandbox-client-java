# Changelog

All notable changes to this project will be documented in this file.

## v0.3.0 - 2026-10-01

- 所有写请求只发送一次，移除幂等键、operation_id、PUT/DELETE 的自动重试；只读查询保留有界重试。
- 续期失败暂停后续定时续期，DELETE 404 保留原 API 错误，不泛化为成功。

### Added

- `ExecRequest.trustedGovernance` 与 `Client.queryWorkspaceMaintenanceProof`，同步完整原调用、停止、来源与叶身份 DTO；治理查询单发、严格拒绝缺字段和身份漂移。

- 共享/隔离 WorkspaceBinding 闭合 DTO、approved storage inspect 和来源/硬预算事实校验；调用不接受宿主路径或自报 owner。
- WorkspaceView get/prepare/seal、会话历史、显式 purge，以及真实文件 executable 事实和同步/异步条件权限变更。
- `Client.lookupSession` 与 Session helpers 的原幂等键只读查询；404 不证明从未创建，不自动调用 create。
- Maven 独立消费验证工程：安装 0.3.0 构件后从实际 jar 导入公开 API，不借源码目录。

### Fixed

- 条件文件删除携带 If-Match，并禁止未知删除/权限变更自动重放。
- 承接既有 ExecResult 成功判定修复的回归测试；真实集成测试只在显式启用后检查非空 key 并创建 Client。
- 新控制 DTO 拒绝未知字段、重复 JSON 键和浮点强转整数；底层 OS 身份用 BigInteger 无损表示 uint64。

## v0.1.0 - 2026-10-01

### Added

- Java SDK extracted from the `genesis-sandbox` monorepo into this standalone repository; the monorepo now only keeps a migration notice under `sdks/java`.
- Typed `Client` covering jobs, workspaces, sessions, session WorkspaceFS, environment resolution, dependency builds, and SSE log streams.
- High-level sync/async session helpers (`SandboxSession`, `AsyncSandboxSession`) with lazy runtime start, suspend/resume, heartbeat, and WorkspaceFS semantics.
- Typed errors: `ApiException` (status/error_code/request_id/details/retryable) and `ExecRecoveryException` (sessionId/operationId/execId recovery).
- Contract tests, exec recovery tests, and exec status model tests; Maven wrapper and CI workflow.

### Changed

- Maven coordinates aligned with the repository name: `io.genesis:genesis-sandbox-client-java`.
- Compiler target fixed to Java 11 via `maven.compiler.release`; examples are compiled but no longer packaged into the published jar.
