# Acceptance Tests — Android Local Agent-first Development

**Rule:** Report `PASS_REAL` only after actually running on the target Android tablet. `PASS_UNIT` is not evidence of a working app. Never convert a mocked result into a real pass.

| ID | Scenario | Measurable pass criteria | Evidence | Status |
|---|---|---|---|---|
| A01 | Android install | APK installs/launches on target Huawei/Samsung Android tablet; UI is Compose/native | APK/build log, device info, screenshot | NOT_RUN |
| A02 | Local runtime | Git and Node are executed inside the tablet's app-private userland; no remote host | process info + CLI version logs | NOT_RUN |
| A03 | Repo operations | Clone a sample public Git repo into app-private storage, checkout branch, edit, commit | command trace, SHA | NOT_RUN |
| A04 | Codex login | Authenticated CLI/app-server on Android; do not collect login secrets in logs | redacted auth status and CLI version | NOT_RUN |
| A05 | Prompt → task | Prompt creates `runId`, persists stage transitions, shows live output | run record + event trace | NOT_RUN |
| A06 | Approvals | Destructive command requests approval; deny prevents execution | approval event + denied command test | NOT_RUN |
| A07 | Resume/replay | Disconnect UI during running task, reconnect, resume from last event without doubles/gaps | event IDs/cursor log | NOT_RUN |
| A08 | Background | Turn off screen/switch apps; verify status realistically under Android power policies | 10+ actual-device runs + logs | NOT_RUN |
| A09 | `npm run dev` | Local dev server binds to loopback port and serves a test app | process ID, port, response + HMR log | NOT_RUN |
| A10 | Embedded preview | App WebView renders `localhost` Vite/Next.js; HMR works | video/screenshot and HMR trace | NOT_RUN |
| A11 | Build & verify | Agent turn finishes, build fails -> task NOT completed; repair -> passes -> complete | gate evidence IDs + exit codes | PASS_UNIT (domain only) |
| A12 | Config edit | Mask `.env`, edit single value, safely save and verify no secret in logs/prompt | before/after sanitized hash + audit log | NOT_RUN |
| A13 | Agent switching | Unavailable runtime cannot be selected as supported; UI shows capability limits | adapter manifest + UI test | NOT_RUN |
| A14 | Parallel projects | Two projects may run simultaneously; two mutating agents cannot share same worktree | workspace contention logs | NOT_RUN |
| A15 | Restart recovery | App/process restart recovers persisted run state or records INTERRUPTED; never silently marks COMPLETED | Room DB snapshot + recovery trace | NOT_RUN |
| A16 | MCP isolation | MCP forbidden to access outside configured directories and network scopes | negative permission tests | NOT_RUN |
| A17 | Core unit test | Lifecycle, repair, approval interruption, event dedup/gap | `bash scripts/test-core.sh` output | PASS_UNIT |

**Evidence requirement:** Every test includes timestamp, source commit, APK checksum, device/Android version, run command, expected/actual result and screenshot/log path. If blocked by environment, mark `BLOCKED`; never declare PASS.

## Immediate execution priority

`A02 → A04 → A05 → A07 → A09 → A10 → A11` before building the full UI. These establish core feasibility, then complete remaining tests before product release.
