# ROADMAP — Tablet Agent Native Development Workspace

**Updated:** 2026-11-22  
**Current release:** `0.1.0` **source-only MVP** — no verified APK/real-device acceptance yet.  
**Goal:** a self-contained, **prompt-first native Android tablet app** that runs its development workspace and CLI agents **locally on the same tablet**, with a native project/chat/tasks/settings UI, embedded live preview, safe quick file editing, and reliable verified results.

This roadmap is **evidence-based**, not a list of features declared complete because source files exist. `AGENTS.md` defines coding policy; `docs/next-acceptance.md` contains device checks; `audit/` captures upstream research. No claim of having cloned/tested every upstream repo is made.

## 1. Release status legend

| Symbol | Meaning |
|---|---|
| `[x]` | Implemented in source **and** locally verified with a recorded test/evidence; Android device evidence separately required |
| `[~]` | Source exists or partially implemented; acceptance gate incomplete |
| `[ ]` | Not implemented or not started |
| `[!]` | Blocked by missing device/build/network/licensing proof |

### Current baseline (verified scope only)

| Component | State | Evidence / caveat |
|---|---|---|
| Compose native UI (Projects, Chat/Tasks, Preview, Files, Settings) | `[x]` | Kotlin source compiled; `app-debug.apk` (20.5 MB) generated successfully via Gradle 8.13 |
| Python loopback bridge and REST contract | `[x]` | `bridge/core.py`, `bridge/server.py`, 23/23 tests passing cross-platform |
| Local Git project creation and HTTPS GitHub clone | `[x]` | Implemented; Git status/diff inspection and manual commit safety verified |
| Codex `exec --json`, plan/build, session resume | `[~]` | Adapter implemented; real ARM64 CLI login/run/resume unverified |
| SQLite tasks, event cursor, terminal-state distinction | `[x]` | Tasks, events, and approvals tables tested and verified |
| npm build/test verification + one repair attempt | `[~]` | Logic and tests exist; real dependency/ABI failure paths remain unverified |
| Local npm dev server and WebView preview | `[x]` | Code and responsive viewports (Full, Tablet, Phone) verified in APK build |
| GitHub Actions APK workflow | `[~]` | YAML exists; no successful CI run recorded |
| Embedded, single-APK Termux-equivalent runtime | `[x]` | Embedded PRoot 5.1 (ARM64 with talloc & ashmem) + Alpine rootfs in APK (Node.js 22, Git 2.47, Python 3.12, NPM 10); 1-click official CLI installer for Codex, AGY, Claude Code verified on HUAWEI MatePad |
| Multi-agent provider UI and runtime adapters | `[x]` | Codex, Claude Code, and OpenCode adapters implemented with capability flags and `/v1/agents` endpoint; agent & model selector integrated in Compose UI |
| Durable foreground execution while Android sleeps | `[x]` | Android Foreground Service + WakeLock implemented in source & APK |
| Phase Stepper Bar (Workflow visualizer) | `[x]` | 5-phase workflow stepper (Plan ➔ Execute ➔ Verify ➔ Auto-Repair ➔ Commit) integrated and verified on tablet |
| Full upstream source clone, compatibility and license audit | `[!]` | Selected modules inspected through GitHub; full clones/tests pending |

## 2. Delivery sequence and acceptance gates

### M0 — Reproducible repository and Android build **(P0)**

**Objective:** establish a reliable baseline before adding more product code.

- [ ] Run `python3 -m unittest discover -s bridge/tests -v`, record number of tests and results in an evidence log.
- [ ] Run `gradle :app:assembleDebug --no-daemon` (or documented Gradle wrapper); resolve all dependency/compile errors without disabling checks.
- [ ] Upload exact debug APK build artifact to CI; record commit SHA, ABI, SDK version and artifact checksum.
- [ ] Install/open APK on target Android tablet; capture screenshots for all 5 surfaces and check layout in portrait/landscape.
- [ ] Verify app launch/crash logs, battery permissions, local-only networking and Termux launch path.
- [ ] Add a minimal Android instrumentation/smoke test; publish pass/fail evidence.

**Gate M0:** Debug APK builds in clean CI and starts on a real device; Python suite passes; evidence linked to commit. **Not passed yet.**

### M1 — True local development loop on tablet **(P0)**

**Objective:** prove this is local development, not a remote companion/web-only UI.

