# Development setup

Satori is a recorder and transcriber with on-device AI that works with the phone in airplane
mode. Target device: Motorola razr 70 plus.

Set up and verified on 2026-10-08 on an Apple Silicon Mac (macOS 26.7): `./gradlew assembleDebug`
succeeds and the debug APK installs and launches on the razr with its network blocked.

## Toolchain

| Component | Version | Location | Installed with |
|---|---|---|---|
| JDK | 17.0.20.1 (Homebrew `openjdk@17`) | `/opt/homebrew/opt/openjdk@17` | `brew install openjdk@17` |
| Gradle | 8.6 (wrapper) | `~/.gradle/wrapper/dists/gradle-8.6-bin` | `./gradlew` |
| Android SDK platform | 35 | `~/Library/Android/sdk/platforms/android-35` | `sdkmanager` |
| Build-tools | 34.0.0 (AGP 8.4 default) | `~/Library/Android/sdk/build-tools/34.0.0` | `sdkmanager` |
| NDK | 29.0.13113456 (r29-beta1) | `~/Library/Android/sdk/ndk/29.0.13113456` | manual, see below |
| CMake | 3.31.6 | `~/Library/Android/sdk/cmake/3.31.6` | `sdkmanager` |
| Platform-tools | 37.0.1 | `~/Library/Android/sdk/platform-tools` | auto-installed by AGP |
| Command-line tools | 23.0 | `~/Library/Android/sdk/cmdline-tools/latest` | `sdkmanager` (bootstrapped by `brew install --cask android-commandlinetools`) |
| Git LFS | 3.8.0 | Homebrew | `brew install git-lfs`, then `git lfs install` |
| adb | 37.0.1 | `/opt/homebrew/bin/adb` | `brew install --cask android-platform-tools` |

The JDK 27 that Homebrew also has (`openjdk`) is too new for Gradle 8.6, so stay on 17.

`~/.zshrc` sets:

```sh
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
```

`local.properties` (gitignored) holds `sdk.dir=/Users/<you>/Library/Android/sdk`.

### NDK install note

`sdkmanager` failed twice on the 1 GB NDK archive with `Connection reset` on this network. A
resumable download works:

```sh
mkdir -p /tmp/ndk-dl && cd /tmp/ndk-dl
URL=https://dl.google.com/android/repository/android-ndk-r29-beta1-darwin.zip
for i in $(seq 1 10); do curl -sS -L --fail -C - -o ndk.zip "$URL" && break; sleep 3; done
shasum -a 1 ndk.zip   # expect 485c42ff6a04ef0ec9612420b3226de443f46e7a (repository2-3.xml)
unzip -q ndk.zip && mv android-ndk-r29-beta1 "$ANDROID_HOME/ndk/29.0.13113456"
```

## Repository layout

