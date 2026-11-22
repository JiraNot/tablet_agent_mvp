# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

Developers, builders, and technical creators working directly on an Android tablet (such as HUAWEI MatePad ARM64) who want to plan, build, verify, preview, and commit real software projects locally on their tablet without needing a desktop computer, SSH server, or cloud IDE.

## Product Purpose

Tablet Agent Studio is a prompt-first native Android AI development environment. Users issue natural-language Plan or Build tasks to official CLI agents running inside an embedded local Linux environment, inspect structured progress and verification evidence, interact with a live localhost web preview side-by-side, make quick edits to configuration files (with `.env` secret masking), and commit verified work to local Git.

## Positioning

Unlike remote-control shells, web wrappers, or full desktop code editors crammed onto a touch screen, Tablet Agent Studio runs a self-contained Zero-Termux Alpine Linux PRoot userland (`Node.js 22`, `Git 2.47`, `Python 3.12`) and loopback supervisor bridge (`127.0.0.1:8765`) inside a native Kotlin + Jetpack Compose tablet interface where completion requires executable build/test verification rather than unverified AI chat output.

## Operating Context

- Landscape tablet workspace (`2800×1840` high-DPI multi-pane touch and keyboard posture) with adaptive support for portrait/compact widths.
- Extended coding and review sessions in varied lighting, requiring an adaptive Dark + Light studio theme with an immediate in-app theme toggle.
- Real local workflows: creating or cloning GitHub HTTPS repositories, switching between authenticated CLI agents (`Codex CLI`, `Antigravity CLI`, `Claude Code`) and their live model catalogs, monitoring the 5-phase workflow (`Plan` → `Execute` → `Verify` → `Auto-Repair` → `Commit`), testing Vite and Next.js apps in an embedded localhost `WebView` across `Full`, `Tablet (768dp)`, and `Phone (375dp)` viewports, and reviewing unified Git diffs before local commits.

## Capabilities and Constraints

- Native Android UI must remain 100% Kotlin + Jetpack Compose (Material 3); `WebView` is restricted strictly to `127.0.0.1` / `localhost` live project previews.
- Loopback HTTP bridge on `127.0.0.1:8765` with Android KeyStore `AES-256-GCM` device token and provider API key encryption (`SecureSecrets`).
- Only authenticated and installed CLI agents are shown in the active Agent selector; unauthenticated or offline CLIs are hidden until configured in Settings.
- Canonical task lifecycle (`QUEUED`, `PLANNING`, `WAITING_APPROVAL`, `EXECUTING`, `VERIFYING`, `REPAIRING`, `PLANNED`, `COMPLETED`, `NEEDS_VERIFICATION`, `FAILED`, `CANCELLED`, `BLOCKED_SETUP`, `INTERRUPTED`).
- Explicit human approval gates for sensitive tool executions and an explicit opt-in Security toggle (`OFF` by default) for Codex PRoot builds.
- Autonomous `git push` or remote deployment is prohibited by policy.

## Brand Commitments

- Name: **Tablet Agent Studio**
- Adaptive Dark + Light Studio visual theme with an in-app switch (`Dark`, `Light`, `System`).
- Tablet-native layout discipline: left `NavigationRail` on expanded tablet screens (`NavigationBar` on compact screens), compact studio header bar, adjustable split-pane workspace (`50:50 Split`, `Focus Agent`, `Focus Preview`), compact prompt & model/preset composer, and structured scannable activity cards.

## Evidence on Hand

- Verified working APK and Embedded Alpine PRoot runtime on physical HUAWEI MatePad (`8FHUN26724H01566`, Android 12, `arm64-v8a`).
- Live authenticated `Codex CLI` (`0.171.0`) and `Antigravity CLI` (`1.5.14`) catalogs, verified E2E builds for Vite `6.4.6` (`demo_app`, `codex_vite`) and Next.js `16.4.6` (`next_demo`), and live GitHub HTTPS clone (`hello_clon`).

## Product Principles

1. **Prompt and verification lead; chrome recedes.** Keep vertical overhead minimal on landscape tablets so the prompt composer, active phase progress, event stream, and live preview share the first viewport without cramped scrolling.
2. **Evidence over claims.** Surface verification output, active workflow phase, and Git diff status with unmistakable visual hierarchy so `COMPLETED` is clearly distinguished from `NEEDS_VERIFICATION` or `FAILED`.
3. **Tablet-native ergonomics.** Use Material 3 `NavigationRail` on wide displays, `48×48dp` touch targets, flexible pane ratios (`Split`, `Agent Focus`, `Preview Focus`), and high-contrast semantic tokens in both Dark and Light themes.
4. **Safe by default.** Mask `.env` secrets automatically, hide unauthenticated agents from the primary prompt bar, and keep sandbox overrides behind explicit security warnings.
