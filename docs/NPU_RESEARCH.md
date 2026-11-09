# NPU-native models for the razr 70 plus: research and recommendation

Research date: 2026-10-08. Four parallel web reviews covered:
- Qualcomm's own stack (QAIRT, Genie, GenieX, AI Hub)
- open NPU runtimes
- small LLMs for English and German
- NPU speech-to-text

Every claim below was sourced in those reviews; the key sources are listed at the end. "Est." marks an estimate, "UNVERIFIED" something nobody has measured or confirmed.

**The question:** which LLM uses the razr's NPU natively, is fast and power-efficient, and does the architecture have to change? English and German only; airplane mode.

## Short answer

1. **The chip is the constraint.**
   - The razr's SM8635 (Snapdragon 8s Gen 3) has a Hexagon v73 NPU with only 4 MB of fast NPU memory (VTCM), half that of the 8 Gen 2/3.
   - Qualcomm's own tooling marks it as not supporting FP16 on the NPU.
   - No vendor publishes ready-made NPU models for this chip: AI Hub, LiteRT-LM and GenieX all skip it.
2. **The NPU pays off for reading long prompts (prefill), not for writing output (decode).**
   - On similar Snapdragons, prefill on the NPU is about 10x faster and 3-10x more energy-efficient than on the CPU.
   - Decode is limited by memory bandwidth, so the CPU matches the NPU and sometimes uses less energy.
   - Our insight and summary jobs read about 3k tokens and write about 250, so prefill dominates.
3. **The biggest battery lever is speech-to-text, not the LLM.** STT runs for the whole recording. sherpa-onnx added Parakeet TDT on Qualcomm's QNN NPU runtime in v1.13.4 (July 2026), so we can keep the best English and German model and move it to the NPU.
4. **The deciding unknown: can an app (not adb shell) open an NPU session on Motorola's firmware?** On a Samsung v73 phone, adb works but the app gets "Permission denied". This must be tested on the razr before any NPU work.
5. **The architecture changes, but it is not a rewrite.** We keep sherpa-onnx and llama.cpp and add:
   - a backend layer that picks NPU, GPU or CPU at startup and falls back if a backend fails;
   - support for more than one loaded model;
   - one bundled QNN version shared by STT and LLM (QNN allows one version per process);
   - an on-device "optimizing for your phone" compile step.

## Recommended stack

| Job | Model | Runtime and backend | Why |
|---|---|---|---|
| Transcription (always on) | **Parakeet TDT 0.6B v3** (CC-BY-4.0). Open ASR Leaderboard WER: English 5.66, German 4.12 | **sherpa-onnx 1.13.8 with QNN** (NPU). Use the generic `android-aarch64` QNN package (417 MB), which compiles its NPU binary on the phone at first run. Keep today's int8 CPU path as the fallback | Still the most accurate EN/DE model of its size. Est. 3-10x less STT energy on the NPU, about 0.5 W while transcribing |
| Periodic insights (JSON) | **Gemma 4 E2B-it, Google QAT Q4_0** (Apache 2.0, 3.35 GB). IFEval 94.6; EuroEval German #17 | **llama.cpp at a current tag** (b9574 → b11514 or newer): **OpenCL on the Adreno 735 for prefill** now, Hexagon NPU later if it proves correct. GBNF grammar keeps the JSON valid | Best instruction following and German at this size. Its sliding-window attention keeps 3k-token windows cheap. Q4_0 is the NPU and Adreno-friendly type |
| Final summary on stop | **Gemma 4 E4B-it QAT Q4_0** (5.15 GB), or the lighter **Qwen3-4B-Instruct-2507 Q4_0** (2.38 GB) | llama.cpp on the CPU or GPU, loaded after recording stops | Best German quality available. 4B-class models crashed on the v73 NPU in public reports, so don't put them on the NPU yet |
| Live EN↔DE translation | **Mozilla Bergamot** (MPL-2.0, about 31M parameters, about 32 MB per direction). Optional second pass with **Hy-MT2-1.8B** (Apache 2.0) | CPU, so it doesn't compete with STT or the LLM for the NPU | A dedicated translation model beats every general LLM of 4B or less. COMET-22: EN→DE 0.867, DE→EN 0.881. Worth testing as an alternative: Canary-180M-flash (speech-to-translation directly) |
| Speaker labels (later) | pyannote segmentation 3.0 plus TitaNet-small or WeSpeaker embeddings | sherpa-onnx, offline after recording | Small, already supported. Nemotron-3-Diarization has no Android NPU build yet |
| Q&A over recordings (later) | EmbeddingGemma 2 (270M, Apache 2.0) | CPU or GPU | Fits next to the rest; its NPU builds skip SM8635 too |