| Item | State |
|---|---|
| `third_party/llama.cpp` | Git submodule pinned to tag `b9574` (`961e9a3`), shallow. `LLAMA_SRC` in `lib-llama-android/src/main/cpp/CMakeLists.txt` points at the submodule and can be overridden with `-DLLAMA_SRC=...`. |
| `lib-llama-android/patches` | Satori's changes to the pinned llama.cpp, `*.patch` in name order. The native build's CMake configure applies each to `third_party/llama.cpp` once (one already in is left as it is) and stops on one that neither applies nor is in; they are never committed to the submodule, whose pinned commit stays, so `git status` shows it as `m` (modified content) after a build. `git submodule update --force` or a checkout of the submodule's files undoes them; the next build applies them again. |
| llama.cpp's libraries | Built from `third_party/llama.cpp` by `lib-llama-android`'s CMake; none is prebuilt. They hold no absolute path of the checkout that built them: the CMake maps the checkout, its build folder, `LLAMA_SRC` and the NDK to relative paths (`-ffile-prefix-map`, `-fmacro-prefix-map`), and builds ggml's two sources with OpenMP regions without debug info, as their OpenMP source locations would keep the absolute path. |
| `lib-reader-onnx` | The reader's native module (0.8.0): IndicTrans2's three ONNX graphs on sherpa-onnx's ONNX Runtime 1.28.2 through its C API, whose headers are pinned in `third_party/onnxruntime-headers-1.28.2/` (sizes and sha256 in its README), the beam search and its caches, and SentencePiece built static from the `third_party/sentencepiece` submodule (v0.2.1, `31646a4`) into `libreader-onnx.so`. `ReaderEngine` (load, translate, cancel, release) and `ReaderTokenizer` are its Kotlin API, `lib-reader-onnx/README.md` the whole of it. Its host tests run on the Mac, not in a Gradle run: `lib-reader-onnx/src/test/run_host_tests.sh <unpacked onnxruntime-osx-arm64-1.28.2> <venv python with numpy, onnx, onnxruntime and sentencepiece==0.2.1> [<work dir>]` builds the native core under ASan and UBSan against toy graphs with the export's names and a Python reference of the same beam search, and with `GRADLE` set runs `ReaderEngineHostTest` through the JNI built for macOS. Re-run them after any change to its C++. |
| `lib-reader-onnx/patches` | Satori's changes to the pinned SentencePiece (`third_party/sentencepiece`, v0.2.1), applied by `lib-reader-onnx`'s CMake configure the same way, so the submodule shows as `m` after a build too. `0001` quotes `-fmacro-prefix-map=<source folder>/=''`, a flag SentencePiece gives the NDK's clang without quotes: in a checkout whose path has a space or a parenthesis, the shell stopped at it and none of SentencePiece's sources compiled. Quoted, it reaches the compiler as the one argument it is in a path without them: the `libreader-onnx.so` built in this checkout is byte for byte the one built without the patch in a `/tmp` checkout. |
| `.github/workflows/build.yml` | CI: checks out the repository with its LFS objects and the pinned llama.cpp submodule, and builds the debug APK. |
| Git LFS objects | sherpa-onnx 1.13.8 prebuilt libs in `lib-sherpa-onnx/src/main/jniLibs` (only `libonnxruntime.so`, ONNX Runtime 1.28.2, and `libsherpa-onnx-jni.so`; the never-loaded C and C++ API libs and the copy in `app/src/main/jniLibs` were removed on 2026-10-10) and `app/src/main/assets/silero_vad.onnx`. |
| `lib-sherpa-onnx` update | Take `jniLibs/arm64-v8a/libsherpa-onnx-jni.so` and `libonnxruntime.so` from the release's `sherpa-onnx-v<version>-android.tar.bz2` (check its sha256 against the release's digest) and the Kotlin files from `sherpa-onnx/kotlin-api/` at the same tag: the JNI library reads every field of the config classes by name, so the two must match. `strings libsherpa-onnx-jni.so` shows the version and the tag's commit, `strings libonnxruntime.so` the ONNX Runtime version. Satori's only edit is the shorter `getOfflineModelConfig` table; the field log's `Speech summary:` lines name the version that ran. |
| `feature-reader` | The reader's data source (`ReaderDataSource`): its files' check, its load at a speech model's pace for a file (the one-load gate, the pre-read, the room for the packed weights of the arm's prepacked graphs) and its release with the native heap purged, never beside a capture, the live speech model or an AI model, and one line's reading (`LineReader`). `ReaderArm` holds V1's session settings, `ReaderLimits` the model's from its `config.json`. |
| `domain/src/test/resources/reading` | Golden data of the reader's text pipeline (`com.satori.domain.reading`, the Kotlin port of IndicTrans2's preprocessing, vocabularies and English detokenizer), written by `~/satori-audit/tools/reading/golden.py` (outside the repo) from the Python reference: IndicTransToolkit 1.1.1's IndicProcessor over indic-nlp-library 0.92, sacremoses 0.2.0. It holds synthetic sentences, edge cases, id lists, the export's 316 outputs and the subsets of the two pinned vocabularies they need. FLORES-200 and the full vocabularies stay outside: `READING_GOLDEN=<golden.py's output folder> READING_DICTS=<folder with the pinned dict.SRC.json and dict.TGT.json> ./gradlew :domain:test --tests '*OutsideGoldenTest' --rerun` checks them, printing counts only. |
| Analytics and crash reporting | None. |
| App identity | Satori: application ID `com.satori`, Kotlin packages `com.satori.*`, database file `satori.db` (since 2026-10-15). |

## Build

