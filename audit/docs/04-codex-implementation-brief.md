# Prompt ให้ Codex — Execute Full Audit & Android Feasibility POC

Use the text below with Codex on a workstation/server that can access GitHub. This is a *local source audit* — it should not modify any private repositories unless later approved.

---

You are a principal Android runtime engineer and open-source code auditor.

**Goal:** Design a **native Android tablet prompt-first local AI development application**. AI agents, local files, Git, Node/npm dev server and in-app WebView preview all run on the tablet. Do not replace this goal with a web IDE, PWA, remote devbox, or browser-based main app. Cloud AI inference is permitted but a separate development server is not required.

Read the project bundle first (`README.md`, `repos.lock.json`, `docs/01-source-audit.md`, `docs/02-target-architecture.md`, `docs/03-acceptance-tests.md`).

## Mandatory sequence

1. Run `python3 scripts/clone_audit.py` with public internet. Clone the six pinned repositories into `sources/` and confirm all commit hashes. Never execute untrusted install scripts during inventory.
2. Trace each repository's execution path. For each relevant subsystem, record real source path + symbols and call flow `UI → command → provider/adapter → process → stream → state → persistence/recovery` with no guessing. Read tests/docs and rate code quality.
3. Produce `docs/05-deep-source-comparison.md`, `docs/06-adapter-contracts.md`, `docs/07-license-bom.md` and `docs/08-android-runtime-feasibility.md`, with citations to precise files in pinned sources and evidence, separating observed and proposed features.
4. Choose one canonical Run/Session domain model and persistent event sequence schema. Compare AntiGem JSON-RPC event replay and Happier Execution Run Protocol; preserve verifiable agent capabilities without pretending all agents support resume/approvals identically.
5. Check Android ARM64 platform constraints, native ABI packages, Codex app-server installation, Node.js npm `esbuild`/`sharp` native modules, loopback WebView HMR, filesystem permissions and Android Background/Foreground Services. Build the **smallest possible POC** that tests these assumptions on a physical device. Report BLOCKED when no device is attached.
6. Run the Kotlin `bash scripts/test-core.sh` tests; add tests, not merely documentation, for failure, cancellation, verification and replay edge cases.
7. Publish an architecture decision: which source modules to reuse directly, implement as adapters, replace or exclude, and why. Keep a source-license traceability table.
8. Do not start rewriting all six repositories, and do not implement UI redesign before the Android feasibility gates pass.

## Security / license boundaries

- Do not use `--dangerously-bypass-approvals-and-sandbox` or `danger-full-access` as default.
- Do not run arbitrary repo scripts without explicit sandbox and inspection.
- No extraction of credentials, `.env` values, private keys or account tokens. Do not paste secrets into generated documentation or prompts.
- Do not claim that a Kotlin rewrite automatically removes obligations from GPL/AGPL source-derived implementations. Independently define contracts or secure licensing review.
- No pushing to existing user repositories; no destructive git operations to user repos.
- Retain source references and recorded test evidence. Use PASS_REAL / PASS_UNIT / BLOCKED / FAIL distinctions.

## Deliverable format

- A matrix across Claudable, CloudCLI, ForgeStudio, Happier, AntiGem and AionUI
- Concrete code paths and call graphs
- One coherent Kotlin-centered domain design, with provisional adapter boundary if Go runtime is kept
- Android feasibility POC findings
- 3 next implementation increments, each with acceptance and rollback conditions

Start the audit now. Do not replace code inspection with feature-level summaries.
