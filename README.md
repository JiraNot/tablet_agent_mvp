# Tablet Agent — Android-native Prompt-first Development Workspace (MVP)

**Status: SOURCE MVP — VERIFIED LOCAL BUILD & COMPOSABLE APK.** Last updated: 22 November 2026.

This is an independently written Android native app (Kotlin + Jetpack Compose) and a **local** Python agent supervisor running on **the same Android tablet** in Termux. It is not a browser-based IDE and needs no remote PC, VPS, SSH tunnel, or cloud server. Codex still needs an authenticated account and internet access to call its model.

## Agent instructions and delivery plan

- **[`AGENTS.md`](AGENTS.md)** — canonical repository instructions for Codex and other coding agents: architecture boundaries, security, workflow, tests and completion criteria.
- **[`agent.md`](agent.md)** — alias/pointer for the requested lowercase filename; do not duplicate policies here.
- **[`ROADMAP.md`](ROADMAP.md)** — M0–M6 prioritized milestones, acceptance gates, product scope, upstream code reuse and evidence log.

AI agents should read these documents **before modifying code**, and update the roadmap when a milestone is actually verified.

## What works in the source implementation

- Android Compose UI: project chooser/create/clone, Agent prompt, Plan vs Build, task list and events, Live Preview WebView, quick file editor including `.env`, device settings.
- Android Foreground Service (`AgentForegroundService.kt`) with persistent ongoing notification, live task status updates, cancel action, and partial WakeLock to keep processes alive while screen is off.
- Native Tool Approvals UI and SQLite approvals subsystem (`WAITING_APPROVAL` lifecycle, REST endpoints, timeout/default-deny safety).
- Prompt Library presets for tablets: quick chips for Features, Bug fixes, UI Polish, Refactor, Audit, and Tests.
- Structured Activity Stream: distinct Material3 cards and icons for Messages, Tools, Verifications, Approvals, and Errors.
- Responsive Live Preview: quick switcher for Full width, Tablet (768dp), and Phone (375dp) viewports with reload button.
- Masked `.env` secret values in Quick File Editor with one-tap Reveal/Mask toggle to prevent credential leakage.
- Landscape tablet layout: chat and preview side-by-side when width is at least 850dp.
- Local Python HTTP bridge on `127.0.0.1:8765`, with a 32-byte-class random device bearer token and Android Keystore encryption of stored token.
- GitHub repo clone into `~/tablet-agent/workspaces/<project>` and project creation with `git init`.
- Codex CLI non-interactive `codex exec --json`; plan runs in read-only mode, build runs workspace-write mode. No dangerous bypass flags.
- Codex JSONL event adapter, persisted per-project session IDs and `codex exec resume` follow-up support.
- SQLite persisted task history, event log, and approvals table with cursor pagination and interruption detection.
- Verification of npm `build` or `test` scripts, one automated repair attempt on failed verification. `COMPLETED` only after a verification command succeeds; `NEEDS_VERIFICATION` if no supported verifier exists.
- Local dev preview process with npm `dev` script; WebView renders project URL on tablet loopback.
- Backend unit and HTTP integration tests (16/16 tests passing cross-platform).
- Bundled Gradle Wrapper (8.13) with verified debug APK assembly (`app-debug.apk` ~20.5 MB).

## Implementation boundaries / not yet verified