```sh
git submodule update --init      # llama.cpp b9574, SentencePiece v0.2.1
git lfs pull                     # sherpa-onnx libs + Silero VAD
./gradlew lintDebug assembleDebug   # lint must pass before an install
```

The first build takes about 4 minutes, most of it the native llama.cpp build with seven CPU
variants (armv8.0 to armv9.2). The output is `app/build/outputs/apk/debug/app-debug.apk`, about
55 MB. The app pins the NDK version, so its native libs are stripped even in debug builds (they
were 135.6 MB unstripped before 2026-10-10). minSdk is 31 for every module (`gradle/libs.versions.toml`).

Verified in the APK: every `NEEDED` native dependency is packaged (`libomp.so` included), and every
`.so` has 16 KB LOAD alignment (`0x4000`). The razr runs 4 KB pages today, but it supports 16 KB
(`ro.product.cpu.pagesize.max=16384`).

## The release check

Before an APK goes to the phone, in this order:

1. The JVM suites, lint and the native build, in a checkout with both submodules (llama.cpp and
   SentencePiece):
   ```sh
   ./gradlew :domain:test :data:testDebugUnitTest :presentation:testDebugUnitTest :feature-stt:testDebugUnitTest \
     :app:testDebugUnitTest :lib-llama-android:testDebugUnitTest :feature-llm:testDebugUnitTest \
     :lib-reader-onnx:testDebugUnitTest :feature-reader:testDebugUnitTest :app:compileDebugKotlin :app:lintDebug assembleDebug
   ```
2. Since 0.7.4, the app's regular expressions on Android's regex engine, ICU, on an emulator. The
   unit tests run the JVM's java.util.regex, which takes patterns ICU rejects: 0.7.3's
   `\{[a-z_]+}` (a bare `}`) passed all 3,640 of them and stopped every AI call's checks on the
   phone. The debug APK and the test APK, built with or without the native code (a checkout
   without the submodules adds the two `-x ':lib-llama-android:...CMakeRelease[arm64-v8a]'` flags
   and lib-reader-onnx's two, `-x ':lib-reader-onnx:configureCMakeRelease[arm64-v8a]'` and
   `-x ':lib-reader-onnx:buildCMakeRelease[arm64-v8a][reader-onnx]'`), go on an emulator
   reached through an adb server of its own, never the phone's: the install replaces the app, and
   the test package would stay on the device. Any emulator of the android-35 google_apis
   arm64-v8a image will do; `S` is its serial, `emulator-<console port>` (`adb -s $S emu avd name`
   says which AVD it is).
   ```sh
   ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
   export ANDROID_ADB_SERVER_PORT=5038 ADB_MDNS=0 ADB_MDNS_AUTO_CONNECT=0  # the emulator's own adb server, which never finds the phone
   S=emulator-5820
   adb -s $S uninstall com.satori.test; adb -s $S uninstall com.satori     # a fresh install, as on a new emulator
   adb -s $S install -r -t app/build/outputs/apk/debug/app-debug.apk
   adb -s $S install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
   adb -s $S shell am instrument -w -r com.satori.test/com.satori.regex.PlainApplicationRunner
   ```
   It must end with `OK (<n> tests)` (27 in 0.7.4), also on that first run after the install, in
   which Android 15 sends the app BOOT_COMPLETED (`PlainApplicationRunner` says what it does with
   it). Not `connectedDebugAndroidTest`, which installs on every device its adb server sees.
   `IcuRegexTest` starts every class of the app's code that keeps a pattern in a static field
   (logcat tag `IcuRegexTest` lists each one by class and field, 170 in 0.7.4), reads the shipped
   prompts' rules from the compiled resources, and checks that the engine is ICU; `RegexRunsTest`
   (`app/src/sharedTest`) runs each class with patterns once, on the JVM in `:app:testDebugUnitTest`
   and on ICU here, with the same answers. The scan reaches static fields only, so a new pattern
   built in a function or kept in an instance field needs a run of its own in `RegexRunsTest`.

   The emulator's engine is Android 15's, ICU 75.1 with Unicode 15.1 (the only image on this Mac,
   2026-10-19). The phone runs Android 16, whose ICU is a later one, not checked here. Their regex
   syntax is expected to be the same, so the emulator stands in for the phone's engine closely,
   but it is not that engine: once an android-36 google_apis arm64-v8a image is on this Mac, run
   this step on it as well.
