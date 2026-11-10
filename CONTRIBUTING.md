# Contributing to Satori

Contributions that improve on-device latency, memory stability, cover-screen ergonomics, test coverage, or transcription/translation accuracy are welcome.

## Ground Rules

- Read and follow [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md) in all issues, pull requests, commit messages, and discussions.
- Review [`LEGAL.md`](LEGAL.md) before submitting protocol, native library, or hardware-interoperability changes. Clean-room boundaries, statutory interoperability rules, and zero proprietary binary redistribution are strictly enforced.
- Do not include personal data, private network addresses, device serial numbers, MAC addresses, or credentials in code, tests, logs, or fixtures.
- Report security vulnerabilities privately via [`SECURITY.md`](SECURITY.md) rather than public issues.

## Prerequisites

- **JDK 17** (`temurin-17` or `openjdk@17`)
- **Android SDK** (`platforms;android-35`, `build-tools;34.0.0`)
- **Android NDK** (`29.0.13113456`) and **CMake** (`3.31.6`) for `:lib-llama-android` and `:lib-reader-onnx`
- **Git LFS** (`git lfs install && git lfs pull`) and initialized submodules (`git submodule update --init --recursive`)

See [`docs/DEV_SETUP.md`](docs/DEV_SETUP.md) for the complete local build and model provisioning guide, and [`GLOSSARY.md`](GLOSSARY.md) for domain terminology.

## Architectural & Code Standards

1. **Module Boundaries:**
   - `:domain` is pure Kotlin with zero Android framework dependencies.
   - `:data` implements repository contracts, Room DAOs, DataStore preferences, and native data sources.
   - `:presentation` holds Hilt ViewModels and immutable UI state classes (`StateFlow`).
   - `:app` contains Jetpack Compose UI (`Grille` design system), foreground services, and activity entry points.
2. **Memory & Thermal Discipline:**
   - Any code path that loads or unloads a native model (`llama.cpp`, `sherpa-onnx`, or the IndicTrans2 ONNX reader) must go through `ModelLoadPacing` (`data/src/main/java/com/satori/data/monitor/ModelLoadPacing.kt`) and respect the `120 MB/s` paced pre-fault rate and `MemAvailable` headroom checks (`MemoryFloor`, `AnswerFloor`, `ParseRoom`).
3. **Privacy in Logs:**
   - Never log transcript segments, user notes, titles, or file contents to `Log.*`. Follow the numeric-only field logging contract in [`docs/FIELD_LOG.md`](docs/FIELD_LOG.md).
4. **Cover Screen & Dual-Display Support:**
   - UI changes must work on both the inner main screen (`1080x2640`) and the Motorola Razr external cover screen (`1080x1272`, `displayId = 1`, accounting for the `232 px` camera band via `GrilleCameraBand`).
5. **Regular Expressions:**
   - Android uses ICU regular expressions, while JVM unit tests run on `java.util.regex`. Use the character classes in `WordClasses.kt` and run `RegexRunsTest` / `IcuRegexTest` when modifying text parsers.

## Pull Request Workflow

1. Create a focused topic branch from `main` (`feat/...`, `fix/...`, `docs/...`, `chore/...`, `ci/...`).
2. Keep commits atomic, use Conventional Commit headers (`feat:`, `fix:`, `docs:`, `ci:`, `refactor:`, `test:`), and sign all commits (`git config commit.gpgsign true`).
3. Run the local verification suite before opening a pull request:

```bash
python3 -m py_compile tools/gazetteer/build_places.py
bash -n gradlew
./gradlew testDebugUnitTest lintDebug assembleDebug
```

4. Open a pull request against `main` summarizing the problem, root cause or design choice, and verification output. All pull requests are licensed under the Apache License, Version 2.0 ([`LICENSE`](LICENSE)) and squash-merge onto `main` once `ci` and `security` checks pass.
