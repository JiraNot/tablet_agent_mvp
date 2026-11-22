# AGENTS.md — Tablet Agent Development Contract

> **Canonical agent instructions.** This document is the source of truth for AI coding agents (Codex, Claude Code, etc.) working in this repository. Read it **before** changing any file. See [`ROADMAP.md`](ROADMAP.md) for milestones and acceptance gates.
>
> **Status (2026-11-22):** Android native **source MVP**, not a verified APK or proven on-device product. The current implementation runs a Kotlin/Jetpack Compose Android client plus a Python standard-library supervisor in **Termux on the same Android tablet**. Do not claim that an embedded single-APK Linux runtime, production-grade sandbox, complete multi-agent support, or background survival exists.

## 1. Product intent (non-negotiable)

Build a **prompt-first native Android agent development app**, not a browser IDE or a thin remote-control shell. Users primarily issue prompts, view plan/progress/approvals, inspect a live preview, and occasionally edit `.env`, JSON, or other small configuration files. The default development environment and project files **run on the Android tablet itself**, including local Git, Node.js and CLI agents. An internet connection may still be necessary for hosted AI models and GitHub.

- **Default UX:** Projects → Agent / Prompt → Task Progress → Live Preview → Quick Files → Settings.
- **No full desktop IDE required.** Editor and terminal are secondary tools, never prerequisites to issuing a task.
- **No remote PC, SSH, VPS, or browser-hosted dev server required** to complete the primary workflow. Remote support may be added later as strictly optional.
- **Agent output is not proof of success.** Completion requires verification evidence matching the task's acceptance criteria.
- Do not continue the old MobileIDE architecture or merge upstream projects wholesale. Source-audit them, isolate behaviors, and port only after a recorded decision.

## 2. Repository map and current truth

| Path | Ownership / purpose | Current implementation |
|---|---|---|
| `android/` | Native Android Kotlin + Jetpack Compose application | Projects, Agent prompt, task/events, Preview `WebView`, quick file editor, Settings, Termux launch integration |
| `bridge/core.py` | Local supervisor (Python stdlib) | SQLite tasks/events, Git, Codex CLI `exec --json`, verification, one repair attempt, previews |
| `bridge/server.py` | Loopback HTTP bridge | `127.0.0.1:8765`, bearer auth, REST API; see `docs/api-contract.md` |
| `bridge/tests/` | Executable Python unit/integration tests | Does **not** replace on-device tests |
| `scripts/setup_termux.sh` | Bootstrap bridge and Node/Python/Git in Termux | Requires Termux installed separately |
| `.github/workflows/build-android.yml` | CI intent for Python tests and Android debug APK | Workflow exists; successful hosted APK build is **unverified** |
| `audit/` | Upstream source research and pinned revisions | Selective source inspection; full clones/builds still pending |
| `docs/` | Contract, ADRs, device acceptance | Supporting technical records |
| `ROADMAP.md` | Ordered delivery plan | Keep status and evidence up to date |

**Support today:** `codex` adapter only; tasks `plan` / `build`; `npm run build` or supported `npm test` verification; local `npm run dev` preview. Future: structured app-server, approvals UI, Claude/OpenCode adapters, MCP manager, skills, persistent foreground execution, integrated runtime.

## 3. Required workflow for every agent task

1. **Inspect first.** Read `README.md`, this file, `ROADMAP.md`, `docs/architecture-decisions.md`, `docs/api-contract.md`, and relevant code/tests. Review the Git diff and working tree before edits. Never overwrite user changes.
2. **State scope and acceptance.** Specify intended files, expected behavior, risk level, and how to verify it. For large changes, define a small vertical slice rather than building many untested modules.
3. **Implement minimum coherent change.** Prefer stable contracts and testable behavior over UI mock features. Do not pretend missing dependencies or devices are available.
4. **Test appropriately.** Run local tests; build Android when SDK/Gradle is available; validate Android ARM64/Termux on a real device before marking an on-device feature complete. Record exact commands and outputs.
5. **Report precisely.** Separate `Implemented`, `Tested`, `Not tested`, `Known limitations`, and `Next`. Never label source-only code as a verified APK. Update `ROADMAP.md` statuses/evidence if a milestone changes.

### Baseline commands

```bash
python3 -m unittest discover -s bridge/tests -v
# Android build requires working Android SDK, JDK 17 and Gradle:
cd android && gradle :app:assembleDebug --no-daemon
# Device-only (requires the Termux app):
bash scripts/setup_termux.sh
python ~/tablet-agent/bridge/server.py
# Upstream audit, only when network/git are available:
python3 audit/scripts/clone_audit.py
```

Never report a command as passing unless it was actually executed. If Android SDK or GitHub access is unavailable, say so explicitly and leave device gates unchecked.

## 4. Architecture rules

