# lib-reader-onnx: the reader

IndicTrans2-Dist (indic-en, 200M, the int8 ONNX export pinned in R0) on the ONNX Runtime 1.28.2
that sherpa-onnx already ships, with SentencePiece built in. It turns one Malayalam segment's source
ids into English target ids; the text work on both sides (IndicProcessor's rules, the ids through
`dict.SRC.json` and `dict.TGT.json`, the detokenizer) is R1's, in `domain/.../reading`.

## Files

| Path | What |
|---|---|
| `src/main/cpp/reader_engine.{h,cpp}` | The three sessions, their settings (D3, D4), one segment's decoding, cancel and release |
| `src/main/cpp/beam_search.{h,cpp}` | Beam search over the logits, with no ONNX Runtime in it |
| `src/main/cpp/kv_cache.{h,cpp}` | The attention caches of one segment, allocated once, reordered by parent |
| `src/main/cpp/spm_tokenizer.{h,cpp}` | SentencePiece's EncodeAsPieces behind a UTF-8 check |
| `src/main/cpp/reader_jni.cpp` | The JNI of `ReaderNative` |
| `src/main/cpp/reader_status.h` | The statuses every native call returns |
| `src/main/cpp/CMakeLists.txt` | `libreader-onnx.so`: SentencePiece static, sherpa-onnx's `libonnxruntime.so` linked as `-lonnxruntime` |
| `patches/` | Satori's changes to the pinned SentencePiece; `0001` quotes a flag of its CMake that a checkout path with a space or a parenthesis breaks |
| `src/main/java/com/satori/reader/` | `ReaderEngine`, `ReaderTokenizer`, `ReaderException`, `ReaderNative` |
| `src/test/` | Host tests (below) |

Pinned outside the module: `third_party/onnxruntime-headers-1.28.2/` (the C API headers, with their
sources and sha256 in its README) and `third_party/sentencepiece` (a submodule at v0.2.1, 31646a4).

## Building

- `git submodule update --init third_party/sentencepiece` and `git lfs pull` (sherpa-onnx's
  libraries) first; the CMake configure stops with a message naming either when it is missing.
- The configure applies `patches/*.patch` to `third_party/sentencepiece`, each once, as
  lib-llama-android's CMake applies its own to llama.cpp. They are never committed to the
  submodule, so `git status` shows it as `m` after a build.
- The native build is optimized in debug builds too (`-DCMAKE_BUILD_TYPE=Release`), as the debug APK
  is the one the phone checks time, and builds only the `reader-onnx` target.
- The APK holds `lib/arm64-v8a/libreader-onnx.so` (about 0.9 MB) and still one `libonnxruntime.so`,
  sherpa-onnx's: the reader's library needs it by soname, imports only `OrtGetApiBase@VERS_1.28.2`
  from it, and exports only its JNI functions.
- The module's own native output (its JNI artifact, the AAR) holds `libreader-onnx.so` alone.
  ONNX Runtime is linked as `-L<sherpa-onnx's jniLibs> -lonnxruntime`: linked by its path or as an
  IMPORTED target it counts as a runtime file of the target, and the Android Gradle plugin copies
  it into this module too, which the app's merge then drops only because the bytes are identical.
- Gradle runs that build no native code (lint in a checkout without the submodule, as the shared
  `gl` command does) leave out this module's two CMake tasks beside lib-llama-android's:
  `-x ':lib-reader-onnx:configureCMakeRelease[arm64-v8a]' -x ':lib-reader-onnx:buildCMakeRelease[arm64-v8a][reader-onnx]'`.
  `:app:lintDebug` is the task that pulls them in; the unit-test and compile tasks don't.

## Kotlin API (package `com.satori.reader`)

```kotlin
val engine = ReaderEngine()                       // a native reader; LoadFailed if the .so didn't load
engine.load(ReaderGraphs(encoder, decoder, decoderWithPast), ReaderOptions())  // blocks, cancellable
val dims: ReaderDims = engine.dims()              // layers, heads, head size, for the load log line
val reading: Reading = engine.translate(sourceIds, beam = 4, maxNewTokens = 0, minTokens = 1) // one segment
engine.cancel()                                   // any thread, any time; returns at once
engine.release()                                  // cancels, waits for the running call, frees; off the main thread

val tokenizer = ReaderTokenizer()                 // SentencePiece alone (the reading, the V0b selftest)
tokenizer.load(modelSrc)
val pieces: List<String> = tokenizer.pieces(textAfterTheTwoTags)
tokenizer.release()
```

