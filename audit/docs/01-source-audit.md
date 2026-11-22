# Source Audit: พฤติกรรมจริงที่พบจาก GitHub

**As-of:** 2026-11-22 · **Confidence:** Preliminary source inspection (selected modules), not exhaustive runtime verification. Commit pins in `../repos.lock.json`.

| System | Source path examined | Grounded observation | Target reuse | Status |
|---|---|---|---|---|
| AntiGem | `agy_ide_bridge/pkg/codex/manager.go` | Codex `app-server` is driven through JSON-RPC (stdin/stdout), bridges notifications via numbered WebSocket events and supports replay `?since`; holds approval requests | Specification for event protocol + approval/continuity | Inspected, not executed |
| AntiGem | `agy_ide_bridge/pkg/codex/handlers.go` | `/api/codex/status`, `/start`, `/rpc`, `/requests`, `/ws` endpoints and requests/replies | Build adapter integration tests against real app-server | Inspected, not executed |
| AntiGem | `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml` | Native Kotlin Compose; ARM64 runtime, Android services/permissions; targetSdk recorded as 28 in inspected revision | Android package/process/runtime feasibility | Not installed |
| Happier | `packages/protocol/src/executionRuns.ts` | Explicit run ids, statuses, start/send/stop, transcripts and streaming schemas | Event/state schema reference | Inspected, not executed |
| Happier | `packages/agents/src/manifest.ts`, `apps/cli/src/session/` | Per-agent capability differences and session metadata/controls | Capability-aware adapter contract and session supervisor | Selected paths inspected |
| CloudCLI | `server/modules/`, `src/modules/` | Agent/provider, CLI, authentication, browser, sessions, settings, MCP, plugin and websocket modules separated | Settings ideas and provider orchestration; no code copy yet | Tree inspection |
| Claudable | `lib/services/preview.ts` | Per-project development process, package-manager detection, port allocation, preview state/logs | Design of Project Runtime Supervisor | Inspected, not executed |
| Claudable | `lib/services/cli/codex.ts` | Constructs Codex `exec --json`; requests `--dangerously-bypass-approvals-and-sandbox`; some status signals describe completed on failure | **Risk pattern: do not copy insecure defaults; separate agent result from verified task result** | Inspected, not executed |
| ForgeStudio | `server.py` | Single Python backend (~6,919 lines) with profiles, agents, command processes, preview, tools and approvals | Extract requirements/behavior, **avoid transplanting monolith** | Inspected, not executed |
| AionUI | `packages/web-host/src/backend-launcher.ts` | Process startup lifecycle, health checks, timeout diagnostics | Stable process supervisor reference | Inspected, not executed |
| AionUI | `packages/web-host/src/agent-process-registry.ts` | Process registry and cleanup behavior for stale child agents | Restart/recovery concerns | Inspected, not executed |

## Source links at pinned revisions

- [AntiGem Codex Manager](https://github.com/Santoshkurmi/antigem/blob/5a4141edcd8b17a08d73c807909c6b30359f7730/agy_ide_bridge/pkg/codex/manager.go)
- [AntiGem Codex API](https://github.com/Santoshkurmi/antigem/blob/5a4141edcd8b17a08d73c807909c6b30359f7730/agy_ide_bridge/pkg/codex/handlers.go)
- [Happier Execution Runs](https://github.com/happier-dev/happier/blob/d6ec446205b6911e251513500452aa51fb3affd5/packages/protocol/src/executionRuns.ts)
- [Happier Agent Manifest](https://github.com/happier-dev/happier/blob/d6ec446205b6911e251513500452aa51fb3affd5/packages/agents/src/manifest.ts)
- [CloudCLI Server Modules](https://github.com/siteboon/claudecodeui/tree/b6959148ec006f258d737e72bb630ff2e5e84672/server/modules)
- [Claudable Preview](https://github.com/anymorph-ai/Claudable/blob/2634077b0eecbf5affd1a8a88fe9f5b5bafde514/lib/services/preview.ts)
- [Claudable Codex](https://github.com/anymorph-ai/Claudable/blob/2634077b0eecbf5affd1a8a88fe9f5b5bafde514/lib/services/cli/codex.ts)
- [ForgeStudio server](https://github.com/DrBrainlessLol/ForgeStudio/blob/1a0a53bfee75937dae16ab8398f1e365b788ab0b/server.py)
- [AionUI Backend Launcher](https://github.com/iOfficeAI/AionUi/blob/e108b6b56b1a6eb99a00904878d0582f657b2908/packages/web-host/src/backend-launcher.ts)

## Risk register — ก่อนเขียน Production Code

| Priority | Risk | Test/mitigation |
|---|---|---|
| P0 | Android ARM64 dependencies incompatible with Termux ABI | Run one real `npm ci` on selected Next.js/Vite repositories; record native module failures |
| P0 | Android may terminate background processes | Foreground Service + notification + stop/reconnect test, no background uptime promises |
| P0 | Privileged CLI or unsafe defaults | Minimal privilege sandbox, explicit approval for destructive ops, no bypass flags by default |
| P0 | Turn/response success incorrectly treated as build success | Verify command exit code, test outcome and artifact evidence before COMPLETE |
| P0 | GPL/AGPL license obligations of copied or derived code | Source tracing/license bill of materials; legal review before redistribution |
| P1 | Native Android/browser tooling unavailable for Playwright/Chromium | Separate browser automation capability; test Android WebView/remote debugging alternative |
| P1 | Multiple agents can mutate same working tree | Per-run Git worktree or strict workspace locks |
| P1 | Token/credentials leakage from `.env` / logs / prompt attachments | Android Keystore, redaction, path restrictions, per-agent secret exposure controls |
| P1 | Session goes stale on service crash | Persist run state and sequence cursor; reconcile vendor session on restart |
| P2 | Provider quotas and account switching differ | Explicit capability manifest; fallback only via supported authenticated flows |

## Investigation that remains open

1. Clone each locked SHA and inventory all source files, licenses and tests.
2. Trace `UI -> store -> adapter -> process -> event -> persistence -> recovery` for 3 agents minimum.
3. Inspect existing test suites and execute them in isolated environments.
4. Run on **actual Android tablet** for ABI compatibility, storage scoped access, WebView and background behavior.
5. Produce compatibility and license decisions **before** selecting code for inclusion.
