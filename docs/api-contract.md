# Local bridge REST contract — v0.1

`http://127.0.0.1:8765`. All endpoints other than `GET /health` require `Authorization: Bearer <device_token>`. CORS is deliberately not enabled.

| Method | Endpoint | Result |
|---|---|---|
| GET | `/health` | service health without credentials (`{ok, service, version, runtime}`) |
| GET | `/v1/projects` | locally available workspaces |
| POST | `/v1/projects` | `{name, git_url?}`, create/clone |
| GET | `/v1/projects/{name}/files?path=.` | safe directory list |
| GET | `/v1/projects/{name}/file?path=.env` | raw small file content |
| PUT | `/v1/projects/{name}/file?path=.env` | `{content}` atomic write |
| GET | `/v1/tasks?project={name}` | task history |
| POST | `/v1/tasks` | `{project,prompt,mode:"plan|build",agent?:"codex|claude|opencode",model?:string,task_id?:string}` (continues existing session when `task_id` is passed) |
| GET | `/v1/agents` | supported CLI adapters, binary availability & capabilities |
| GET | `/v1/tasks/{uuid}` | persisted status and verification |
| GET | `/v1/tasks/{uuid}/events?after=N` | next 120 ordered events + `{has_more, gap_detected, min_seq}` |
| GET | `/v1/tasks/{uuid}/approvals` | pending and resolved tool approvals |
| POST | `/v1/tasks/{uuid}/approvals/{id}` | `{decision:"allow|deny"}`, resolve approval |
| POST | `/v1/tasks/{uuid}/cancel` | stop process, terminal task state |
| GET | `/v1/settings` | runtime opt-in flags (`{codex_proot_sandbox_bypass, codex_use_app_server}`) |
| PUT | `/v1/settings` | update runtime opt-in flags (`{codex_proot_sandbox_bypass?:bool, codex_use_app_server?:bool}`) |
| POST | `/v1/projects/{name}/export` | `{include_env?:bool}`, export workspace to standalone .zip archive |
| POST | `/v1/projects/import` | `{zip_path, name?}`, import/restore workspace from .zip archive |
| POST | `/v1/projects/{name}/attachments` | `{filename, data_b64}`, binary attachment streamer up to 25 MB |
| GET | `/v1/projects/{name}/apks` | list discovered `.apk` builds in workspace |
| GET | `/v1/projects/{name}/memory` | read Hermes episodic memory ledger (`.agents/memory.md`) |
| DELETE | `/v1/projects/{name}` | safely stop preview/terminal and delete workspace |
| POST | `/v1/projects/{name}/rename` | `{new_name}`, rename project and update task history |
| GET | `/v1/projects/{name}/search?q=...` | fast workspace text grep (capped at 200 matches, redacting secrets) |
| GET | `/v1/projects/{name}/git/status` | workspace git status (uncommitted, untracked, modified) |
| GET | `/v1/projects/{name}/git/diff` | unified git diff of uncommitted changes |
| POST | `/v1/projects/{name}/git/commit` | `{message}`, audit-trailed manual commit |
| POST | `/v1/projects/{name}/git/rollback` | discard uncommitted changes back to HEAD checkpoint |
| GET | `/v1/projects/{name}/git/branches` | list local git branches, current branch, and remotes |
| POST | `/v1/projects/{name}/git/checkout` | `{branch, create?:bool}`, switch or create branch |
| POST | `/v1/projects/{name}/git/push` | `{remote:"origin", branch?}`, push with GITHUB_TOKEN (no force-push) |
| POST | `/v1/projects/{name}/git/checkpoint` | `{label?}`, create automated pre-apply snapshot commit |
| GET | `/v1/projects/{name}/preview` | local process state/url, stack type, and live output logs |
| POST | `/v1/projects/{name}/preview/start` | run dev server (Node/Vite, Monorepo, Python, or Static HTML) |
| POST | `/v1/projects/{name}/preview/stop` | terminate dev process |
| POST | `/v1/projects/{name}/terminal/start` | `{cols?, rows?}`, spawn Linux PTY session (`/bin/sh` or `/bin/bash`) |
| POST | `/v1/projects/{name}/terminal/input` | `{data}`, write raw keystrokes/control characters to PTY master fd |
| GET | `/v1/projects/{name}/terminal/poll?after=N` | incremental terminal output chunks with cursor sequence |
| POST | `/v1/projects/{name}/terminal/resize` | `{cols, rows}`, update PTY window size via `TIOCSWINSZ` |
| POST | `/v1/projects/{name}/terminal/stop` | cleanly kill running PTY terminal process |
| GET | `/v1/projects/{name}/instructions` | get AGENTS.md or CLAUDE.md instructions content |
| PUT | `/v1/projects/{name}/instructions` | `{content}`, update project AGENTS.md instructions |

