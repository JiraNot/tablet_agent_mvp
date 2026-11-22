# Local bridge REST contract — v0.1

`http://127.0.0.1:8765`. All endpoints other than `GET /health` require `Authorization: Bearer <device_token>`. CORS is deliberately not enabled.

| Method | Endpoint | Result |
|---|---|---|
| GET | `/health` | service health without credentials |
| GET | `/v1/projects` | locally available workspaces |
| POST | `/v1/projects` | `{name, git_url?}`, create/clone |
| GET | `/v1/projects/{name}/files?path=.` | safe directory list |
| GET | `/v1/projects/{name}/file?path=.env` | raw small file content |
| PUT | `/v1/projects/{name}/file?path=.env` | `{content}` atomic write |
| GET | `/v1/tasks?project={name}` | task history |
| POST | `/v1/tasks` | `{project,prompt,mode:"plan|build",agent:"codex"}` |
| GET | `/v1/tasks/{uuid}` | persisted status and verification |
| GET | `/v1/tasks/{uuid}/events?after=N` | next 120 ordered events |
| POST | `/v1/tasks/{uuid}/cancel` | stop process, terminal task state |
| GET | `/v1/projects/{name}/preview` | local process state/url |
| POST | `/v1/projects/{name}/preview/start` | run npm dev, optional `{port}` |
| POST | `/v1/projects/{name}/preview/stop` | terminate dev process |

Local-only port 8765; preview default port 3000 (Next) or 5173 (Vite). A project might require custom scripts/ports. No remote API and no external shell command endpoint.

States: `QUEUED`, `PLANNING`, `EXECUTING`, `VERIFYING`, `REPAIRING`, `PLANNED`, `COMPLETED`, `NEEDS_VERIFICATION`, `FAILED`, `CANCELLED`, `BLOCKED_SETUP`, `INTERRUPTED`.
