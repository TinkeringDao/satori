# Glossary

Core domain, hardware, and on-device inference terminology used across the Satori codebase.

---

## Product & Hardware

### Satori (`com.satori`)
An offline Android voice recorder, live transcriber, first-person note synthesizer, to-do & idea extractor, English↔German speech translator, and cross-recording mind-map builder designed for both standard displays and the Motorola razr 70 plus external cover screen.

### Cover Screen (`displayId = 1`)
The Motorola razr 70 plus external POLED display (`1080×1272` px, `480×410 dp`). Its `232 px` camera band houses the physical camera lenses and LED flash (`GrilleCameraBand` in `app/src/main/java/com/satori/ui/design/GrilleCameraBand.kt`), with dedicated two-column compact layouts that adapt to standard (`ROTATION_0`) and tent/flipped (`ROTATION_180`) cover orientations.

### Inner Screen (`displayId = 0`)
The primary unfolded 6.9-inch `1080×2640` POLED display (`22:9` aspect ratio) hosting the full-height `Grille` interfaces.

### Grille
Satori's high-contrast typographic UI system (`app/src/main/java/com/satori/ui/design/Grille*.kt`). Supports both a dark POLED palette (`#0A0A0A` surface, `#F2F0EC` ink, `#D94E34` recording accent) and a warm paper-and-ink light palette (`#F4F1EA` surface, `#141413` ink), paired with `Doto` and `Space Grotesk` typography, tabular numerals, hairline dividers, and a permanently docked bottom record bar (`docs/GRILLE_DESIGN.md`).

### Quick Key (`QuickKeyActivity`)
A windowless trampoline activity (`com.satori.quickkey.QuickKeyActivity`) bound to a double-press of the hardware power key via Motorola's `com.motorola.mykey` (`Settings.System.my_key_tap_app` and `Settings.Secure.double_tap_power_button_gesture = 2`). Filters accidental bounce presses within `1,000 ms` (`QuickKeyRepeatFilter`) and routes `QuickKeyRequest` to `MainActivity` to start or stop a recording (`docs/QUICK_KEY.md`).

### "The Recorder Wins" (`ActiveRecorder`, `GatedAiWork`)
Satori's core preemption invariant: starting a recording immediately cancels or pauses any background or interactive AI job (`RecordingFinisher`, `Better transcript after Stop`, the IndicTrans2 reading step, `ReadingBackfill`, `Transcribe again`, `Rewrite notes`, `Find to-dos and ideas again`, or `Mind map` generation), evicts model weights from RAM, and gives the microphone priority.

---

## Recording, Categories & Speech-to-Text (STT)

### Session (`TranscriptionSession`)
A single recorded voice note, meeting, or translation session stored in Room (`transcription_sessions`), associated with a category (`categoryId`), recording mode (`RecordingMode`), recording language (`RecordingLanguage`), optional offline GPS place fix (`LocationFix`), transcript segments (`TranscriptionSegment`), and synthesized notes (`LlmInsight`).

### Categories & Recording Modes (`Category`, `RecordingMode`)
User-selectable recording types shown in the `Record as` sheet (`RecordAsSheet.kt`) and managed in `CategoriesScreen.kt`:
- **Starter categories**: **Voice note** (`SIMPLE_LISTENING`), **Meeting** (`SHORT_MEETING` / `LONG_MEETING`), **Idea**, and **To-do**, plus custom user-created categories.
- **Translate mode (`REAL_TIME_TRANSLATION`)**: Spoken **English or German** is transcribed and translated into the target language (`Translate into`: English or German) either live while speaking or after `Stop`, displayed in a dedicated **Translation** tab.
- **Change type**: Re-files an existing recording into a different category and rewrites its notes in that category's style while preserving its title and to-dos.

### Segment (`TranscriptionSegment`)
A timestamped utterance emitted by the streaming or post-stop speech recognizer and persisted in `transcription_segments`. Tapping a segment on the details page seeks the audio player (`RecordingPlayer`) to that line's start time (`Play from here`).

### Streaming STT (`SherpaOnnxDataSource`)
Real-time speech recognition during active recording, driven by Silero VAD (`silero_vad.onnx`) and `sherpa-onnx` over 16 kHz mono PCM audio chunks (`:feature-stt`, `:lib-sherpa-onnx`), with optional custom vocabulary boosting (`Words to recognize` via `bpe.vocab` in `Parakeet v3`).

### Better Transcript After Stop & Transcribe Again (`TranscribeAfterStopUseCase`, `TranscribeAgainUseCase`)
- **Better transcript after Stop**: Optional step at the start of `RecordingFinisher` that re-transcribes a finished recording's WAV audio using a higher-accuracy offline model (`Parakeet v2` for English or `Cohere Transcribe` for English and German, or a full-utterance second pass with `IndicConformer` for Malayalam) before notes are written.
- **Transcribe again**: User-initiated action on a recording's details page (`RecordingAudioViewModel`) that replaces the transcript from the saved audio using any chosen speech model.