Local-only port 8765; preview default port 3000 (Next) or 5173 (Vite). A project might require custom scripts/ports. No remote API and no external shell command endpoint.

States: `QUEUED`, `PLANNING`, `WAITING_APPROVAL`, `EXECUTING`, `VERIFYING`, `REPAIRING`, `PLANNED`, `COMPLETED`, `NEEDS_VERIFICATION`, `FAILED`, `CANCELLED`, `BLOCKED_SETUP`, `INTERRUPTED`.

## Endpoint Details

### 1. Binary Attachment Upload Pipeline
- **`POST /v1/projects/{name}/attachments`**
  - **Payload:** `{"filename": "spec.png", "data_b64": "<base64_string>"}`
  - **Constraints:** Up to 25 MB decoded binary payload (36 MB base64). Path traversal blocked.
  - **Destination:** `.agents/attachments/<filename>`
  - **Response:** `{"ok": true, "path": ".agents/attachments/<filename>", "size": <bytes>}`

### 2. Interactive Linux PTY Terminal
- **`POST /v1/projects/{name}/terminal/start`**: Spawns `/bin/sh` or `/bin/bash` with genuine Linux PTY (`pty.openpty()`, `termios`). Payload: `{"cols": 100, "rows": 30}`.
- **`POST /v1/projects/{name}/terminal/input`**: Writes raw keystrokes/control chars (`\x03`, `\x04`, etc.) to PTY fd. Payload: `{"data": "git status\n"}`.
- **`GET /v1/projects/{name}/terminal/poll?after=N`**: Returns incremental output chunks: `{"seq": M, "data": "<ansi_text>", "running": true}`.
- **`POST /v1/projects/{name}/terminal/resize`**: Sets window dimensions via `fcntl.ioctl(fd, termios.TIOCSWINSZ, ...)`. Payload: `{"cols": 80, "rows": 24}`.
- **`POST /v1/projects/{name}/terminal/stop`**: Terminates PTY session and reclaims master/slave fds.

### 3. Git Branch Management & Push
- **`GET /v1/projects/{name}/git/branches`**: Returns `{"branches": ["main", "feat-1"], "current": "main", "remotes": ["origin"]}`.
- **`POST /v1/projects/{name}/git/checkout`**: `{"branch": "feature/x", "create": true}`.
- **`POST /v1/projects/{name}/git/push`**: Pushes branch to remote using stored `GITHUB_TOKEN`. Force-push is strictly blocked.
- **`POST /v1/projects/{name}/git/checkpoint`**: `{"label": "apply-to-file"}` creates snapshot commit prior to manual file mutations.

### 4. Full Stack Preview & Live Logs
- **`GET /v1/projects/{name}/preview`**: Returns `{"status": "running", "url": "http://127.0.0.1:5173", "stack": "vite", "logs": ["..."]}` with last 200 lines ring-buffer.
- Multi-stack auto-detection: Vite/Next, Monorepo (`frontend/`), Python FastAPI (`uvicorn`), Flask (`flask run`), Django (`manage.py runserver`), Static HTML (`http.server`).

### 5. Git Worktrees (Multi-Agent Parallelism)
- **`GET /v1/projects/{name}/worktrees`**: Lists active worktrees in the project: `{"worktrees": [{"path": "/...", "head": "...", "branch": "feature/a", "is_base": false}]}`.
- **`POST /v1/projects/{name}/worktrees`**: Creates a new isolated worktree: `{"branch": "feature/parallel", "create_branch": true}`.
- **`DELETE /v1/projects/{name}/worktrees`**: Removes a worktree: `{"path": "/...", "force": false}`.
- **`POST /v1/projects/{name}/worktrees/prune`**: Cleans up stale worktree administrative records.

### 6. 100% Offline Local Small Language Model (SLM via llama.cpp)
- **`GET /v1/slm/status`**: Returns local llama.cpp runtime & model presence: `{"installed": true, "binary": "llama-cli", "models": ["qwen2.5-coder-1.5b-q4.gguf"], "default_model": "qwen2.5-coder-1.5b-q4"}`.
- **`POST /v1/slm/setup`**: Downloads/provisions local GGUF weights into the embedded runtime for 100% offline agent operation without cloud API keys.