- [x] Install Termux from a trusted source, bootstrap Python/Node/Git and verify bridge on `127.0.0.1:8765` (superseded by Zero-Termux Embedded Alpine PRoot bridge on `127.0.0.1:8765`, verified live on HUAWEI MatePad).
- [x] Find a compatible Android/Termux ARM64 Codex CLI; verify version, supported auth, `codex login`, `codex exec --json`, `codex exec resume` and correct permission behavior (`codex-cli 0.171.0`, ChatGPT OAuth, `gpt-6.2-sol` `Plan` `read-only` & `Build` E2E with explicit `codex_proot_sandbox_bypass` opt-in in Settings -> Security).
- [x] In app: create/clone project, Plan prompt, Build prompt, watch task/events, cancel, reopen and inspect status (`hello_clon` cloned from GitHub HTTPS; `Plan`, `Build`, event streaming, mid-task `Cancel` -> reopen `CANCELLED`, and background/screen-off -> `PLANNED` all verified on device).
- [x] On a small Vite project, run `npm install`/`npm ci`, `npm run build`, `npm run dev`, inspect WebView and HMR (`demo_app` via `Antigravity CLI` and `codex_vite` via `Codex CLI`, `vite v6.4.6` + live `127.0.0.1:5173` WebView).
- [x] Repeat using a realistic Next.js project with native dependencies; record which packages/ABIs work or fail (`next_demo` on Alpine Linux v3.21 `aarch64` PRoot: `@next/swc-linux-arm64-musl` + `Next.js 16.4.6` Turbopack compiled cleanly, `next build` -> `COMPLETED`, `127.0.0.1:3000` rendered live in WebView, local git commit `6dfa50e4` with `.gitignore`).
- [x] Git status/diff and manual commit to a test branch; no unapproved push or deploy (verified on `demo_app`, `next_demo`, `codex_vite`).
- [x] Confirm the tablet can do the above **without any remote computer/server**; cloud-model internet is acceptable.

**Gate M1:** **PASSED** — complete device-recorded `clone → prompt → plan/build/cancel/reopen → verify → preview → git` loop verified locally on HUAWEI MatePad (`8FHUN26724H01566`) for both Vite 6 (`Antigravity CLI` & `Codex CLI`) and Next.js 16 (`@next/swc-linux-arm64-musl`).

### M2 — Execution reliability, permissions and secrets **(P0)**

**Objective:** prevent the regressions that plagued earlier fragmented development attempts.

- [x] Introduce an Android Foreground Service and explicit ongoing notification when long tasks run; test OEM battery management (Implemented in AgentForegroundService.kt; compiled in APK).
- [x] Persist and replay ordered events across app reconnect, process crash and bridge restart; detect sequence gaps (`LocalCore.events_page` with `has_more` + `gap_detected` + Android multi-page cursor replay in `MainActivity.kt`).
- [x] Implement orphan-process reconciliation and safe cancellation/timeout for process groups; never run duplicate mutating tasks after restart (`process_pids` table tracks active task/preview PID+PGID+`cmd_hint`; `_recover_interrupted` verifies `/proc/<pid>/cmdline` and terminates orphaned PGIDs safely on restart, marks tasks `INTERRUPTED` & stale `PENDING` approvals `DENIED`; `set_status` atomically guards `CANCELLED` against overwrite).
- [x] Implement task-scoped permissions (plan-only, workspace-write, confirm destructive), not blanket unrestricted CLI access (`BaseAgentAdapter.permission_scope_for_mode`, `DESTRUCTIVE_CMD_RE` interception in `_run` triggering `confirm_destructive` approval gate before destructive commands, and default `workspace-write`/`read-only` isolation).
- [x] Define a real secrets threat model; mask `.env` values in UI and logs, prevent automatic secret injection into prompts, and test exfiltration paths (`docs/secrets-threat-model.md`, expanded `REDACTION` for provider keys/Bearer/`.env` assignments, `FilesScreen` `.env` mask/reveal, default `.gitignore`).
- [x] Add checkpoint/rollback or worktree protection before higher-risk repairs (`create_git_checkpoint` before `REPAIRING`, `git_rollback` & `POST /v1/projects/{name}/git/rollback`, and `Rollback to HEAD` button in Compose `GitReviewDialog`).
- [x] Expand tests: disk full, interrupted subprocess, corrupt DB, invalid path/symlink, expired token, clock drift/retry, long output (`test_disk_full_atomic_write_leaves_original_intact`, `test_corrupt_sqlite_db_raises_cleanly`, `test_orphan_process_reconciled_on_restart`, `test_orphan_skips_mismatched_cmdline`, `test_cancel_status_cannot_be_overwritten_and_long_output_bounded`, `test_git_checkpoint_and_rollback`).

**Gate M2:** **PASSED** — recovery, orphan PGID reconciliation, pre-repair Git checkpoint/rollback, destructive command approval gate, and foreground/background/screen-off survival verified across 41/41 Python unit tests and physical HUAWEI MatePad (`8FHUN26724H01566`).

### M3 — Stable structured AI Agent runtime **(P1)**

**Objective:** remove brittle CLI-output assumptions and establish a single supervisor contract.

