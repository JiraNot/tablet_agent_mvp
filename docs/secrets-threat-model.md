# Secrets & Local Runtime Threat Model (M2)

> **Scope:** Standalone Android PRoot + Python loopback bridge (`127.0.0.1:8765`).

## 1. Protected Assets
1. **Device Bridge Token:** Stored in Android KeyStore (`AES-256-GCM`) and synced to `device_token` (`0600`) inside the app's private PRoot rootfs (`Context.filesDir`). Never printed to stdout when `TABLET_AGENT_EMBEDDED=1`.
2. **Provider Credentials & API Keys:**
   - API keys (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`) encrypted at rest in KeyStore (`SecureSecrets.kt`) and injected only into the spawned bridge child process environment.
   - OAuth credentials inside PRoot (`~/.codex/auth.json`, `~/.claude/.credentials.json`, `~/.gemini/antigravity-cli/antigravity-oauth-token`) with `0600`/`0700` permissions inside Android app-private sandbox.
3. **Workspace `.env` files:** Editable by the user in `FilesScreen` (masked by default with explicit `Reveal` toggle).

## 2. Trust Boundaries & Mitigations
- **Loopback isolation:** HTTP bridge binds strictly to `127.0.0.1:8765`; CORS is disabled; constant-time bearer token validation (`secrets.compare_digest`).
- **Workspace confinement:** Path traversal (`..`), symlinks escaping project root, and access to `.git`, `node_modules`, `.next`, `.gradle`, `.venv` are blocked in `LocalCore._file`.
- **Log / UI / Prompt redaction:** `REDACTION` in `bridge/core.py` scrubs known key prefixes (`sk-`, `sk-ant-`, `ghp_`/`gho_`/`ghu_`/`ghs_`/`ghr_`, `AIza`), `Bearer <token>` headers, and `.env`-style secret assignments (`OPENAI_API_KEY=...`, `ANTHROPIC_API_KEY=...`, `GEMINI_API_KEY=...`, `*_SECRET=...`, `*_TOKEN=...`, `*_PASSWORD=...`) before writing task events, `result`, `error`, `verification`, `git diff`, or `AGENTS.md` instructions.
- **Default `.gitignore`:** New local projects initialize `.gitignore` excluding `.env`, `.env.*` (`!.env.example`), `node_modules/`, `dist/`, `.next/`, `.venv/`, `__pycache__/`, `.gradle/`.
- **No silent sandbox bypass:** `CodexAdapter` defaults to `--sandbox read-only` (Plan) and `--sandbox workspace-write` (Build). Because Android 12 + PRoot (`kernel 5.10.43`) blocks unprivileged `bubblewrap`/Landlock namespaces, passing `--sandbox danger-full-access` for Codex `Build` requires an explicit user opt-in in **Settings -> Security** (`codex_proot_sandbox_bypass`, `false` by default).

## 3. Known Residual Risks
- Executing third-party CLIs (`codex`, `claude`, `agy`) or `npm` lifecycle scripts inside PRoot runs under the same Android UID (`u0_a162`); prompt instructions and regex redaction reduce accidental leakage to logs/prompts, **not** OS-level secret isolation against arbitrary code run inside a build script.
- Users should avoid placing production credentials inside untrusted repositories cloned onto the tablet.