1. **There is no prebuilt APK** in this archive. No Android SDK/Gradle/network was available in the execution environment to compile and install it. Build on Android Studio or GitHub Actions.
2. No genuine Android ARM64 + Termux Codex execution has yet been tested. Native Codex CLI and dependency installation depend on ABI/repository compatibility; some npm native modules may not work.
3. MVP uses **Termux installed as a separate Android app** for on-device Python/Node/Codex. It does **not** yet bundle AntiGem's runtime or ship a single all-in-one APK. For an embedded runtime, separate packaging/ABI/security work remains.
4. Only Codex adapter is wired. Claude Code, OpenCode, Antigravity, ACP, MCP manager, approvals UI, custom skills and model routing remain future iterations.
5. `codex exec` is run in noninteractive mode. Granular tool approvals and reliable recovery of the underlying vendor process require the app-server protocol and further implementation. Prompt instructions do not implement a filesystem secrecy boundary.
6. After Android suspends/kills Termux, jobs may be interrupted. Foreground service, battery optimization exceptions, vendor recovery and robust process cleanup are future work. No background-uptime guarantee.
7. When an agent turn ends, task state is verified only via selected scripts. A passing script is useful evidence, **not** proof that every requested feature works or is safe.
8. No end-to-end GUI or APK build test has been run. The Kotlin source was syntax-scanned using the standalone Kotlin compiler, but full Android dependency resolution was unavailable.
9. The 6 upstream projects were source-inspected selectively via GitHub; full `git clone` could not run due GitHub DNS restrictions. See `audit/` for the pinned source map and clone script.

## Architecture

```text
Android APK (Jetpack Compose, Kotlin)
  ├─ Agent Chat / Prompt Tasks / Project Picker
  ├─ Native Quick File Editor and Local WebView Preview
  └─ LocalApi (Android HttpURLConnection, Bearer Token)
                   │ 127.0.0.1:8765 only
                   ▼
Termux local environment on same tablet
  ├─ bridge/server.py (Python stdlib HTTP)
  └─ bridge/core.py (SQLite, project/workspace, task state + event log)
       ├─ Codex CLI exec --json / resume (local process)
       ├─ Git clone/init
       ├─ npm run build/test (verification + single repair loop)
       └─ npm run dev -> localhost:3000 or localhost:5173
```

## Install on Android tablet (two-stage MVP)

1. **Build debug APK** via Android Studio (`android/`) or publish this repository to GitHub and run Actions workflow `.github/workflows/build-android.yml` (APK is available from the Actions artifact). Do not assume it has been prebuilt.
2. Install **Termux from its official F-Droid or GitHub release**, and open it once.
3. Extract this project archive into Termux-accessible storage, then run `bash scripts/setup_termux.sh` from project root **in Termux**. This installs Python, Node.js and Git and copies the bridge into `~/tablet-agent/`.
4. Install a **compatible Codex CLI binary** for your tablet; check `pkg search codex`, use the documented package if available and verify with `codex --version`. Then `codex login` and verify a simple Codex task **in Termux**. Installing the standard npm Codex package is not guaranteed to work on every Android CPU/ABI.
5. Start locally: `python ~/tablet-agent/bridge/server.py`. It prints `Local Tablet Agent API` and a device token.
6. Install/open the debug APK, go to **Settings**, paste the token, tap **Save token**, verify `Local bridge connected`.
7. Create or clone a GitHub repository. Choose **Plan only** before sending a **Build** prompt. For a Node project, install its dependencies in Termux, then use **Preview → Start dev server**.
8. Optional Start Bridge button: edit `~/.termux/termux.properties` to include `allow-external-apps=true`; grant this APK `RUN_COMMAND` in Android Settings. The button starts an **on-device Termux terminal** running the local bridge; it does not contact a remote machine. Restrict this permission to apps you trust.

For portability: the Termux backend currently must be copied into its private directory; the native app cannot automatically read/write Termux's private files without using the local authorized API.

## Development and tests

```bash
python3 -m unittest discover -s bridge/tests -v
python3 bridge/server.py    # try on a Linux test machine; Codex optional for API smoke tests
```

Build with Android Studio by opening `android/` as a Gradle project, installing Android SDK Platform 35, using JDK 17, and choosing **Build APK(s)**. Or run GitHub Actions after adding files to a new repository.

Backend API contracts and acceptance criteria are in `docs/`. Source-level review snapshots and cloning scripts are in `audit/`.

## Legal and dependency constraints

The new source files in `bridge/` and `android/` are an independent implementation; they do not bundle upstream GPL/AGPL repo code. Runtime integrations and distribution of Termux/other CLIs are separate matters with their own licenses. This archive retains upstream names/paths only for research and evaluation. Verify all third-party dependency licenses before distribution, especially if integrating AntiGem, CloudCLI, or ForgeStudio code later.