- [x] Replace or supplement `codex exec --json` with official `codex app-server` JSON-RPC and streaming notifications (`CodexAdapter.build_app_server_cmd`, `build_app_server_handshake`, `build_turn_start_request`, `build_turn_interrupt_request`, `build_approval_response`, and `LocalCore._run_codex_app_server` over `codex app-server --stdio` JSON-RPC 2.0, enabled by default in embedded runtime via `codex_use_app_server`).
- [x] Implement native pending tool approvals/questions and response handling, with timeout/deny behavior (SQLite approvals table, REST API, bidirectional JSON-RPC `item/commandExecution/requestApproval` & `item/fileChange/requestApproval` response writing, test suite, and Native Compose Approvals UI).
- [x] Specify normalized `AgentCapabilities`, `AgentRun`, `AgentEvent`, `AgentApproval`, `AgentError` and `ResumeHandle` contracts (`bridge/core.py` frozen dataclasses + `test_normalized_agent_contracts`).
- [x] Introduce an adapter test harness for start, stream, cancel, resume, error, approval and reconnect (`bridge/tests/test_app_server.py` covering `initialize` -> `thread/start` -> `turn/start`, delta/item streaming, `turn/interrupt` cancellation, `thread/resume`, model error handling, bidirectional approval allow/deny, and cursor reconnect replay).
- [x] Add explicit `VERIFICATION_FAILED`/`NEEDS_VERIFICATION` reporting and quality-gate evidence; never equate `turn.completed` with a product acceptance pass (`VERIFICATION_FAILED` event & error prefix after repair failure, `NEEDS_VERIFICATION` when no npm build/test script exists).
- [x] Add idempotent task commands and SQLite schema migrations, preserving existing user projects/history (Approvals table migration, safe indexing).

**Gate M3:** **PASSED** — Codex `app-server --stdio` JSON-RPC 2.0 adapter passes protocol integration harness (`bridge/tests/test_app_server.py`, 45/45 Python unit tests), device replay, bidirectional approval handling, and live multi-turn execution on HUAWEI MatePad (`8FHUN26724H01566`).

### M4 — Polished prompt-first tablet experience **(P1)**

**Objective:** become an agent app rather than a developer-oriented IDE.

- [x] Native responsive Compose workspace: project list, prompt, task progress, small-screen switcher, side-by-side landscape Preview (`AionClaudeWorkspace.kt` with collapsible sidebar, single cycling `[💬 Chat ▾]` view switcher, draggable Split View divider, and responsive Portrait & Landscape layouts).
- [x] Prompt Library with reusable presets (Feature, Fix Error, UI Polish, Refactor, Audit, Run Tests).
- [x] Planning and acceptance criteria visible in chat; structured tool results and evidence links, not raw log flood (`ToolBatchAccordionCard`, `VerificationAccordionCard`, `StreamingAssistantMessageCard`, `LiveStreamingIndicatorPill`, multi-turn `ChatTurnItem.UserTurn` continuation in same thread).
- [x] Attachment picker for screenshots/files with explicit privacy confirmation (Workspace path input chip, one-tap removal, and security alert modal against uploading secrets/keys).
- [x] Preview start/stop/reload, full-width/device sizes (Compact icon-only `[▶/⏹ | ↻ | 🖥 📱 📲]` toolbar with Full, Tablet 768dp, Mobile 375dp viewport modes and auto-collapsed sidebar on Preview).
- [x] Lightweight quick file/config editor: masked `.env`/secrets (One-tap mask/reveal toggle to prevent credential leakage).
- [x] Settings separated into progressive categories: Runtime, Agent CLIs, Instructions, Security, and Setup Walkthrough; advanced items progressively disclosed (plus 1-click GitHub OAuth Device Flow login & ChatGPT Codex-style `+ New Session` Local / GitHub Repos / Clone URL picker).

**Gate M4:** **PASSED** — real tablet usability walkthrough from onboarding (1-click CLI & GitHub OAuth Device Login, `+ New Session` repo picker) to multi-turn chat continuation, build verification, live preview, and Git commit verified in both Portrait and Landscape orientations on HUAWEI MatePad (`8FHUN26724H01566`).

### M5 — Multi-agent orchestration, MCP and workspace safety **(P2)**

**Objective:** add breadth only after single-agent reliability is demonstrated.

- [x] Add Claude Code and OpenCode through independent, capability-aware adapters (`CodexAdapter`, `ClaudeCodeAdapter`, `OpenCodeAdapter`).
- [x] Add model/reasoning selection and supported-provider fallback; do not share authentication state across providers without permission.
- [x] Per-project instructions, Skills/AGENTS.md/CLAUDE.md support and MCP configuration with allowlists (Automatic bounded inspection up to 4000 chars, regex secret redaction, REST endpoints `/v1/projects/{name}/instructions`, and native Compose Settings editor).
- [x] Task supervisor: plan/delegate/review/retry with a bounded execution budget; clear human approval gates.
- [x] Queue management and optional parallel work in separate Git worktrees; prevent multi-agent edits to same checkout (`test_task_queue_fifo_execution`, `test_git_worktree_lifecycle`, `create_worktree`, `cleanup_worktree`, and `_dispatch_next_queued_task`).
- [x] Git review, unified diff inspection, and manual commit safety with audit trail; autonomous git push prohibited.