Rejected:
- **Whisper:** worse in German, and it only translates into English.
- **LiteRT-LM:** no SM8635 NPU files, and a 1,280-token context.
- **GenieX:** validated on Android only on SM8750 and SM8850.
- **Hunyuan:** licence excludes the EU.
- **EXAONE, Tiny Aya, MobileLLM-Pro:** non-commercial.
- **LFM2.5:** revenue-capped licence and no German evidence.
- **MLC-LLM:** no releases since 2023.

## What the NPU route looks like, by runtime

| Runtime | SM8635 status | Notes |
|---|---|---|
| llama.cpp Hexagon (ggml-hexagon) | v73 build exists; **experimental** | FP16 matrix maths on FP16-less v73 chips gives garbage output. An integer path (draft PR #29740) fixes it. A Poco F6 (same SoC) hit unresolved session errors. Unsigned process domain, so no signing needed; needs `ADSP_LIBRARY_PATH`. Built in the `snapdragon-toolchain` Docker image (linux/amd64, runs on the Mac) |
| llama.cpp OpenCL (Adreno) | Starts on the Adreno 735; speed UNVERIFIED | About 450 tok/s prefill on an 8 Gen 3 against about 166 on the CPU. **The safe first step** |
| Qualcomm QAIRT/Genie | QAIRT lists SM8635 as soc_model 68 | Compiling LLM context binaries needs **x86 Linux** plus the model weights on that host. The development Mac can't download weights (corporate proxy), so this needs a separate Linux machine outside that network. Est. Qwen3-1.7B w4a16: prefill 400-1,000 tok/s, decode 25-40 tok/s |
| ExecuTorch + QNN | SM8635 only on `main` (merged 2026-09-03), integer only | Same Linux x86 requirement, about 100 GB of host RAM for export. Recipes for Gemma 3 1B and Gemma 4 E2B. A JNI correctness issue is open |
| sherpa-onnx QNN (STT) | Generic package compiles on the phone | **No host compile, so no weights on the Mac.** We must build sherpa-onnx 1.13.8 with `SHERPA_ONNX_ENABLE_QNN=ON` ourselves; the release has no prebuilt QNN Android lib |

QNN runtime licence: the qnn-runtime AAR ships under Qualcomm's "AI Stack License".
- It allows the libraries inside our app.
- Qualcomm can revoke it.
- It forbids emotion recognition at work and employment uses, so never add sentiment or performance scoring of meeting participants.

## Architecture changes

1. **`LlmEngine` backends with a capability probe.**
   - Today the native layer holds a single global model (`ai_chat.cpp`).
   - Replace it with handles, so the insights, summary, translation and embedding models can coexist.
   - Probe NPU, GPU and CPU at first start, remember what works, and fall back automatically.
2. **Route each task to a device.**
   - Prefill-heavy jobs (insights, summary) go to the GPU or NPU.
   - Decode-heavy jobs (translation, chat) go to the CPU.
   - Set llama.cpp devices explicitly: OpenCL and Hexagon both register as GPU devices and would otherwise split layers between them.
3. **One QNN version.**
   - Bundle one `qnn-runtime` version and use it for both sherpa-onnx STT and any LLM QNN path, or run the LLM in its own process.
   - Never mix it with the phone's `/vendor` copies.
4. **STT pipeline for fixed-shape NPU models.**
   - Use a VAD forced split of 10-15 s instead of 30 s.
   - Produce partials at most every 3 s.
   - Keep the token timestamps that are thrown away today.
   - Add a one-time on-device compile during onboarding.
5. **Model provisioning.**
   - Weights come to the phone, never the Mac, through the in-app import plus `adb shell curl` for development.
   - Expect 4-9 GB of models. Checksums are required.

## Plan

**Phase 0: on-device check (1-2 days). This decides whether the NPU path is used at all.**

1. App-context NPU access: a minimal APK that opens an HTP session (unsigned process domain) from the app process. Check logcat for `Permission denied` or `createUnsignedPD`.
2. llama.cpp b11514 `android-arm64-snapdragon` build on the phone:
   - `test-backend-ops -b HTP0 -o MUL_MAT` checks FP16 correctness;
   - `llama-bench` on CPU, OpenCL and HTP0 with `gemma-4-E2B-it-qat-q4_0` and `gemma-3-1b-it-qat-q4_0`, `-p 3072 -n 128`.
3. sherpa-onnx 1.13.8 QNN with the Parakeet `android-aarch64` package: first-run compile time, real-time factor, and word error rate on 10 English and 10 German clips, compared with CPU.
4. Energy per job with `dumpsys batterystats` or `BatteryManager.CURRENT_NOW`.

**Phase 1: no NPU needed (about 1 week).**
- llama.cpp upgrade plus OpenCL.
- Gemma 4 E2B QAT Q4_0 with GBNF JSON.
- Bergamot EN↔DE.
- sherpa-onnx upgrade, partials every 3 s, token timestamps.

**Phase 2, only if Phase 0 passes.**
- STT on the NPU through sherpa-onnx QNN.
- LLM prefill on the NPU through llama.cpp Hexagon (if correct), or else Genie/ExecuTorch compiled on a Linux x86 host.

**Phase 3.** Diarization, Q&A over recordings (EmbeddingGemma 2), and the final-summary model (E4B or Qwen3-4B).

## Expected numbers (Est., not yet measured on SM8635)

| Job | Today (CPU, Gemma 3 1B Q8_0) | Target |
|---|---|---|
| STT while recording | CPU 2 threads, re-decoding partials every 1.5 s | NPU, about 0.5 W, 3-10x less energy |
| One insight (3k tokens in, 250 out) | about 25-45 s at 5-8 W (about 125-360 J) | GPU prefill a few seconds plus CPU decode about 10 s. With NPU prefill, about 20-70 J |
| Live translation | Gemma 3 1B every 30 s | Bergamot per finished segment, near-instant, tens of MB |

## Key sources

- **Device and chip support**
  - QAIRT supported devices (SM8635 = soc 68, v73): https://docs.qualcomm.com/doc/80-63442-10/topic/QNN_general_overview.html
  - ExecuTorch SM8635 support, "FP16 not supported" (PR #22542, 2026-09-03): https://github.com/pytorch/executorch/pull/22542
- **llama.cpp NPU and GPU**
  - FP16-less v73 issues: https://github.com/ggml-org/llama.cpp/issues/29473 and draft PR https://github.com/ggml-org/llama.cpp/pull/29740
  - Poco F6 (SM8635) Hexagon errors: https://github.com/ggml-org/llama.cpp/issues/22352
  - llama.cpp Snapdragon backend docs: https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/snapdragon/README.md
- **NPU access from apps**
  - Unsigned-PD denial in app context (Samsung A36): https://github.com/qualcomm/ai-hub-apps/issues/157
- **Energy studies**
  - NPU vs CPU energy (2026): https://arxiv.org/abs/2607.05475
  - llm.npu prefill energy (8 Gen 2): https://arxiv.org/abs/2407.05858
- **Models**
  - Qualcomm AI Hub per-model performance: https://github.com/qualcomm/ai-hub-models
  - Gemma 4 model card: https://ai.google.dev/gemma/docs/core/model_card_4
  - Gemma 4 QAT GGUF: https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf
  - EuroEval German leaderboard: https://euroeval.com/leaderboards-csv/german_generative.csv
- **Speech-to-text**
  - sherpa-onnx QNN (Parakeet TDT from 1.13.4): https://github.com/k2-fsa/sherpa-onnx/blob/master/CHANGELOG.md, https://k2-fsa.github.io/sherpa/onnx/qnn/index.html
  - Open ASR Leaderboard: https://huggingface.co/spaces/hf-audio/open_asr_leaderboard
- **Translation**
  - Bergamot model registry: https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data/db/models.json
  - Hy-MT2-1.8B: https://huggingface.co/tencent/Hy-MT2-1.8B
