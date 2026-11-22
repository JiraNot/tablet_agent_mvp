# Architecture decisions (MVP)

### ADR-001 — Native Android UI, on-device local runtime
Decision: UI in Jetpack Compose (Kotlin). Run development processes in Termux on **same Android tablet**; communicate only over `127.0.0.1:8765`. No remote dev server, no browser as shell. Preview uses embedded WebView by design.

Reason: Full embedded Linux runtime under Android is difficult and must be verified on physical devices. Termux initially provides a reliable userland and its official `RUN_COMMAND` integration; a future embedded native runtime must satisfy the same API contracts.

Tradeoff: two installed apps in MVP, Kotlin and Python rather than one implementation language. A later Kotlin-native bridge or migrated native Go bridge may remove Python, but should follow verified protocols.

### ADR-002 — Event log and task lifecycle
Events are stored with increasing SQLite sequence; client polls after cursor to reconnect. Task state is stored in SQLite. Starting agent sessions and completing work are separate transitions. A task can be `NEEDS_VERIFICATION`, `INTERRUPTED`, `BLOCKED_SETUP` or `FAILED`; no false completion when build/test is unavailable.

On startup any in-flight tasks are marked interrupted. The current implementation does not reattach orphaned Codex processes; do not auto-resume them without conflict detection.

### ADR-003 — Agent integration and continuation
MVP Codex `exec --json` for actionable proof-of-concept; persist `thread.started` ID on success; `codex exec ... resume` for followups. Later replace this adapter with official Codex `app-server` (streaming JSON-RPC and approval handoff) similar to AntiGem's design. Do not port AntiGem's GPL source without license review.

### ADR-004 — Security
Loopback-only binding, bearer token, Android Keystore storage, refuse file traversal/symlinks outside workspace, 256 KiB file edit limit, no shell construction from arbitrary project names, whitelist GitHub HTTPS clone URLs and do not use bypass-sandbox flags. OpenAI CLI may still view files inside workspace, including secrets; prompt rules are not a filesystem sandbox. For production, separate secrets and add executable-level file access controls.

### ADR-005 — Controlled use of OSS research
Happier informs session contracts; Claudable preview lifecycle; CloudCLI settings; ForgeStudio agent-first workflow; AionUI process recovery; AntiGem local runtime and JSON-RPC. No direct GPL/AGPL source bundled. Full clone, graph and test audit must precede substantive porting.

### ADR-006 — Android Foreground Service and WakeLock for Background Survival
Decision: Implement `AgentForegroundService` (foregroundServiceType="dataSync") with an ongoing persistent notification and a partial CPU WakeLock while tasks are actively running.
Reason: Android battery optimizations and OEM task killers aggressively kill background processes or put CPUs into deep sleep when the screen turns off. A foreground service with ongoing status notifications and an immediate "Cancel Task" action provides user transparency and execution survival.

### ADR-007 — Interactive Tool Approvals and Safe Defaults
Decision: Introduce an `approvals` table in SQLite, REST endpoints (`/v1/tasks/{id}/approvals`), and a native Compose banner.
Reason: Autonomous agents can propose destructive actions (e.g. deleting files, shell commands, or git pushes). The bridge pauses execution, marks task as `WAITING_APPROVAL`, and enforces a 120-second timeout with a secure-by-default denial if the user does not explicitly approve.