### Audio Retention & Compression (`AudioRetentionJob`)
Background maintenance job (`data/src/main/java/com/satori/data/audio/AudioRetentionJob.kt`) enforcing the user's age-based rules under `Settings → Keep audio of recordings`: transcoding older uncompressed WAV files (`~2 MB/min`) to AAC/M4A (`~0.25 MB/min`) and/or deleting older audio files while keeping all text, unless a recording is marked **Keep this audio** (`audioKept = true`).

### Speech Model Identifiers (`SpeechModelId`)
Defined in `domain/src/main/java/com/satori/domain/model/SpeechModelId.kt`:
- **`PARAKEET_V3` (`NVIDIA Parakeet TDT 0.6B v3 INT8`)**: Default streaming/offline transducer for English and German (~670 MB).
- **`PARAKEET_V2_EN` (`NVIDIA Parakeet TDT 0.6B v2 INT8`)**: English-focused NeMo transducer for *Better transcript after Stop* and *Transcribe again* (~660 MB).
- **`COHERE_TRANSCRIBE` (`Cohere Transcribe INT8`)**: High-accuracy encoder-decoder recognizer for post-stop re-transcription in English and German (~2.9 GB).
- **`INDICCONFORMER_ML` (`AI4Bharat IndicConformer Malayalam 120M INT8`)**: Hybrid CTC/RNNT Conformer model (`model.int8.onnx`) for Malayalam (`ml`) speech recognition (~140 MB).

---

## Internal Malayalam Reading Bridge (`:feature-reader`, `:lib-reader-onnx`)

### Reading Step (`ReaderDataSource`, `LineReader`, `segment_readings`)
An internal post-stop pipeline stage between *Lines* and *Notes* for Malayalam recordings (`MALAYALAM_FOUNDATION_DESIGN.md`). Uses AI4Bharat's **IndicTrans2 Distilled 200M INT8** (3 ONNX graphs on ONNX Runtime `1.28.2` + SentencePiece `model.SRC` and `model.TGT`, beam size 5) to write English readings of Malayalam transcript segments into Room (`segment_readings`, DB v13). The visible transcript remains in Malayalam, while downstream LLM steps (Notes, Auto-naming, To-dos & Ideas, and Mind Map) read the English `segment_readings` so 1B-class models do not have to parse Malayalam script directly.

### Malayalam Text Pipeline (`com.satori.domain.reading`)
Deterministic Unicode normalization, IndicNLP trivial tokenization, Malayalam-to-Devanagari offset transliteration (`0x00–0x6F`), vocabulary mapping, and Moses detokenization feeding the IndicTrans2 ONNX sessions (`domain/src/main/java/com/satori/domain/reading/`).

### Reading Backfill (`ReadingBackfill`)
Idle background worker that populates `segment_readings` and refreshes unedited notes, names, and to-dos for Malayalam recordings created before DB schema v13, respecting owner-edit provenance flags (`name_by_owner`, `notes_kept_before_redo`, `kept_before_redo`).

---

## Notes, To-Dos, Ideas & Mind Map

### Finishing Pipeline (`RecordingFinisher`, `FinishRecordingUseCase`)
Sequential background pipeline running in `LlmProcessingService` after a recording stops:
1. **Lines** (`Finishing up`) — flushes trailing streaming STT audio.
2. **Transcript** (`Making a better transcript`) — optional post-stop re-transcription.
3. **Reading** (`Finishing up`) — IndicTrans2 Malayalam-to-English reading pass (`segment_readings`) for Malayalam recordings.
4. **Notes** (`Finishing up`) — grammar-constrained first-person bullet points (`GenerateFinalInsightUseCase` / `GenerateBatchInsightUseCase`).
5. **Name** (`Writing a name`) — 2–5 word subject title (`AutoNameSessionUseCase`).
6. **To-dos & Ideas** (`Finding to-dos`) — grounded task, appointment, and idea extraction (`ExtractTodosUseCase`).
7. **Mind Map** (`Linking topics`) — topic extraction, theme linking, and cross-recording theme summary refresh (`MapRecordingUseCase`, `RefreshThemeSummariesUseCase`).

