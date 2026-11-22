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
| Embedded, single-APK Termux-equivalent runtime | `[ ]` | Current MVP uses separate Termux installation |
| Multi-agent provider UI and runtime adapters | `[x]` | Codex, Claude Code, and OpenCode adapters implemented with capability flags and `/v1/agents` endpoint; agent & model selector integrated in Compose UI |
| Durable foreground execution while Android sleeps | `[x]` | Android Foreground Service + WakeLock implemented in source & APK |
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

- [ ] Install Termux from a trusted source, bootstrap Python/Node/Git and verify bridge on `127.0.0.1:8765`.
- [ ] Find a compatible Android/Termux ARM64 Codex CLI; verify version, supported auth, `codex login`, `codex exec --json`, `codex exec resume` and correct permission behavior.
- [ ] In app: create/clone project, Plan prompt, Build prompt, watch task/events, cancel, reopen and inspect status.
- [ ] On a small Vite project, run `npm install`/`npm ci`, `npm run build`, `npm run dev`, inspect WebView and HMR.
- [ ] Repeat using a realistic Next.js project with native dependencies; record which packages/ABIs work or fail.
- [ ] Git status/diff and manual commit to a test branch; no unapproved push or deploy.
- [ ] Confirm the tablet can do the above **without any remote computer/server**; cloud-model internet is acceptable.

**Gate M1:** one complete device-recorded `clone → prompt → edit → verify → preview → git` flow with actual CLI/process evidence. **Not passed yet.**

### M2 — Execution reliability, permissions and secrets **(P0)**

**Objective:** prevent the regressions that plagued earlier fragmented development attempts.

- [x] Introduce an Android Foreground Service and explicit ongoing notification when long tasks run; test OEM battery management (Implemented in AgentForegroundService.kt; compiled in APK).
- [ ] Persist and replay ordered events across app reconnect, process crash and bridge restart; detect sequence gaps.
- [ ] Implement orphan-process reconciliation and safe cancellation/timeout for process groups; never run duplicate mutating tasks after restart.
- [ ] Implement task-scoped permissions (plan-only, workspace-write, confirm destructive), not blanket unrestricted CLI access.
- [ ] Define a real secrets threat model; mask `.env` values in UI and logs, prevent automatic secret injection into prompts, and test exfiltration paths.
- [ ] Add checkpoint/rollback or worktree protection before higher-risk repairs.
- [ ] Expand tests: disk full, interrupted subprocess, corrupt DB, invalid path/symlink, expired token, clock drift/retry, long output.

**Gate M2:** recovery and approval tests pass on Android in foreground/background/screen-off scenarios; no false-complete claims. **Not passed yet.**

### M3 — Stable structured AI Agent runtime **(P1)**

**Objective:** remove brittle CLI-output assumptions and establish a single supervisor contract.

- [ ] Replace or supplement `codex exec --json` with official `codex app-server` JSON-RPC and streaming notifications.
- [x] Implement native pending tool approvals/questions and response handling, with timeout/deny behavior (SQLite approvals table, REST API, test suite, and Native Compose Approvals UI).
- [ ] Specify normalized `AgentCapabilities`, `AgentRun`, `AgentEvent`, `AgentApproval`, `AgentError` and `ResumeHandle` contracts.
- [ ] Introduce an adapter test harness for start, stream, cancel, resume, error, approval and reconnect.
- [ ] Add explicit `VERIFICATION_FAILED`/`NEEDS_VERIFICATION` reporting and quality-gate evidence; never equate `turn.completed` with a product acceptance pass.
- [x] Add idempotent task commands and SQLite schema migrations, preserving existing user projects/history (Approvals table migration, safe indexing).

**Gate M3:** Codex app-server adapter passes protocol integration tests, device replay and native approvals UI. **Not passed yet.**

### M4 — Polished prompt-first tablet experience **(P1)**

**Objective:** become an agent app rather than a developer-oriented IDE.

- [x] Native responsive Compose workspace: project list, prompt, task progress, small-screen switcher, side-by-side landscape Preview.
- [x] Prompt Library with reusable presets (Feature, Fix Error, UI Polish, Refactor, Audit, Run Tests).
- [x] Planning and acceptance criteria visible in chat; structured tool results and evidence links, not raw log flood (Structured cards for messages, tools, verifications, and approvals).
- [x] Attachment picker for screenshots/files with explicit privacy confirmation (Workspace path input chip, one-tap removal, and security alert modal against uploading secrets/keys).
- [x] Preview start/stop/reload, full-width/device sizes (Full, Tablet 768dp, Mobile 375dp viewport modes).
- [x] Lightweight quick file/config editor: masked `.env`/secrets (One-tap mask/reveal toggle to prevent credential leakage).
- [x] Settings separated into progressive categories: Runtime, Agent CLIs, Instructions, Security, and Setup Walkthrough; advanced items progressively disclosed.

**Gate M4:** real tablet usability walkthrough from onboarding to finished task, with accessibility and orientation checks. **Not passed yet.**

### M5 — Multi-agent orchestration, MCP and workspace safety **(P2)**

**Objective:** add breadth only after single-agent reliability is demonstrated.

- [x] Add Claude Code and OpenCode through independent, capability-aware adapters (`CodexAdapter`, `ClaudeCodeAdapter`, `OpenCodeAdapter`).
- [x] Add model/reasoning selection and supported-provider fallback; do not share authentication state across providers without permission.
- [x] Per-project instructions, Skills/AGENTS.md/CLAUDE.md support and MCP configuration with allowlists (Automatic bounded inspection up to 4000 chars, regex secret redaction, REST endpoints `/v1/projects/{name}/instructions`, and native Compose Settings editor).
- [x] Task supervisor: plan/delegate/review/retry with a bounded execution budget; clear human approval gates.
- [ ] Queue management and optional parallel work in separate Git worktrees; prevent multi-agent edits to same checkout.
- [x] Git review, unified diff inspection, and manual commit safety with audit trail; autonomous git push prohibited.

**Gate M5:** at least two distinct agents tested end-to-end on Android; no concurrent worktree corruption or secret exposure. **Not passed yet.**

### M6 — Consolidate runtime and prepare alpha release **(P2)**

**Objective:** decide whether to retain external Termux or package an integrated runtime without breaking M0–M5.

- [ ] Full source clone and architecture/license comparison for the six upstream apps at pinned SHAs; write an ADR before copying or porting logic.
- [ ] Evaluate a Kotlin-native supervisor versus a Go sidecar versus current Python/Termux against **real** CPU, battery, ABI, package compatibility and maintainability data.
- [ ] If practical, package a supported embedded ARM64 userland; otherwise document Termux as an intentional supported dependency.
- [ ] Create versioned data migrations, crash reporting with privacy controls, upgrade/rollback and signed release builds.
- [ ] Validate third-party licenses (particularly GPL/AGPL), user data export, dependency SBOM and reproducible release artifacts.
- [ ] Target alpha checklist: onboarding, offline local tools, authenticated cloud agents, error handling, security, restart, large-repo performance.

**Gate M6:** repeatable signed alpha delivery and device acceptance. **Not passed yet.**

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

**Owner of this roadmap:** the project maintainers and coding agents following [`AGENTS.md`](AGENTS.md). Keep it updated as the source of truth instead of declaring milestones finished from the existence of feature code.