3. `rebrand.py --check` on the repo and the APK, and `strings` on every `.so` of the APK built from
   a clean checkout: no `/Users/` path.

### Regular expressions on Android

Android's `java.util.regex` is ICU underneath; the unit tests' is the JVM's. Found in 0.7.4's audit,
each checked on the emulator:
- ICU rejects a `}` that closes no quantifier (escape it, `\}`), the flag `(?U)`, and a lookbehind
  without a bounded length (`(?<=a+)`), all of which the JVM takes. A lone `]` outside a class,
  `[\s-]`, `\Q...\E`, `(?m)`, `(?<name>...)`, `&&` in a class and possessive quantifiers read the
  same on both.
- ICU reads `\w`, `\W`, `\b` and `\d` by Unicode, the JVM by ASCII (`\b` by letters and digits):
  on the JVM `^\W*` takes the "Ü" of "Übersicht" and the vowel signs of a Malayalam word. Write
  `\p{L}`, `\p{M}`, `\p{N}` classes, or `WordClasses.NOT_A_WORD_CHAR` for ICU's `\W`.
- ICU's `\s` is every Unicode space (no-break spaces too), `\p{Punct}`, `\p{Alpha}` and
  `\p{Cntrl}` are Unicode's classes, not ASCII's, and its `.` stops at a vertical tab and a form
  feed as well. So are `\p{Lower}`, `\p{Upper}`, `\p{Digit}`, `\p{Space}` and `\p{Alnum}`:
  `\p{Digit}` takes a Malayalam digit, `\p{Lower}` an "ä". ICU's `\p{Punct}` also leaves out `$`
  and `+`, which the JVM's has.
- With IGNORE_CASE ICU folds fully: "strasse" matches "Straße", "dass" matches "daß". Kotlin's
  `RegexOption.IGNORE_CASE` reads case by Unicode on the JVM too, but the embedded `(?i)` and
  `Pattern.CASE_INSENSITIVE` without `UNICODE_CASE` read it by ASCII there and by Unicode on ICU.