### To-Dos, Time Resolver & Grounding (`Todo`, `TodoTimeResolver`, `TodoGrounding`)
- **`TodoTimeResolver` & `MalayalamTodoCues`**: Deterministic English, German, and Malayalam date/time spotter that resolves relative expressions (`"Friday at 9"`, `"in 20 minutes"`) against the recording's start timestamp into `T1`, `T2` tokens before the LLM runs.
- **`TodoGrounding`**: Verifies that every extracted to-do or idea cites a real segment and uses words from that segment, dropping hallucinated or prompt-echoed items.
- **Direct vs. Suggested To-Dos (`TodoCapture`, `TodoStatus`)**: Explicit reminders (`COMMITMENT`) go straight to `OPEN` on Home and schedule an exact alarm (`AndroidReminderAlarm`); tasks asked by others (`ASKED_BY_OTHER`), inferred tasks (`INFERRED`), and appointments (`APPOINTMENT`) go to the `SUGGESTED` review sheet (`Keep`, `Dismiss`, `Move to ideas`).

### Ideas (`Idea`, `IdeasScreen`)
Suggestions or wishes extracted from recordings (`"type": "idea"`) or recordings made in the **Idea** category. Stored in the `ideas` table, accessible from Home (`%d ideas, %d new`) and `IdeasScreen.kt`, and convertible into to-dos (`Make it a to-do`). Ideas survive even if their source recording is deleted (`Without a recording`).

### Mind Map (`MindNode`, `MindEdge`, `MindMapScreen`)
An ego-centric knowledge graph centered on **You** (`Show as map` 2D canvas and `Show as list` hierarchy):
- **Theme (`MindNodeKind.THEME`)**: A recurring subject across recordings, carrying an AI-maintained 2–4 sentence cross-recording summary (`RefreshThemeSummariesUseCase`) and optional `Keep in Core` pinning.
- **Topic (`MindNodeKind.TOPIC`)**: What a single recording said under a theme (1–4 words + up to 3 points), linking back to that recording.
- **Views (`MindMapFilter`)**: **Core** (pinned + top themes), **Recent** (last 7 days), and **All**.

---

## Local LLM & Memory Governance

### LLM Model Variants (`LlmModelVariant`)
Defined in `domain/src/main/java/com/satori/domain/model/LlmModelVariant.kt`:
- **`Q8_0` (`Standard` — `Gemma 3 1B IT Q8_0`)**: Default model (~1.0 GB) for live notes, finishing, and EN↔DE translation.
- **`IQ4_NL` (`Efficient` — `Gemma 3 1B IT IQ4_NL`)**: Compact ~650 MB quantization of Gemma 3 1B IT.
- **`QWEN3_5_Q8_0` (`Qwen 3.5 0.8B Q8_0`)**: Compact ~870 MB alternative model.
- **`GEMMA4_E2B_Q4_0` (`Gemma 4 E2B IT QAT Q4_0`)**: Optional ~3.3 GB model used for *AI for finished recordings* when `MemAvailable` passes the safety check.

### Model Load Pacing (`ModelLoadPacing`)
Cooperative memory-bandwidth and page-cache regulator (`data/src/main/java/com/satori/data/monitor/ModelLoadPacing.kt`). Pre-faults model weights into memory at a capped rate (`120 MB/s`, paused during active recording) with `MAP_POPULATE` disabled (`0001-load-progress-bytes.patch`, `0002-map-populate-switch.patch`, `0003-mapped-hook.patch`) so large `mmap` reads do not starve the Android UI thread or audio capture buffer, and calls `madvise(MADV_PAGEOUT)` when releasing weights.

### Memory Floor & Room Guards (`MemoryFloor`, `AnswerFloor`, `ParseRoom`, `DeviceMemory`)
Runtime checks (`data/src/main/java/com/satori/data/monitor/` and `domain/src/main/java/com/satori/domain/monitor/DeviceMemory.kt`) against `/proc/meminfo` (`MemAvailable`) verifying sufficient free memory headroom before loading speech, reader, or LLM weights.

### System-Prompt Disk Cache (`forgetPromptCache`)
Because Satori's system prompts contain no per-recording placeholders, `:lib-llama-android` caches their evaluated KV states on disk so subsequent sessions skip prompt prefill. Can be cleared from `Settings → Advanced → Forget cached AI prompts`.

---

## First-Run & Home Status Strip

### First-Run Coordinator (`FirstRunCoordinator`)
App-wide state machine (`domain/src/main/java/com/satori/domain/firstrun/FirstRunCoordinator.kt`) managing onboarding stages (`FirstRunStage`), microphone permission prompting, and background model downloads (`FirstRunPlan`) with `300 MB` storage headroom checks (`StorageCheck`) and VPN-aware network capability inspection (`NetworkMonitor`).

### Status Strip (`StripState`)
Compact banner at the top of the home session list (`HomeList.kt`) surfacing background model download progress, paused/blocked network states, or one-time contextual nudges (`NudgeId`).