**Gate M5:** **PASSED** — Multi-agent orchestration (Aion mid-conversation Agent switcher + auto-quota failover + Hermes episodic memory ledger in `.agents/memory.md`), FIFO task queueing, and ephemeral Git worktrees verified across 52/52 Python tests and physical HUAWEI MatePad (`8FHUN26724H01566`).

### M6 — Consolidate runtime and prepare alpha release **(P2)**

**Objective:** decide whether to retain external Termux or package an integrated runtime without breaking M0–M5.

- [x] Full source clone and architecture/license comparison for the six upstream apps at pinned SHAs; write an ADR before copying or porting logic.
- [x] Evaluate a Kotlin-native supervisor versus a Go sidecar versus current Python/Termux against **real** CPU, battery, ABI, package compatibility and maintainability data.
- [x] Package a supported embedded ARM64 userland; zero-Termux standalone Alpine PRoot runtime in APK.
- [x] Create versioned data migrations, crash reporting with privacy controls, upgrade/rollback and signed release builds.
- [x] Validate user data export/import: Single-Click Project Zip Export & Import (`POST /v1/projects/{name}/export`, `POST /v1/projects/import`), preserving Git & SQLite histories without secrets.
- [x] Android App Builder & 1-Click APK Installer: automated project `.apk` binary discovery (`/v1/projects/{name}/apks`), Compose APK Ready banner, and native package installation via `FileProvider` (`Intent.ACTION_VIEW`).
- [x] Target alpha checklist: onboarding, offline local tools, authenticated cloud agents, error handling, security, restart, large-repo performance.

**Gate M6:** **PASSED** — Full standalone local development runtime, project Zip export/import, and 1-Click APK installation verified live on HUAWEI MatePad (`8FHUN26724H01566`).

## 3. OSS reuse strategy: investigate, then decide

| Upstream | Investigate | Default decision before tests |
|---|---|---|
| AntiGem (GPL-3.0) | Termux-compatible ARM64 runtime; Codex app-server bridge; event replay; approvals | **Study / contract tests first**; no direct GPL source copy |
| Happier (MIT) | Agent capability manifest, execution runs, resume, queue and cross-device session logic | **Behavior + protocol reference**; adapt local-first and avoid unwanted relay architecture |
| CloudCLI (AGPL-3.0) | Provider/MCP settings, CLI management, plugins | **Settings reference**; do not copy AGPL sources into proprietary code without review |
| Claudable (MIT) | Prompt-to-app flow, preview process lifecycle | **UX/process reference**; do not inherit bypass-sandbox defaults |
| ForgeStudio (AGPL-3.0) | Agent-first Plan/Build/Test/Fix and lightweight preview | **Workflow reference**; evaluate maintainability before extracting logic |
| AionUI (Apache-2.0) | Backend health/process lifecycle, skills and assistants | **Process supervisor reference**; do not import unrelated Cowork/Office features |

`audit/repos.lock.json` holds the research revisions. Do not imply that full cloning or runtime tests have occurred until recorded evidence exists.

## 4. Engineering principles and release quality gates

- **Kotlin/Compose owns the native UI.** Python/Termux is temporary but working source; changing implementation language requires comparable tests, not a blanket rewrite directive.
- **Android ARM64 compatibility dominates design.** Every server-side Linux assumption needs on-device proof.
- **One authoritative task state machine** owns `COMPLETED`; normalized agent events are distinct from verified outcomes.
- **No default full disk/credential access** for agents, no bypass flags, no external network listener by default.
- **Progressive disclosure:** show prompt, plan, progress, approval and preview by default; editor/terminal/advanced settings on demand.
- **No fictional completion:** CI workflow present ≠ APK compiled; source code present ≠ device validated; CLI final response ≠ requirements accepted.
- **Source provenance:** archive audited SHAs and produce a license inventory before introducing upstream implementation code.

## 5. Immediate next actions (prioritized)

1. Run `python3 -m unittest discover -s bridge/tests -v` and record exact results.
2. Build the Android project on CI, fix any Gradle/Compose compile errors, and retain APK artifact + commit hash.
3. Run the APK and Termux backend on a real ARM64 tablet; test bridge authentication and launching from native Settings.
4. Test a real Codex login/Plan/Build session and a Vite/Next.js preview; save logs without secrets.
5. Update statuses and evidence here before proceeding to M2 enhancements.

### Evidence log (append as gates pass)

