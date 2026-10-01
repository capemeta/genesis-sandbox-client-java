# Changelog

All notable changes to this project will be documented in this file.

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
