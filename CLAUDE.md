# CLAUDE.md

Entry point for Claude Code and autonomous coding agents operating in `TinkeringDao/satori`.

## Mandatory Reading Before Any Action

1. **[`AGENTS.md`](AGENTS.md)** — Repository context pointers, module responsibilities, domain glossary references (`GLOSSARY.md`), and project verification commands.
2. **[`docs/agents/autonomous-pipeline.md`](docs/agents/autonomous-pipeline.md)** — The 6-stage **Fully Autonomous Development Pipeline Specification** (Context & Hindsight Recall -> Signed Branch TDD -> PR Creation -> Dual 4-Gate CI & Subagent Review -> Autonomous Remediation -> Automated Approval & Squash Merge).
3. **[`LEGAL.md`](LEGAL.md)** and **[`SECURITY.md`](SECURITY.md)** — 8-pillar EU/US clean-room hardware interoperability framework, `Addendum: German Statutory References (Applicable to Residents of Germany)`, and strict on-device privacy/security invariants.
4. **Agent Skills & Conventions** — [`docs/agents/domain.md`](docs/agents/domain.md), [`docs/agents/issue-tracker.md`](docs/agents/issue-tracker.md), and [`docs/agents/triage-labels.md`](docs/agents/triage-labels.md).

## Non-Negotiable Constraints

- **Git Identity & Cryptographic Signing**: Author all commits as `TinkeringDao <203040288+TinkeringDao@users.noreply.github.com>` with `ED25519` SSH signing enabled (`commit.gpgsign=true`).
- **OPSEC & Zero-PII**: Never include personal names, local host filesystem paths, postal codes, geographic coordinates, or device serial numbers in source files, documentation, screenshots, or commit messages.
- **100% Offline & Clean Architecture Boundaries**: Keep `:domain` pure Kotlin with zero Android dependencies, preserve `"The Recorder Wins"` microphone preemption, and never introduce analytics, telemetry, or non-model network requests.
- **Verification Before Push**: Run `./gradlew testDebugUnitTest lintDebug assembleDebug` and `python3 -m py_compile tools/gazetteer/build_places.py` before pushing to a PR branch.
