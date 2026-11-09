# Contributing to Satori

Contributions that improve on-device latency, memory stability, cover-screen ergonomics, or translation/transcription accuracy are welcome.

## Prerequisites

- **JDK 17** (`temurin-17` or `openjdk@17`)
- **Android SDK** (`platforms;android-35`, `build-tools;34.0.0`)
- **Android NDK** (`29.0.13113456`) and **CMake** (`3.31.6`) for `:lib-llama-android` and `:lib-reader-onnx`
- **Git LFS** (`git lfs install && git lfs pull`) for bundled native libraries and `silero_vad.onnx`

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

## Verification Before Opening a Pull Request

Every pull request must pass the full unit test suite and Android Lint across all modules:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

## Licensing & Legal Compliance

By submitting a pull request, you agree that your contribution is licensed under the **Apache License, Version 2.0** ([`LICENSE`](LICENSE)) and complies with the statutory interoperability and clean-room rules in [`LEGAL.md`](LEGAL.md) (no proprietary OEM code, no third-party binary blobs, and no personal/device identifiers).
