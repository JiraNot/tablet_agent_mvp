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
| GET | `/v1/projects/{name}/git/status` | workspace git status (uncommitted, untracked, modified) |
| GET | `/v1/projects/{name}/git/diff` | unified git diff of uncommitted changes |
| POST | `/v1/projects/{name}/git/commit` | `{message}`, audit-trailed manual commit |
| POST | `/v1/projects/{name}/git/rollback` | discard uncommitted changes back to HEAD checkpoint |
| GET | `/v1/projects/{name}/preview` | local process state/url |
| POST | `/v1/projects/{name}/preview/start` | run npm dev, optional `{port}` |
| POST | `/v1/projects/{name}/preview/stop` | terminate dev process |
| GET | `/v1/projects/{name}/instructions` | get AGENTS.md or CLAUDE.md instructions content |
| PUT | `/v1/projects/{name}/instructions` | `{content}`, update project AGENTS.md instructions |

Local-only port 8765; preview default port 3000 (Next) or 5173 (Vite). A project might require custom scripts/ports. No remote API and no external shell command endpoint.

States: `QUEUED`, `PLANNING`, `WAITING_APPROVAL`, `EXECUTING`, `VERIFYING`, `REPAIRING`, `PLANNED`, `COMPLETED`, `NEEDS_VERIFICATION`, `FAILED`, `CANCELLED`, `BLOCKED_SETUP`, `INTERRUPTED`.