- `sourceIds`: the source as the model takes it, the two language tags first (13 and 4 for
  `mal_Mlym eng_Latn`) and `</s>` (2) last, its only `</s>`; a source without it last, or with it
  anywhere else (two segments' ids joined as they are), is `BadInput`. At most `maxSourcePositions`
  (256) ids: `ReaderEngine.MAX_SOURCE_PIECES` (253) pieces, so the Reading step splits a longer
  segment at a word boundary first (D15).
- `Reading`: `ids` without the closing end-of-sentence, `meanLogProb` (score per token, the
  end-of-sentence counted; D5's floor), `capped` (the token cap reached, or no end-of-sentence),
  `steps` (decoder runs). `ids` is empty only with `maxNewTokens = 1`, or with `meanLogProb`
  negative infinity when the model allows nothing but the end at the first step.
- The output is capped at `ReaderEngine.maxNewTokens(pieces)` = min(2 x pieces + 10, 255) tokens,
  the end-of-sentence included; at the last allowed token only end-of-sentence is left. A
  `maxNewTokens` > 0 lowers the cap (a debug worst-case run, V1, uses the default).
- `minTokens` is the search's minimum length, fairseq's 1 (`ReaderEngine.DEFAULT_MIN_TOKENS`) for
  every reading: no end-of-sentence before that many tokens, except at the cap's last token. The
  debug worst case (V1: a 253-piece source, beam 4) gives 255, so the end is held back to the cap's
  last token and the search runs all 255 steps. Negative is `BadInput`.
- `ReaderOptions` defaults are the design's: 4 threads, prepacking on for the encoder and
  `decoder_with_past` and off for `decoder_model`, CPU arena on and shrunk after each segment's
  encoder and first step, no memory pattern. V1's arms are `prepackDecoder = true` (all three
  prepacked), all three `false`, `cpuArena = false`, `threads = 2`.
- Failures are `ReaderException`s, one subclass per native status: `Cancelled`, `BadInput`,
  `BadModel`, `LoadFailed`, `RunFailed`, `OutOfMemory`, `NotLoaded`, `Busy`, `Internal`. Their
  messages are ONNX Runtime's or SentencePiece's (names, shapes, paths), never segment text.
- Threads: `load` and `translate` block, so they run off the main thread. Calls on one engine wait
  for each other (`ReaderEngine` serializes them; `dims()` called during a `translate` waits for
  it), so `Busy` never comes through `ReaderEngine` or `ReaderTokenizer`, only from `ReaderNative`
  called directly. `cancel` stops a running Run at its next node (11-17 ms on the Mac's toy test)
  and a load at ONNX Runtime's next check, and every later call throws `Cancelled`: release and
  make a new engine (D14). `NativeHeap.purge()` (lib-llama-android) belongs after `release`.
- `release` waits for the running call to see the cancel. That is milliseconds for a Run, but a
  load stopped while ONNX Runtime plans a graph's memory finishes the planning first (below), so
  `release` must never run on the main thread.
- A library that didn't load (an ONNX Runtime without `OrtGetApiBase@VERS_1.28.2`, say) is
  `LoadFailed` from every `ReaderEngine()` and `ReaderTokenizer()`, never an `Error`.

### Load cancellation

ONNX Runtime 1.28.2 reads the load-cancellation flag per initializer, between optimizer passes and
per node while prepacking (`session_state_utils.cc:379`, `graph_transformer_mgr.cc:34`,
`session_state.cc:476`), and once after planning the graph's memory (`session_state.cc:1655`), but
not during that planning. On a toy encoder of 5,600 light nodes (a 4.7 s load under ASan) a cancel
at the start returned in 9 ms, 10 % in after 0.2 s, 90 % in after 0.6-0.7 s, and one landed in the
planning (50 % in) after 2.4 s (2.4-3.2 s in the review's runs). The real graphs have far fewer,
heavier nodes; V6's "once during the load" measures it on the phone. The host test holds the early
points (0 % and 10 %) to stopping in under half the load still to come, which a cancel without the
flag (one that waits for the session to finish) can't meet.

## JNI (`ReaderNative`, `reader_jni.cpp`)

| Kotlin | Native |
|---|---|
| `create(): Long` | `new Reader()`, 0 without memory |
| `load(handle, encoder: ByteArray, decoder: ByteArray, decoderWithPast: ByteArray, options: IntArray): Int` | `Reader::Load`; paths UTF-8; options as `ReaderOptions.toIntArray()` (12 ints) |
| `loadTokenizer(handle, model: ByteArray): Int` | `Reader::LoadTokenizer` |
| `pieces(handle, text: ByteArray, status: IntArray): Array<ByteArray>?` | `Reader::Pieces`; pieces as UTF-8 |
| `translate(handle, sourceIds: IntArray, beam: Int, maxNewTokens: Int, minTokens: Int, outIds: IntArray, outCounts: IntArray, outScore: DoubleArray): Int` | `Reader::Translate`; counts = ids, capped, decoder runs |
| `dims(handle): IntArray?` | layers, heads, head size |
| `cancel(handle)` | `Reader::Cancel`, any thread |
| `lastError(handle): ByteArray?` | the last failure's message, UTF-8 |
| `release(handle)` | `delete` |

All are `@JvmStatic` on the `internal object ReaderNative`; the `Int` results are the statuses of
`reader_status.h` (0 ok, 1 cancelled, 2 bad input, 3 bad model, 4 load failed, 5 run failed, 6 out
of memory, 7 not loaded, 8 busy, 9 internal), `ReaderStatus` in Kotlin. Text never crosses as a
`String`: JNI's modified UTF-8 can't carry a character outside the BMP, and CheckJNI aborts on one.

## Decoding

- The encoder once; `decoder_model` once with the start token (2) at batch 1, prepacking off; then
  `decoder_with_past` per token with the active beams as its batch. The first step's
  `present.N.encoder.*` is copied to every beam slot and fed to every step.
- Every output of `decoder_with_past` goes into the reader's own buffers: `logits`,
  `present.N.decoder.*` into the self-attention cache, and `present.N.encoder.*` into the very
  cross-attention buffer fed as `past_key_values.N.encoder.*`. The graph passes the cross cache
  through unchanged (an Identity in the toy graphs; the export's wrapper returns the input
  tensors), and ONNX Runtime's copy skips a target that is its own source. Left unfetched, ONNX
  Runtime still ran it, into its arena: a copy of the whole cross cache per step, 36 x 2 MiB on
  IndicTrans2 at beam 4 and 256 ids. Bound, the step's arena holds only small intermediates (on
  the toy: largest block 16 KiB, against 196,608 bytes unbound), so it isn't shrunk between steps.
- Beam search as fairseq's SequenceGenerator with its defaults (the decoder of the paper's numbers):
  log-softmax in double, the best 2 x beam candidates by score (ties to the lower beam x vocabulary
  + token), an end-of-sentence among the first `beam` finishes its hypothesis, the first `beam`
  other tokens go on, the search ends with `beam` finished; the result is the best score per token.
  Padding (1) is never generated. min_len 1: end-of-sentence is no first token (fairseq doesn't
  return empty readings), except when the cap allows one token only; a call may ask for a longer
  minimum (`minTokens`, the debug worst case). Beam 1 is greedy.
- The self-attention cache: two buffers per segment, the present written by the Run into one and
  gathered by parent into the other; unchanged beams swap them instead.
- Layer count, heads and head size come from the sessions (`decoder_with_past`'s past inputs, else
  the first step's outputs); every tensor a Run returns is checked before it is read.

## Host tests (macOS)

`src/test/run_host_tests.sh <ort dir> <python> [<work dir>]`, with `<ort dir>` the unpacked
`onnxruntime-osx-arm64-1.28.2.tgz` of ONNX Runtime's v1.28.2 GitHub release and `<python>` a
throwaway venv with numpy, onnx, onnxruntime and sentencepiece==0.2.1 from pypi. With `GRADLE` set
(e.g. `./gradlew`), it also runs the JVM test through the JNI.

- `python/toy_models.py`: toy graphs with random weights and the export's names, dynamic axes,
  int8 quantization and file layout (two decoders on one `decoder_shared.onnx.data`), plus variants
  the reader must refuse, one whose `decoder_model` makes the end the best first token by far
  (`eosfirst`, for fairseq's minimum length), one whose every step does (`eosalways`, where a
  minimum of 1, 3 or 7 gives exactly that many tokens) and two slow encoders for cancellation.
- `python/toy_reference.py`: the same beam search in Python over onnxruntime, the reference.
- `python/toy_spm.py`: a toy SentencePiece model trained on synthetic text, and the wheel's pieces.
- `cpp/reader_host_test.cpp`, under ASan and UBSan: every reference case identical (tokens, mean to
  the bit, capped, decoder runs) at beams 1 to 8, short sources, the 256-id source and the 255-token
  output, and the minimum lengths a call asks for (the worst case: the 256-id source, the end held
  back to the cap, 255 decoder runs); the minimum length; random inputs; `decoder_with_past`'s arena (no block the size of a
  cross-cache copy, read through ONNX Runtime's allocator statistics); bad input, the source's
  framing, paths and shapes; cancel before and during a load and during a Run; the toy tokenizer's
  pieces.
- `java/.../ReaderEngineHostTest.kt`: the same through `ReaderEngine` and the JNI built for macOS;
  skipped in an ordinary Gradle run. `ReaderStatusTest.kt` runs always, and so does
  `ReaderLibraryMissingTest.kt` (a library that didn't load is `LoadFailed`), which is skipped when
  the host library is on the path.

The tokenizer with the real `model.SRC` is checked on the phone (V0b), as the file stays off the
Mac (O12).
