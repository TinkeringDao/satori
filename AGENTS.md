# AGENTS.md

Context pointers for agents operating in `TinkeringDao/satori`. Read [`GLOSSARY.md`](GLOSSARY.md) for canonical domain terms (`Grille`, `Cover Screen`, `Quick Key`, `"The Recorder Wins"`, `RecordingFinisher`, `segment_readings`, `ModelLoadPacing`, `TodoTimeResolver`, `TodoGrounding`).

- **Modifying Compose UI, Cover Screen (`1080×1272`, `displayId = 1`, `232 px` camera band), or Inner Screen (`1080×2640`) layouts**: read [`docs/GRILLE_DESIGN.md`](docs/GRILLE_DESIGN.md), [`docs/QUICK_KEY.md`](docs/QUICK_KEY.md), and the `:app` / `:presentation` modules.
- **Updating the 7-stage post-stop finishing pipeline, to-do time resolution, grounding, or mind-map indexing**: read [`GLOSSARY.md`](GLOSSARY.md) and inspect `:domain` (`FinishRecordingUseCase`, `ExtractTodosUseCase`, `TodoTimeResolver`, `TodoGrounding`, `MapRecordingUseCase`, `RefreshThemeSummariesUseCase`) and `:presentation` (`RecordingFinisher`).
- **Modifying streaming/offline speech recognition (`Parakeet v3/v2`, `Cohere Transcribe`, `IndicConformer`, `Silero VAD`) or the internal Malayalam-to-English reading bridge (`IndicTrans2 200M`)**: inspect `:feature-stt`, `:lib-sherpa-onnx`, `:feature-reader`, `:lib-reader-onnx`, and `com.satori.domain.reading`.
- **Changing local LLM inference (`Gemma 3 1B`, `Gemma 4 E2B`, `Qwen 3.5 0.8B`), GBNF grammar schemas, system-prompt KV disk caching, or cooperative memory pacing (`120 MB/s` pre-faulting, `MADV_PAGEOUT`, `MemAvailable` floors)**: inspect `:feature-llm`, `:lib-llama-android`, `:data` (`ModelLoadPacing`, `MemoryFloor`), and [`docs/PERFORMANCE_DIAGNOSTICS.md`](docs/PERFORMANCE_DIAGNOSTICS.md).
- **Reviewing statutory interoperability, non-circumvention, privacy, or security boundaries**: read [`LEGAL.md`](LEGAL.md) and [`SECURITY.md`](SECURITY.md).

## Fully Autonomous Development Pipeline

All autonomous agent contributions, pull request reviews, remediations, and merges in this repository are governed by the 6-stage specification in [`docs/agents/autonomous-pipeline.md`](docs/agents/autonomous-pipeline.md) and enforced on every Pull Request by [`.github/workflows/autonomous-pr-review.yml`](.github/workflows/autonomous-pr-review.yml) and [`.github/workflows/build.yml`](.github/workflows/build.yml).

### Project Verification Commands

Run these verification checks locally before pushing any commit:

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
python3 -m py_compile tools/gazetteer/build_places.py
bash -n gradlew
```

### Verification Checklist & Pre-Merge Gate Commands

| Gate | Requirement | Local Pre-Merge Command |
| :--- | :--- | :--- |
| **Gate 1: OPSEC & Zero-PII** | Zero personal identifiers, local host paths, postal codes, or coordinates in repo tree | `grep -rnI -iE "<FORBIDDEN_PII_PATTERN>" . --exclude-dir=.git --exclude-dir=.gradle --exclude-dir=.kotlin --exclude-dir=.cxx --exclude-dir=third_party --exclude-dir=build --exclude="local.properties" --exclude="*autonomous-pr-review.yml"` |
| **Gate 2: Cryptographic Signatures** | Every commit signed with `ED25519` SSH key (`verified == true`) | `git log -1 --show-signature` |
| **Gate 3: Legal & Autonomous Spec** | `LEGAL.md` (with German Statutory Addendum), `LICENSE`, `SECURITY.md`, `CODE_OF_CONDUCT.md`, `.github/CODEOWNERS`, `AGENTS.md`, `CLAUDE.md`, `docs/agents/autonomous-pipeline.md` present | `grep -q "Addendum: German Statutory References" LEGAL.md && test -s docs/agents/autonomous-pipeline.md` |
| **Gate 4: Build, Lint & Unit Tests** | Unit tests across all modules, Android Lint (`0` errors), debug APK assembly, and script syntax checks | `./gradlew testDebugUnitTest lintDebug assembleDebug && python3 -m py_compile tools/gazetteer/build_places.py` |

## Agent skills

### Issue tracker

Issues and specs for this repo live in GitHub Issues. See [`docs/agents/issue-tracker.md`](docs/agents/issue-tracker.md).

### Triage labels

Uses the five default canonical triage labels (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See [`docs/agents/triage-labels.md`](docs/agents/triage-labels.md).

### Domain docs

Single-context (`GLOSSARY.md` + `docs/` at the repo root). See [`docs/agents/domain.md`](docs/agents/domain.md).
