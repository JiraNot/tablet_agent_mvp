# ADR-001: Local Native Android, Agent-first, Modular Core

Status: **Proposed — awaiting actual Android POC**

## Product boundary

**Single Android tablet owns files, agent runtime, local Node/other dev processes, Git and WebView localhost preview.** Cloud AI inference may require internet, but desktop/server are optional. Main UI is Native Compose, not web page/PWA.

## Modules

1. **app-ui (Kotlin / Jetpack Compose)**: Projects, Chat Composer, approval surfaces, Preview, Quick Config, Settings; tablet landscape and portrait layouts
2. **domain-core (Kotlin)**: Task state machine, agent manifests, project rules, verification policy, event order/replay; no Android dependencies
3. **application-services (Kotlin)**: Prompt context builder, queue, task supervisor, Git operation coordination, run recovery, notifications
4. **agent-adapters (Kotlin contracts)**: Codex app-server (JSON-RPC), Claude stream-json, OpenCode HTTP/SSE, optional AGY bridge; expose capabilities rather than fake generic parity
5. **local-runtime (Android + Linux userland)**: Native process/service lifecycle, Termux-compatible packages, file/system access, secure CLI credential storage
6. **persistence (Room/SQLite + files)**: Transactional runs, transcript index, event cursor, pending approvals, task evidence, settings (secrets in Keystore)
7. **preview (WebView + localhost)**: Package manager detection, command runner, per-project port reservation, HMR, error logs; no unrestricted outward binding
8. **tools (MCP adapters)**: MCP allowlist per project, skills, Git operations, terminal approval policy

## Language strategy

Primary **new product code: Kotlin** (Android UI, domain and application). Do **not** rewrite vendor CLI binaries. Retain a proven Go bridge only as isolated, licensed provisional adapter if verified; target pure Kotlin adapter once parity tests are present. This is a **single-language primary codebase**, not a claim that dependency/runtime binaries will contain only Kotlin.

Reasons: Android services, lifecycle and native UI directly align with Kotlin. Rewriting all six codebases into Kotlin is a high-risk, high-cost port and does not preserve vendor interoperability or tests automatically.

## Contract example

Agent Adapter: `capabilities`, `start`, `send`, `cancel`, `answerApproval`, `streamEvents`, `resume` (only if capability true).

Event envelope: `runId`, `sequence`, `timestamp`, `agentId`, `kind`, `payload`, `vendorEventId`.

Task stages: `QUEUED`, `PLANNING`, `WAITING_APPROVAL`, `EXECUTING`, `VERIFYING`, `COMPLETED`, `FAILED`, `CANCELLED`, `INTERRUPTED`.

Verification: `requiredGates: Set<String>` with evidence of exit status/log/artifact. A vendor's completed chat turn must **never** directly mark our task COMPLETED.

## Concurrency & correctness

- No two mutating agents on the same working directory; use worktree isolation or file locks.
- Separate read-only planning from mutating execution.
- `lastEventSequence` checkpoint and `since` based replay; idempotent writes and gap detection.
- Restart: reconcile vendor process and run status instead of guessing; unrecoverable processes mark INTERRUPTED.
- Settings defaults deny permission escalations; do not allow unconditional `danger-full-access`.
- Sensitive `.env` editor uses masked values and explicit reveal; write atomically and never feed secrets into prompts automatically.

## Process hierarchy

```
Android app / Compose
  Foreground Service: local agent runtime coordinator
    Agent Adapter: Codex App Server / Claude / OpenCode
    Local Linux (Termux-derived package runtime)
      Project files and Git
      Node.js dev server on 127.0.0.1:<assigned port>
  Android WebView -> 127.0.0.1:<assigned port>
  Room: runs / events / approvals / verification evidence
```

Note: an Android Foreground Service improves survivability but **does not guarantee** uninterrupted execution under battery, thermal, vendor task killers, or OS restrictions. Native process management requires actual-device tests.

## Design inspirations, not implementation inheritance

- AntiGem: Android local runtime and Codex event handling
- Happier: session/run protocol and capability manifest
- CloudCLI: provider/MCP/plugin settings
- Claudable: project/prompt/preview flow
- ForgeStudio: agent-first run/test/fix flow
- AionUI: process launch/health/recovery and skills UI

## Legal boundary

Never merge GPL-3.0/AGPL-3.0 code into a proprietary project without reviewing the resulting obligations. A Kotlin rewrite based closely on copyleft code can remain derivative. For commercial/proprietary goals, use independent specifications/contracts and obtain legal review (or implementation permissions) before shipping.