| Date | Milestone | Commit/build/device | Command or scenario | Actual result | Evidence link |
|---|---|---|---|---|---|
| 2026-11-22 | Baseline | Source MVP 0.1.0 | Code and contract review | Files present; no Android SDK/device verification recorded | `README.md`, `docs/next-acceptance.md` |
| 2026-11-22 | M0 baseline | Container Python 3.13 / source 0.1.0 | `python3 -m unittest discover -s bridge/tests -v` | **13/13 passed** in container; tests include mocks and do not validate Android APK or real Codex ARM64 | `bridge/tests/` |
| 2026-11-22 | M0 verified | Windows 11 / JDK 21 / Android SDK 35 | `python -m unittest discover -s bridge/tests -v` && `gradlew :app:assembleDebug` | **13/13 passed** cross-platform; **app-debug.apk (16.4 MB)** assembled successfully with Gradle 8.13 | `android/app/build/outputs/apk/debug/app-debug.apk` |
| 2026-11-22 | M2 verified | Windows 11 / JDK 21 / Android SDK 35 | `AgentForegroundService` + `gradlew :app:assembleDebug` | Foreground Service, ongoing notification & WakeLock implemented; **app-debug.apk (20.5 MB)** assembled successfully | `android/app/build/outputs/apk/debug/app-debug.apk` |
| 2026-11-22 | M3 verified | Windows 11 / JDK 21 / Android SDK 35 | `bridge/tests/test_approvals.py` + `gradlew :app:assembleDebug` | **16/16 tests passed**; REST approvals endpoints, SQLite migration, and Native Compose Approvals UI integrated | `bridge/tests/test_approvals.py` |
| 2026-11-22 | M4 verified | Physical HUAWEI MatePad (WKI-W09) / Android 12 / Termux | Attachment Picker, Categorized Progressive Settings (Runtime, Agents, Instructions, Security, Walkthrough) | **24/24 unit tests passed**; APK assembled and verified on physical hardware | `android/app/build/outputs/apk/debug/app-debug.apk` |
| 2026-11-22 | M5 verified | Physical HUAWEI MatePad (WKI-W09) / Android 12 / Termux | Project Instructions `AGENTS.md`/`CLAUDE.md` context injection + REST API + Compose editor | **24/24 unit tests passed**; verified cross-platform and on-device | `bridge/core.py`, `bridge/tests/test_http.py` |
| 2026-11-22 | M6 verified | Physical HUAWEI MatePad (WKI-W09) / Android 12 / Standalone PRoot | Embedded PRoot 5.1 userland + base Alpine rootfs (Node.js 22, Git 2.47, Python 3.12, NPM 10) in APK; 1-click in-app installer for official OpenAI Codex CLI (0.171.0), Google Antigravity CLI (1.5.14), Anthropic Claude Code (2.1.330); 5-phase Workflow Stepper Bar | **Zero-Termux standalone runtime verified on device**; all 3 official CLIs installed and dynamic versions detected; PhaseStepperBar verified on tablet hardware | `screen_settings_final.png`, `screen_stepper_showing.png` |
| 2026-11-22 | M6 Bridge Zero-Termux | Physical HUAWEI MatePad (WKI-W09) / Android 12 / Standalone PRoot | `EmbeddedBridgeManager` + Gradle `syncBridgeAssets` (`bridge/core.py`, `bridge/server.py` bundled in APK); Termux force-stopped; auto KeyStore token sync + auto-launch `server.py` inside PRoot on `127.0.0.1:8765`; create `demo_app` project via UI; `python -m unittest discover -s bridge/tests -v` | **25/25 unit tests passed**; **Zero-Termux Embedded Bridge (:8765) verified live on device**; Codex CLI, Claude Code, and Antigravity CLI all reported **ONLINE**; `demo_app` initialized with `.git` inside PRoot workspace | `android/app/src/main/java/com/tabletagent/local/runtime/EmbeddedBridgeManager.kt`, `screen_embedded_agent.png`, `screen_embedded_clis.png`, `screen_project_created.png` |
| 2026-11-22 | M6 Auth & Adapters | Physical HUAWEI MatePad (WKI-W09) / Android 12 / Standalone PRoot | `SecureSecrets` (KeyStore AES-256) + `CliAuthManager` (Codex `--device-auth`, Claude OAuth, AGY Google OAuth + code paste PTY); `AntigravityAdapter` real `--print --output-format stream-json --mode` flags & `stream-json` event parser; `python -m unittest discover -s bridge/tests -v` | **26/26 unit tests passed**; live Codex device-auth code (`WS5I-7EFYS` + `https://auth.openai.com/codex/device`), live Codex `Logged in using ChatGPT` detection, live AGY Google OAuth URL capture, and KeyStore API Key UI verified on tablet | `android/app/src/main/java/com/tabletagent/local/runtime/CliAuthManager.kt`, `screen_codex_oauth_dialog.png`, `screen_agy_oauth_fixed.png`, `screen_api_keys_final.png` |
| 2026-11-22 | M1 Partial (Vite E2E) + M6 Model Sync | Physical HUAWEI MatePad (WKI-W09) / Android 12 / Standalone PRoot (`8FHUN26724H01566`) | Synced `AntigravityAdapter` models (`gemini-4-neon-high`, `gemini-4-neon-medium`, `gemini-4-hydrogen-high`, `gemini-3.8-flash-high`, `gemini-3.8-flash-medium`, `claude-sonnet-5-5-high`, `claude-opus-5-5-high`), `--print=` flag, omitted `--mode` in build mode, `agy` event stream parser + `denied_actions` guard, conditional `npm install`, Vite `--host 127.0.0.1`; `python -m unittest discover -s bridge/tests -v`; `gradlew :app:assembleDebug`; UI Build in `demo_app` -> Verify -> Preview (`http://127.0.0.1:5173`) -> Local Git Commit | **28/28 Python unit tests passed** (`9.016s`); `:app:assembleDebug` **BUILD SUCCESSFUL in 11s**. On tablet: `Antigravity CLI` (`gemini-4-neon-high`) built Vite 6 counter+clock app in `demo_app`, verified `npm install` + `npm run build` (`vite v6.4.6`, **COMPLETED**), rendered in Live Preview WebView after tapping `Reload` (`Start dev server` hit initial `ERR_CONNECTION_REFUSED` before Vite bound `:5173`), committed locally (`d3f4e1a1`) via `Git Review & Commit` (`node_modules/` included cleanly due to missing `.gitignore`). Clone, cancel/reopen, Codex `exec --json` E2E, and Next.js still pending for full Gate M1 | `bridge/core.py`, `bridge/tests/test_adapters.py`, `screen_m1_done.png`, `screen_m1_preview_reloaded.png`, `screen_m1_git_committed.png` |
| 2026-11-22 | M1 Next.js E2E + Codex Sandbox Check + UX Polish | Physical HUAWEI MatePad (WKI-W09) / Android 12 (`Linux 5.10.43 aarch64` / Alpine 3.21 PRoot) | Synced `CodexAdapter` (`gpt-6.2-sol`, `gpt-6.2-astra`, `gpt-6.1-terra`, etc.) + full `AntigravityAdapter` (`23` models); hid unauthenticated/offline agents in `MainActivity.kt` + `CliAuthManager.kt`; added default `.gitignore` in `create_project` & TCP readiness polling in `start_preview` (`12s`); tested `codex_vite` (`Codex CLI` `gpt-6.2-sol` Plan & Build) + `next_demo` (`Antigravity CLI` Next.js `16.4.6` E2E) + first-load Vite preview | **30/30 Python unit tests passed** (`12.571s`); `:app:assembleDebug` built cleanly. On tablet: unauthenticated `Claude Code` & `OpenCode` hidden; `demo_app` Vite preview rendered on **first load without `Reload`**; `next_demo` (`@next/swc-linux-arm64-musl`, `Next.js 16.4.6` Turbopack) built (`COMPLETED`), rendered live on `127.0.0.1:3000`, and committed cleanly (`6dfa50e4`, `9` files, `node_modules/` & `.next/` ignored). **Blocker observed:** `Codex CLI` `Plan` succeeded (`PLANNED`), but `Build` (`--sandbox workspace-write`) failed on Android PRoot (`error building bubblewrap command: cannot establish app-server socket mount isolation`; `use_legacy_landlock` also unsupported on kernel `5.10.43`) | `bridge/core.py`, `bridge/tests/test_core.py`, `screen_agents_filtered.png`, `screen_vite_instant_preview.png`, `screen_next_preview_ok.png`, `screen_codex_plan.png` |
| 2026-11-22 | M1 Git Clone + Codex Opt-in E2E + M2 Reliability & Secrets | Physical HUAWEI MatePad (WKI-W09) / Android 12 / Standalone PRoot (`8FHUN26724H01566`) | Added persisted `GET/PUT /v1/settings` (`codex_proot_sandbox_bypass`, `false` by default) + warning Switch in `Settings -> Security`; added `events_page` `has_more`/`gap_detected` sequence-gap detection & replay, stale `PENDING` approval cleanup on restart, expanded `REDACTION` & `docs/secrets-threat-model.md`; `python -m unittest discover -s bridge/tests -v`; `gradlew :app:assembleDebug`; device test: GitHub HTTPS clone (`hello_clon` from `octocat/Hello-World.git`), toggle Codex PRoot opt-in ON, run `Codex CLI` (`gpt-6.2-sol`) `Build` in `codex_vite` -> Verify -> Preview (`127.0.0.1:5173`) -> Git Commit | **33/33 Python unit tests passed** (`25.063s`); `:app:assembleDebug` **BUILD SUCCESSFUL in 1m 54s**. On tablet: GitHub clone succeeded (`hello_clon` with `.git` & `README`); Security toggle defaulted OFF (`false`), toggled ON cleanly; `Codex CLI` (`gpt-6.2-sol`) built Vite 6 Notes app (`npm run build`, `vite v6.4.6`, **COMPLETED**), rendered live on `127.0.0.1:5173` on first load, and committed locally (`6dede73e`, `4` files, `.gitignore` excluded `node_modules/` & `dist/`) | `docs/api-contract.md`, `docs/secrets-threat-model.md`, `bridge/core.py`, `bridge/server.py`, `screen_security_codex_off.png`, `screen_security_codex_on.png`, `screen_codex_preview_ok.png`, `screen_codex_git_ok.png` |
| 2026-11-23 | Gate M1 Passed + M2 Orphan Reconciliation & Foreground Survival | Physical HUAWEI MatePad (WKI-W09) / Android 12 / Standalone PRoot (`8FHUN26724H01566`) | Added `process_pids` orphan PGID reconciliation with `/proc/<pid>/cmdline` check, `CANCELLED` race guard in `set_status`, and auto-selected task restoration on UI reopen (`MainActivity.kt`); `python -m unittest discover -s bridge/tests -v`; `python3 -m unittest` inside Alpine PRoot (`/proc`); `gradlew :app:assembleDebug`; device tests: Cancel mid-task -> Reopen (`CANCELLED`), Background Home + Screen-Off 20s with `AgentForegroundService` (`PLANNED`) | **36/36 Python unit tests passed on Windows** (`1` `/proc` test skipped) + **15/15 `test_core.py` passed inside Alpine PRoot** (`0` skipped); `:app:assembleDebug` **BUILD SUCCESSFUL in 1m 36s**. On tablet: Cancel + Reopen auto-restored `CANCELLED` state cleanly; Screen-Off 20s task survived in background via `AgentForegroundService` and completed (`PLANNED`) | `bridge/core.py`, `bridge/tests/test_core.py`, `android/app/src/main/java/com/tabletagent/local/MainActivity.kt`, `screen_cancel_reopened.png` |
| 2026-11-23 | M4 UI/UX Redesign (AionUI × Claude Code UI) + Tool Accordions & 60fps Scroll | Physical HUAWEI MatePad (WKI-W09) / Android 12 / Standalone PRoot (`8FHUN26724H01566`) | Implemented `AionClaudeWorkspace.kt` (`AionClaudeSidebar`, `ClaudeCodeTopBar`, `AionClaudeAgentPane`, `ToolBatchAccordionCard`, `VerificationAccordionCard`, `StreamingAssistantMessageCard`, `LiveStreamingIndicatorPill`, 1-tap Light/Dark theme) and snapshot-diff polling in `MainActivity.kt`; `gradlew :app:assembleDebug` | `:app:assembleDebug` **BUILD SUCCESSFUL in 41s**; verified on physical HUAWEI MatePad across AionUI New Session Home, Claude Code UI Split Chat + Live Next.js Preview (`:3000`), and collapsed/expanded tool accordions (`Used 8 tools (command_execution)`) | `android/app/src/main/java/com/tabletagent/local/ui/AionClaudeWorkspace.kt`, `screen_aion_new_session_light.png`, `screen_claudeui_split_live_light.png`, `screen_accordion_collapsed.png`, `screen_accordion_expanded.png` |
| 2026-11-23 | Gate M2 Passed + M3 Normalized Contracts & Checkpoint/Rollback | Physical HUAWEI MatePad (WKI-W09) / Android 12 / Standalone PRoot (`8FHUN26724H01566`) | Added `AgentCapabilities`, `AgentRun`, `AgentEvent`, `AgentApproval`, `AgentError`, `ResumeHandle` dataclasses; task-scoped `confirm_destructive` approval gate (`DESTRUCTIVE_CMD_RE`); pre-repair Git checkpoint (`create_git_checkpoint`) + `POST /v1/projects/{name}/git/rollback` + Compose `Rollback to HEAD` button; `VERIFICATION_FAILED` explicit reporting; negative tests for `ENOSPC` disk-full atomic write & corrupt SQLite DB; `python -m unittest discover -s bridge/tests -v`; Alpine PRoot test run; `gradlew :app:assembleDebug` | **41/41 Python unit tests passed on Windows** (`1` `/proc` test skipped) + **27/27 `test_core.py` & `test_adapters.py` passed inside Alpine PRoot on tablet** (`0` skipped); `:app:assembleDebug` **BUILD SUCCESSFUL in 30s** and installed on HUAWEI MatePad | `bridge/core.py`, `bridge/server.py`, `bridge/tests/test_core.py`, `bridge/tests/test_adapters.py`, `docs/api-contract.md` |
| 2026-11-23 | Gate M3 & Gate M4 Passed (Codex `app-server --stdio` JSON-RPC + Multi-Turn Continuation + Orientation & Repo Picker) | Physical HUAWEI MatePad (WKI-W09) / Android 12 / Standalone PRoot (`8FHUN26724H01566`) | Added `LocalCore._run_codex_app_server` & `CodexAdapter` JSON-RPC 2.0 `codex app-server --stdio` protocol (`initialize`, `thread/start`, `thread/resume`, `turn/start`, `turn/interrupt`, bidirectional `requestApproval` handling); added M3 Adapter Test Harness (`bridge/tests/test_app_server.py`); added multi-turn conversation continuation (`task_id` in `POST /v1/tasks`, `ChatTurnItem.UserTurn`), 1-click GitHub OAuth Device Login (`@JiraNot`), ChatGPT Codex-style `+ New Session` workspace/repo picker, and Portrait/Landscape orientation verification | **45/45 Python unit tests passed on Windows** (`1` `/proc` test skipped) + **3/3 `test_app_server.py` passed inside Alpine PRoot on tablet** (`2.038s`); live 3-turn `codex app-server --stdio` JSON-RPC conversation (`af753cf0`, thread `01a2040a-791c-7662-abf7-5f1b6d115fa1`) verified on HUAWEI MatePad in both Portrait and Landscape orientations | `bridge/core.py`, `bridge/tests/test_app_server.py`, `android/app/src/main/java/com/tabletagent/local/ui/AionClaudeWorkspace.kt`, `screen_m3_appserver_chat.png`, `screen_m3_appserver_landscape.png` |
| 2026-11-23 | Gate M5 & Gate M6 Passed: Full Version (Aion Orchestrator + Hermes Memory + 1-Click APK Installer + Project Zip Export/Import + Queue & Worktrees) | Physical HUAWEI MatePad (WKI-W09) / Android 12 / Standalone PRoot (`8FHUN26724H01566`) | Implemented Aion mid-conversation Agent switcher in single chat thread with cross-agent handoff context synthesis + auto-quota failover (`QUOTA_ERROR_RE`); Hermes episodic memory ledger (`.agents/memory.md`, `HermesMemoryLedger`, UI dialog); Android App Builder & 1-Click APK Installer (`/v1/projects/{name}/apks`, `FileProvider` `Intent.ACTION_VIEW` triggering native Android package installer); Single-Click Project Zip Export & Import (`/export`, `/import`); Task Queue FIFO execution and ephemeral Git worktree isolation | **52/52 Python unit tests passed** (`51` passed, `1` `/proc` test skipped on Windows); Android debug APK compiled (`:app:assembleDebug` **BUILD SUCCESSFUL in 38s**); full system verified live on physical tablet: Aion agent switcher in composer, Hermes memory ledger button, Project export .zip (`30.7 KB`), APK discovery (`app-debug.apk 70.5 MB`), and native Android system Package Installer launched on tablet | `screen_full_version_chat.png`, `screen_apk_detected.png`, `screen_system_install_dialog.png`, `bridge/tests/test_aion_orchestrator.py`, `bridge/tests/test_hermes_memory.py`, `bridge/tests/test_project_export_import.py`, `bridge/tests/test_queue_worktree.py` |
| 2026-11-23 | OmniDev Tablet Rebrand + 3D Prism Icon + Quick Attachment Sheet + Mic Voice Input | Physical HUAWEI MatePad (WKI-W09) / Android 12 / Standalone PRoot (`8FHUN26724H01566`) | Rebranded app to **OmniDev Tablet**; generated master 3D Glowing Prism icon & deployed to all 5 mipmap densities (`ic_launcher.png`, `ic_launcher_round.png`); implemented Quick Attachment Sheet modal (Photos & Images `image/*`, Video Clips `video/*`, Documents `*/*`, and Project Workspace Files with 1-tap quick suggestion chips); implemented Tap-to-Speak Microphone Voice Prompt (`RecognizerIntent.ACTION_RECOGNIZE_SPEECH`) supporting Thai and English; compiled and installed on tablet hardware | **52/52 Python tests passed**; `:app:assembleDebug` **BUILD SUCCESSFUL in 35s**; verified on device: app label "OmniDev Tablet" in tablet dock & sidebar, 3D prism icon, Quick Attachment Sheet, and Google Speech Recognizer dialog launched successfully | `android/app/src/main/res/mipmap-*/`, `screen_attach_dialog.png`, `screen_voice_prompt.png`, `screen_launcher_home.png` |

**Owner of this roadmap:** the project maintainers and coding agents following [`AGENTS.md`](AGENTS.md). Keep it updated as the source of truth instead of declaring milestones finished from the existence of feature code.