- **Native UI:** Kotlin + Jetpack Compose. Do not replace the main Android UI with Next.js/React running in a WebView. `WebView` is allowed **only for local website preview** or narrowly scoped provider login if authorized.
- **Runtime first:** Preserve the working Termux/Python bridge contract until a Kotlin/Go replacement is proven behaviorally equivalent. A single-language rewrite is an **option**, not an unconditional requirement.
- **API boundary:** Android talks to the local bridge on `127.0.0.1` only. Do not change bind address to `0.0.0.0`, enable broad CORS, or expose a listening development endpoint by default.
- **Contracts:** Update `docs/api-contract.md` and tests before changing endpoint names, JSON fields, state names, cursor semantics, or authentication.
- **Adapters:** Each CLI/provider must implement explicit capability metadata and normalized events. Keep protocol-specific logic out of UI and generic supervisor. Do not assume all agents support the same permissions, resume mode, attachments, MCP, or model controls.
- **State:** Preserve a single canonical persisted task lifecycle. Current active states: `QUEUED`, `PLANNING`, `EXECUTING`, `VERIFYING`, `REPAIRING`. Current terminal states: `PLANNED`, `COMPLETED`, `NEEDS_VERIFICATION`, `FAILED`, `CANCELLED`, `BLOCKED_SETUP`, `INTERRUPTED`.
- **Correctness:** Agent `turn.completed` / exit code zero is **not** equivalent to a verified outcome. Record which verifier ran, exit status, and limitations. No verifier means `NEEDS_VERIFICATION`, not `COMPLETED`.
- **Concurrency:** One active task per project in MVP. Preserve mutual exclusion or introduce per-task worktrees with documented isolation and cleanup tests before enabling parallel writes.
- **Recovery:** Never silently relaunch interrupted tasks or resume unknown orphaned processes. Persist state/cursors; reconcile process and workspace before new mutating work.
- **Security:** Avoid unsafe tool bypass flags and unprompted full-access defaults. Keep destructive operations behind explicit approval, validate paths and child-process arguments, and prevent cross-workspace file access.

## 5. Secrets, permissions and dependency safety

- **Never** print, log, commit, upload, include in prompt context, or echo `.env` secrets, keys, bearer tokens, private repo credentials, auth caches, or user files containing secrets.
- Current Python bridge provides only **limited** regex redaction, 256 KiB file editing, workspace path checks, bearer auth, and local binding. **These are not a hardened sandbox.** Prompt instructions alone do not enforce secrets isolation from an executing CLI.
- `.env` editing is a user-initiated quick-edit function. Prefer masked secret values and do not send file contents to AI context by default. Any feature giving an agent access to workspace files needs a documented threat model.
- Never introduce `--dangerously-bypass-approvals-and-sandbox` or `danger-full-access` as default behavior.
- Verify Android permissions, `RUN_COMMAND` consent, background execution constraints, and package signatures before shipping. Respect Android scoped-storage rules; do not request blanket storage permissions without justification.
- Keep the CLI provider's official authentication flow; do not implement credential scraping or unsupported session-cookie reuse.
- Treat upstream repos (AntiGem GPL-3.0, CloudCLI/ForgeStudio AGPL-3.0, Happier/Claudable MIT, AionUI Apache-2.0) as **research references** until provenance, license obligations, and binary dependencies are recorded. A rewrite based on protected code may still have license obligations; do not claim otherwise.

## 6. Code quality conventions

- Keep Kotlin UI state independent from networking/agent protocols; use structured models rather than stringly typed business rules when extending the app.
- Keep Python bridge stdlib-compatible where feasible; small modules, explicit return/status semantics, no shell string execution, and bounded subprocess timeouts.
- Change behavior with tests: add a regression test for any bug, add at least one negative case for security-sensitive changes, and preserve existing API behavior unless versioned.
- Do not use network-dependent or destructive tests as default CI. Mock CLI when exercising lifecycle/error handling, and label mocked results honestly.
- Prefer resilient process startup/shutdown, durable SQLite transactions, replay cursors, idempotency, and actionable error messages over extra presentation features.
- Avoid unrelated refactors, large dependency upgrades and mass code formatting in feature PRs. Every PR should be reviewable and reversible.

## 7. Milestone priority and stop conditions

Read the status grid in [`ROADMAP.md`](ROADMAP.md). Work in order:

1. **M0 — Verify the shipped source:** CI APK build, backend test report, install test on target tablet; resolve actual build failures.
2. **M1 — Verify local coding loop:** Termux + ARM64 Codex login, clone a real repository, Plan/Build, build/test, preview, Git commit; record device evidence.
3. **M2 — Reliable lifecycle and secrets:** foreground work, recovery/replay, permissions, harden `.env` handling.
4. **M3 — Agent architecture:** structured `app-server`, approvals and normalized capability-aware adapters.
5. **M4–M6 — Prompt UX, multi-agent/settings, integrated runtime** only after earlier gates pass.

**Stop and surface a blocker** when: working tree has unexpected changes, project would require bypassing sandbox, device/ABI is not supported, a dependency/license cannot be verified, API behavior changes without tests, or a task needs access to user secrets. Ask only for the specific missing authorization/input; continue safe independent work where possible.

## 8. Completion template for assistant/Codex replies

```text
Scope: <feature or milestone>
Implemented: <files and behavior>
Verified: <exact commands, observed result and test counts>
Not verified: <Android device/SDK/CLI/browser constraints>
Risks: <remaining security, recovery, performance or license issues>
Roadmap: <milestone ID and status, link to evidence>
Next: <single highest-priority next action>
```

No claims of “production ready”, “fully local APK”, “100% tested”, or “completed milestone” without corresponding recorded evidence.