- A class that opens with `[:` is ICU's `[:name:]` property form whenever the first `:` after it
  stands right before a `]`: `[:=]\s*[^:]` and `[:;]|a:]` fail to compile on ICU ("Incorrect
  Unicode property") and compile on the JVM. Put the `:` later in the class (`[=:]`). The app's
  `[:=]`, `[:,\-–]` and `[:\-–]` are safe only because no `:]` follows them.
- `--` in a class is ICU's set difference: `[a-z--c]` is a, b and d to z on ICU, and a to z and
  `-` on the JVM. Escape the dash (`\-`).
- Android throws IllegalArgumentException for `Pattern.CANON_EQ` (Kotlin's
  `RegexOption.CANON_EQ`) and `Pattern.UNICODE_CHARACTER_CLASS`, which the JVM takes.

The differences above were run on the emulator and on JDK 17 with the same made-up inputs.

## Run on the razr 70 plus

```sh
adb devices -l                                   # wireless ADB, see the razr helper (~/.local/bin/razr)
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p com.satori -c android.intent.category.LAUNCHER 1
adb logcat | grep -i -E "satori|SherpaOnnx|LlamaAndroid|SyncSttLlm|AndroidRuntime"
```

- The app is called Satori, with application ID `com.satori` and Kotlin packages `com.satori.*`.
- When the phone is folded, Motorola asks once whether the app may open on the external (cover)
  display. Changes to the phone are logged in `~/.config/razr/changes.log`.
- Plain `adb exec-out screencap` output is corrupted on this phone. Use
  `RAZR_DISPLAY=1 ~/.local/bin/razr shot <file>` for the cover screen (display 1).
- The debug tools (`DebugToolsActivity`, debug builds) have a task of their own since 0.8.0-dev2,
  kept out of Recents, so Satori from the launcher or the quick key never shows a debug screen.
  The long ones (memtest, prewarm, rerun, reader-download and the two selftests) send their
  screen to the back once the run has started and the AI's service is in the foreground
  (`<action> runs with its screen in the back` under `DebugTools`); Android then ends the screen,
  its task being out of Recents, and the run goes on in the app's process: unlocking the phone
  mid-run shows nothing of them. Without the service in the foreground 5 s after the start the
  screen stays in front, as in 0.8.0-dev1, since in the back the service is all that keeps the
  process from being frozen (`W <action> keeps its screen in front: the AI service is not in the
  foreground`). Wait for a run's last line before the next `am start`; one sent
  while a run goes on starts that tool anew, and it refuses while the AI is held (a second reader
  download refuses too). `--es front true` keeps the screen in front for an emulator or a run
  someone watches, and Back there cancels the run (`docs/FIELD_LOG.md`).

## Check the cover's layouts on an emulator

The cover in Motorola's full-screen mode is 1080x1272 px at 306 dpi, with the cameras 495x349 px
at the bottom right upright and at the top left upside down (`ui/design/GrilleCameraBand.kt`). An
emulator has no such cutout, so a debug build draws the razr's cameras in when asked:

```sh
adb shell wm size 1080x1272; adb shell wm density 306
adb shell setprop debug.satori.cover.band 495x349      # width x height in px; empty turns it off
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 2          # 0 upright, 2 upside down, 1 and 3 on a side
```

The property is read as a screen is drawn: force-stop Satori after setting it. It is ignored on a
display that has a band of its own, and in release builds. Set it back with
`adb shell setprop debug.satori.cover.band ""`, and the size, density and rotation with `wm size
reset`, `wm density reset` and `user_rotation 0`. The emulator still has a status bar, which the
cover doesn't: upside down it takes the top of the band, so the grille has a little less room there
than on the phone.

Keep the launcher out of a check upside down: showing it (the HOME key, or `monkey` starting an
app after it) puts `user_rotation` back to 0 and turns auto-rotate on again, and while it is in
front a `user_rotation 2` doesn't take, so a batch meant upside down silently runs upright. Start
Satori with `adb shell am start -n com.satori/com.satori.ui.MainActivity`,
then set the rotation, and read `settings get system user_rotation` and
`dumpsys window displays | grep mDisplayRotation` before each screenshot.

## Test airplane mode without losing wireless ADB

Real airplane mode turns Wi-Fi off and drops wireless ADB. During development, firewall only the
app:

```sh
adb shell cmd connectivity set-chain3-enabled true
adb shell cmd connectivity set-package-networking-enabled false com.satori
adb shell cmd connectivity get-package-networking-enabled com.satori   # -> deny
# undo
adb shell cmd connectivity set-package-networking-enabled true com.satori
adb shell cmd connectivity set-chain3-enabled false
```

It was set for the earlier application ID on 2026-10-09. The rule belongs to the app's UID, so it
survives `adb install -r` but not an uninstall, and `com.satori` needs it set again. For final
acceptance, run a real airplane-mode test, either over USB ADB or by hand.

## Put models on the phone without the app going online

Verified on 2026-10-08 with the app firewalled.

- The phone has `/system/bin/curl`, so model files are fetched by the phone over its own network.
- They are stored in `/sdcard/Download/satori-models/` and never pass through the Mac.

```sh
# 1. fetch on the phone (runs in the background on the device; survives an adb disconnect)
adb shell 'D=/sdcard/Download/satori-models/parakeet-tdt-0.6b-v3-int8; mkdir -p $D && cd $D && cat > dl.sh <<EOF
B=https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/2bda32ec70b097a55adaa07d9a7173915b43cc78
for f in tokens.txt joiner.int8.onnx decoder.int8.onnx encoder.int8.onnx; do
  for i in 1 2 3 4 5 6; do curl -sSL --fail -C - --retry 3 -o \$f "\$B/\$f" && break; sleep 3; done
done
echo DONE > done.flag
EOF
nohup sh dl.sh > dl.log 2>&1 &'

# 2. copy into the app's model folder. Launch a fresh install once first: the app then creates
#    files/models/stt itself, and shell can write into it through the ext_data_rw group (chmod on
#    that folder fails and isn't needed). Folders that shell creates instead are 2770
#    shell:ext_data_rw, which the app cannot enter, so those need chmod 0777.
adb shell 'D=/sdcard/Download/satori-models/parakeet-tdt-0.6b-v3-int8
A=/sdcard/Android/data/com.satori/files
[ -d $A/models/stt ] || { mkdir -p $A/models/stt && chmod 0777 $A/models $A/models/stt; }
for f in encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt; do cp $D/$f $A/models/stt/; done
chmod 0666 $A/models/stt/*'
```

The AI model (Gemma 3 1B, Q8_0) goes the same way. Fetch it on the phone into
`/sdcard/Download/satori-models/gemma-3-1b-it-Q8_0/` from
`https://huggingface.co/ggml-org/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q8_0.gguf`, then copy
`gemma-3-1b-it-Q8_0.gguf` (1,069,306,368 bytes) to `$A/models/` and `chmod 0666` it. Satori finds
it at the next recording and shows "AI ready".

Expected sizes:

| File | Bytes |
|---|---|
| `encoder.int8.onnx` | 652,184,281 |
| `decoder.int8.onnx` | 11,845,275 |
| `joiner.int8.onnx` | 6,355,277 |
| `tokens.txt` | 93,939 |

Onboarding's "Download model" then finishes at once: the log says
`Speech download parakeet-tdt-0.6b-v3-int8: 0 of 4 files to fetch`.

The recorder checks these sizes, and the first bytes of each file, before it loads a model
(`ModelFileInspector`; the sizes are in `ModelConfig.kt`). A file of another size, such as the
11-byte placeholders the debug `setup` action writes on an emulator, is reported as "The speech
model is damaged or incomplete" instead of reaching the native loader, which would abort the
process. The Gemma 3 1B Q8_0 file is held to the size above; the other two AI models only have to be
a GGUF file of at least 100 MB until their exact sizes are read off a downloaded copy. Each speech
model is downloaded from the commit whose sizes these are (the registry `SpeechModels` in
`ModelConfig.kt`), so an update of the model's repository changes nothing; to move to a newer
revision, change the commit and the numbers together. The app's own downloads also check each
file's SHA-256 before it takes its name; a copy put there by hand is checked by size and first
bytes only, so check it with `sha256sum` against `SpeechModels` first.

### The reader (0.8.0)

The reader's files are a model of their own, downloaded on the phone, never on this Mac: each
build in `files/models/reader/<id>`, the int8 build (`indictrans2-indic-en-dist-200m-int8`, 11
files, 241,989,173 bytes, from `hari31416/indictrans2-indic-en-dist-200M-ONNX-int8` at
`a7ecdc0ba73148c6e1d051ca372332471d8aff4f`) and the fp16 fallback (`...-fp16`, 466,493,792 bytes,
`hari31416/indictrans2-indic-en-dist-200M-ONNX-fp16` at `b6169410862d56cdace9a5e26298b1243c7c1265`);
each file's size and SHA-256 are in `ReaderModels` (`ModelConfig.kt`). Until the first run plans its
download (0.8.0's stage B2), a debug build fetches it with
`adb shell am start -n com.satori/com.satori.ui.debug.DebugToolsActivity --es action reader-download`
(`--es variant fp16` for the fallback, `--es mobile true` to allow mobile data), which also checks
every file's SHA-256 against its pin (`docs/FIELD_LOG.md`, `The reader's debug tools`).

The phone checks V0b, V1 and V2 run as debug selftests over public text pushed into
`files/debug/reader`, the files of `~/satori-audit/tools/reading/push/` (`make_push.py` writes them
from FLORES-200 and golden.py's synthetic lines). The app makes that folder the first time a
selftest runs: run one before the first push (it then says where to push its input), as a folder
adb makes is one the app can't enter.

```sh
D=/sdcard/Android/data/com.satori/files/debug/reader
adb shell am start -n com.satori/com.satori.ui.debug.DebugToolsActivity --es action tokenizer-selftest   # makes $D
adb push ~/satori-audit/tools/reading/push/v0b_inputs.tsv ~/satori-audit/tools/reading/push/flores200_devtest.mal.txt $D/
adb shell am start -n com.satori/com.satori.ui.debug.DebugToolsActivity --es action tokenizer-selftest   # V0b
adb pull $D/v0b-int8.tsv && python3 -I ~/satori-audit/tools/reading/v0b_compare.py v0b-int8.tsv
sh ~/satori-audit/tools/memtest.sh today reader            # V1, V2; it pulls the run's readings into its folder (files:)
python3 -I ~/satori-audit/tools/reading/score.py <that folder>/readings-int8-prepack-session-arena-on-threads-4-beam4-cold.tsv   # with sacrebleu
```

Each reader selftest's output is named by every setting a run could differ in, so no run
overwrites another's, but a run of the same settings overwrites its own: pull it (or let
memtest.sh, which does) before the next. `v0b_compare.py` wants the push file's `input` column, so
the phone's preprocessing is held to the reference's on every line; `score.py` reads FLORES'
devtest or the smoke lines only, unless `--refs` names the input's own references.

V1's stop timing needs no screen and no key since 0.8.0-dev2: the reader selftest makes the cancel
a recording's start makes itself, `stopafter` ms into its reading or `stopload` ms into the
creation of its sessions (after the pre-read, SentencePiece's model and the vocabularies' parse,
which no cancel cuts short), and ends with `Reader selftest cancelled: stopafter <ms>, the cancel
came inside a Run|between Runs; ...` (or `stopload <ms>, ... inside the load|after the load; ...`),
with the data source's `Run stopped <x> ms after the cancel` or `load stopped <x> ms after the
cancel` before it (V1's bar for a Run: 100 ms). A stopload longer than the sessions' creation
lands after the load. It writes no readings file. Run it as any reader selftest, the phone locked
and dark:

```sh
adb shell am start -n com.satori/com.satori.ui.debug.DebugToolsActivity --es action reader-selftest --es cold true --ei stopafter 2000
adb shell am start -n com.satori/com.satori.ui.debug.DebugToolsActivity --es action reader-selftest --es cold true --ei stopload 300
adb logcat -d -s DebugTools SatoriReader | grep -E 'Run stopped|load stopped|Reader selftest cancelled'
```

On an emulator, `~/satori-audit/tools/reading/toy_reader.py` makes a folder of a toy model from the
host tests' toy graphs and toy SentencePiece model, pushed as `$D/toy` (then `adb shell chmod 0777
$D/toy`, as adb made that folder); either selftest then reads it with `--es dir toy` instead of the
build, the whole native path without the model (`--es front true` keeps its screen in front, where
Back cancels it). Its reader-download is tried against
`~/satori-audit/tools/throttle_server.py --with-reader` (synthetic files of the registry's sizes,
after `--es action firstrun-source --es url http://10.0.2.2:<port>/`), never against the registry:
the emulator's network is this Mac's.

### The other speech models

Parakeet v2 (English) and Cohere Transcribe (English and German) make a better transcript after
Stop and with Transcribe again; Advanced settings downloads them on the phone. They decode the
whole recording in one language, so they are offered and run only for a recording whose transcript
is in that language throughout (`SpokenLanguage`); a mixed one stays with Parakeet v3. After Stop
the model runs only when its estimate, from the razr's own times (`SpeechModelChoice`), is at most
three minutes (`TranscribeAfterStopUseCase.MAX_ESTIMATED_MS`): Cohere on recordings of up to about
8 minutes, v2 on up to about half an hour; Transcribe again takes any length. The estimate is the
time in Android's foreground scheduling group (cores 0-6: the screen off under a foreground
service), the slowest one the app's runs are in while a service of the app runs; with Satori in
front a run takes about half of it. In the background group (cores 0-2, no service) a run takes 4
to 7 times as long as in front, about 2 to 4.5 times the estimate. The better transcript after
Stop runs there when only the finisher's wake job keeps the app (after a reboot), so then it goes
by the background group's own times: Cohere on up to about a minute and a half, v2 on up to about
5 minutes. Skip on the recording's page ends the better transcript after Stop. Each has its own
folder in `files/models/stt` (Parakeet v3 stays in `stt` itself):

| Model | Folder | Repository and commit | Bytes |
|---|---|---|---|
| Parakeet v2 | `stt/parakeet-tdt-0.6b-v2-int8` | `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8` at `1ab9323565ddb038682214b292f588070a538ce2` | 661,190,513 |
| Cohere Transcribe | `stt/cohere-transcribe-14-lang-int8` | `csukuangfj2/sherpa-onnx-cohere-transcribe-14-lang-int8-2026-04-01` at `156a470cf08eefe706a0004f3c52d9ee567ca7a0` | 2,888,052,036 |
| Malayalam (AI4Bharat) | `stt/indicconformer-ml-int8` | `csndhanasekar/sherpa-onnx-indicconformer-ml-int8` at `394e2c20b57eb0db12839ecff00f904a55ea9f47` | 137,566,803 |

Parakeet v2 has the same four file names as v3. Cohere's are `encoder.int8.onnx` (3,090,822),
`encoder.int8.onnx.data` (2,731,503,072, the encoder's weights, read from beside it),
`decoder.int8.onnx` (153,250,705) and `tokens.txt` (207,437). To fetch one on the phone by hand
instead, use the loop above with that repository, commit and file list, then copy the files into
the model's folder (created by the shell, so `chmod 0777` it as above).

The Malayalam model (AI4Bharat IndicConformer 120M, MIT, a community export of its CTC head) is for
the recordings made in Malayalam (0.6.0), and Advanced settings downloads it like the others. Its
files are `model.int8.onnx` (137,563,723, SHA-256
`357382eb9ad4ef45c9269042af953e627ec9ca66a396cad1a2f4ed8562c012a1`) and `tokens.txt` (3,080,
`60eb97740574a55504786d4ac0699b14280977ec346a7d9a9bbef4deeb1e559b`). sherpa-onnx loads it as a
NeMo CTC model and reads its subsampling factor and vocabulary size from the file's metadata, so
before every load the app checks that metadata (`model_type=EncDecCTCModelBPE`, `vocab_size=257`,
`subsampling_factor=4`, `normalize_type=per_feature`, as sherpa-onnx's own tool read it on the phone
on 2026-10-14) and that `tokens.txt` lists 257 tokens with the blank; a file that differs is refused
with a `ModelDownloadManager` warning instead of reaching the loader (`docs/FIELD_LOG.md`).

The words to recognize (Advanced settings) need `stt/bpe.vocab` (117,408 bytes, SHA-256
`41d5e71b3591642eff088151efd7acd4e750124cc0054c8ba9fa3245187a4804`) from
`MadnessOverflow/parakeet-tdt-0.6b-v3-bpe-vocab` at `0890cc43446a37ac7066237543074a7927b58464`, which
the app downloads with the first words. The words themselves are in the app's internal storage
(`files/speech/words.txt`), never in a log.

Without these steps, the app downloads the models it needs on the phone itself (`ModelDownloadService`).

## Sharing the phone with other sessions

The razr's cover screen is also driven by other terminal sessions (for example the KWGT cover-widget work).
- Before installing or sending input, check with the other session.
- Keep swipes in the middle of the cover screen. A swipe that starts low on the screen is taken as the home gesture.

## Network constraints on the development Mac

- Not TLS-intercepted, so builds work: `services.gradle.org`, `plugins.gradle.org`,
  `dl.google.com`, `repo.maven.apache.org`, `github.com`.
- If `huggingface.co` or its weight CDNs are blocked on the host network, model weights cannot be fetched on this
  Mac, and the build must not try to bundle them. Models are
  provisioned on the phone.

## Target device facts (read over ADB on 2026-10-08)

| Property | Value |
|---|---|
| Model | motorola razr 70 plus (`eiger`, product `eiger_ge`) |
| OS | Android 16 (API 36), build W3WSS36.55-9-3-5, security patch 2026-09-01 |
| SoC | Qualcomm SM8635 (Snapdragon 8s Gen 3, platform `pineapple`) |
| CPU | 1x Cortex-X4 3.01 GHz, 4x Cortex-A720 2.80 GHz, 3x Cortex-A520 2.02 GHz |
| RAM | 11.1 GiB usable (MemTotal 11,656,980 kB) |
| GPU | Adreno 735: OpenCL (`libOpenCL_adreno.so`), Vulkan |
| NPU | Hexagon HTP v73: `libQnnHtp.so`, `libQnnHtpV73Stub.so`, `libcdsprpc.so` in `/vendor/lib64` |
| ABI | arm64-v8a only |
| Displays | inner 1080x2640 (display 0), cover 1080x1272 with 923 px usable above the camera cutout (display 1) |
| Storage | 449 GB `/data`, 408 GB free |
