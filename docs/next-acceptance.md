# Device and product acceptance requirements

## Must validate on real Android before alpha
- [ ] Build debug APK from GitHub Actions and install on target tablet.
- [ ] Install Termux and confirm permission model for `RUN_COMMAND`.
- [ ] Confirm arm64 Codex binary version, local `codex login`, real Plan and Build prompts.
- [ ] Clone private authenticated GitHub repo (using Termux Git credential provider).
- [ ] `npm ci` and `npm run dev` for real Next.js/Vite project with native modules.
- [ ] Confirm WebView can load HMR and reconnect after dev server restart.
- [ ] Turn screen off for 5/15/30 minutes; inspect Android process kill and state recovery.
- [ ] Validate Resumed Codex exec session CLI flags on tested version.
- [ ] Validate permission requests, `--sandbox` behavior on Android/Termux (do not assume desktop parity).
- [ ] Verify no secret exposure in agent logs or webview when preview JS reads localhost origin.
- [ ] Test large projects, disk full, storage permissions, crash recovery and cancellation.

## Required for proper product
- [ ] Replace Termux external dependency with a bundled native runtime, or keep it as a clearly documented platform dependency.
- [ ] Move agent adapter to structured app-server with approval UI and reliable persistence/replay.
- [ ] Add Claude Code/OpenCode adapters behind normalized capability interfaces.
- [ ] Real Foreground Service + notifications and background stability.
- [ ] Add project worktrees/checkpoints and rollback, safe commit and PR actions.
- [ ] Add native agent settings, MCP, skills, provider authentication, model selection and quota indicators.
- [ ] Reuse GPL/AGPL implementation only after legal review and source-of-truth license tracking.
- [ ] Implement automated UI screenshot and Android instrumentation tests.

## Definition of complete
An app feature is complete only when implemented, instrumented, and verified on an actual tablet. `Codex turn.completed` alone is not a guarantee.
