# Field log and silent test recordings

Two debug aids, added for the first day of field testing on the razr (version 0.2.0).

## Field log

Logcat keeps only the last few megabytes of the whole system, which covers a few hours at most. So
Satori keeps its own copy (`app/src/main/java/com/satori/diagnostics/FieldLog.kt`):

- A background thread reads Satori's own lines from logcat (an app may read its own lines without a
  permission) and appends them to `Android/data/com.satori/files/logs/satori-<start>-<pid>.log`.
  A new file is started for every process start and every 4 MB. The oldest files are deleted once
  there are more than 40 files or 80 MB.
- A crash is written to the file by an uncaught-exception handler before the process dies.
- At each start it logs why the previous processes ended, from Android's exit reasons (crash, ANR,
  low memory, a Motorola kill such as `[KILL UID] SleepMode`, an update), once each. It copies an
  ANR trace or a native tombstone into the same folder (`satori-anr-*.trace`, `satori-tombstone-*.trace`).
- Every minute a `SatoriVitals` line gives memory (PSS, Java, native, free system memory), battery
  level, current and temperature, charging, thermal status and battery saver.

The log lines hold no transcript text: the app logs only sizes, counts, timings and states. Check
that before adding a log line, because these files leave the phone. A copied tombstone is the
exception: it holds some bytes of memory around a native crash, which can include text. The folder
belongs to the app; other apps can't read it, adb can.

Useful tags when reading a file: `PerfLogger` (`[llm-done] duration=... tokens=... speed=...tok/s`,
and snapshots such as `[session-start] battery=... | RAM used=... | nativeHeap=... | read in <ms>
ms`; since 0.5.1 a snapshot is read on PerfLogger's own thread, as its battery and memory reads,
binder calls, took 0.2-0.9 s on the main thread in the seconds after a finishing began, ahead of
the next recording's start buzz; `read in` says how long they take now),
`SatoriVitals`, `SatoriFieldLog`, `SatoriQuickKey`, `SatoriDebugFeed`, `MainViewModel`,
`SherpaOnnxDataSource` (also `Transcribed ... ms of audio again with <model> in ... ms`),
`SatoriFinishing` (the finishing of recordings in the background), `SatoriLines` (lines joined
after Stop), `SatoriTodos` (each change of a to-do's status), `SatoriNotes` (since 0.7.1, the
pieces of the AI's answers left out because they repeat their prompt; since 0.7.2 also those left
out for other reasons, the notes and names asked once more, and where a name came from; since
0.7.3 also those that repeat the rules of their system prompt, are in another script or repeat an
earlier piece, and what the mind map's answers lost),
`SatoriRetention` (older audio
compressed or deleted), `SatoriAudio`, `SatoriPlayer`, `SatoriDatabase` (the database file's name),
`RecordingService`, `LlmProcessingService`, `LlmRepositoryImpl`, `InferenceEngineImpl` (each AI
model load's settings), `SatoriLoads` (the one-load gate, the pre-read and the weight copy), `ai-chat`
(llama.cpp),
`SatoriReader` (since 0.8.0, the reader's load and release: `The reader`, below), `AndroidRuntime`
and `CRASH`. The lines each part of the app writes are listed below, from "Finishing in the
background" on.

Each capture (a recording that was continued has one per stretch), and each Transcribe again or
better transcript after Stop, ends with one `SherpaOnnxDataSource` line that says how its
transcript was made, also when it was cancelled or failed:

```
Speech summary: model=parakeet-tdt-0.6b-v3-int8 vad=LIVE threshold=0.40 minSilence=0.50s maxSpeech=20.0s mic=VOICE_COMMUNICATION effects=AEC+NS mics=2 device=builtin_mic/bottom fold=closed gain=off speechLevel=-41.8dBFS p90=-33.6dBFS finals=12 lost=0 medianRtf=0.081 cpus=0-7 sherpa-onnx=1.13.8 ended=done
```

- `model` is the speech model that made the transcript: the live model of the recording's language
  for a recording (Parakeet v3 for English or German, `indicconformer-ml-int8` for Malayalam, since
  0.6.0), the model chosen for Transcribe again, or the one set for a better transcript after Stop
  (`SatoriFinishing` then says `Better transcript after Stop of <id> by <model>`, or why there was
  none). A capture whose recording was switched to another language while it recorded names each
  model in turn, joined with `>`: `model=parakeet-tdt-0.6b-v3-int8>indicconformer-ml-int8`.
- `vad` is the voice detection profile: `LIVE` with the values of Advanced settings, or `FILE`, the
  fixed values of Transcribe again and of the better transcript after Stop.
- `mic` is the microphone input: `MIC` (Advanced settings' "The phone's usual input", the default
  since 0.5.3), `VOICE_RECOGNITION` ("For speech recognition", the default from 0.4.0 to 0.5.2),
  `VOICE_COMMUNICATION` ("With the phone's noise reduction", Android's call input),
  `MIC(fallback)` when the input asked for didn't open (a warning says why, once per app start),
  `feed` for a debug WAV
  feed, `file` for a recording's kept audio, `none` when no input opened or Stop came while it
  opened. `Recording stopped before the microphone opened` comes in place of this line only for a
  Stop while the settings are read. A Stop while the speech model's native load runs doesn't end
  the capture there: what was recorded is transcribed once the model is in, and this line follows
  as usual. One while its files are still read in or room is made for it (since 0.7.1), in a
  recording that keeps its audio, stops the load and ends the capture at once: `ended=left` (below).
- `effects` are the audio effects on the capture's stream (AudioRecordingConfiguration.getEffects):
  `AEC`, `AGC` and `NS` are Android's echo cancelling, automatic gain control and noise
  suppression, and any other effect is named by its type UUID. Sorted and joined with `+`, `none`
  for no effect, `?` when the phone didn't say within the first 2 s of reads. `mics` is how many
  microphones the input uses (AudioRecord.getActiveMicrophones), or `?` when unknown. Transcribe
  again, the better transcript after Stop, debug feeds and captures whose input never opened say
  `effects=none mics=0`.
- `device`, `fold` and `gain` (since 0.5.3) say where a capture's audio came from. `device` is the
  device Android routed the input from (AudioRecord.getRoutedDevice): its type, and for a built-in
  microphone its address, `builtin_mic/bottom` or `builtin_mic/back` on the razr; other types
  (`wired_headset`, `bt_sco`, `ble_headset`, `usb_headset`, ...) without the address. `fold` is
  `open`, `closed`, `half` or `unknown`: from the hinge angle sensor when the phone gives apps one
  (closed up to 30 degrees, open from 150), otherwise from the screens that are on (the inner one:
  open; only the cover: closed; none: unknown). Either is a list joined with `>` when it changed
  during the capture (`fold=closed>open`), `?` when the phone never said. `gain` is the debug
  switch below: `off`, a fixed `+12.0dB`, or `auto(<median>/<highest>dB)`, the gains the speech
  got (`auto(none)` without speech). Transcribe again and the better transcript after Stop say
  `device=none fold=none gain=off`, a debug feed `device=none`. On 2026-10-13 the owner's recordings
  with the phone closed had `speechLevel` -51 to -64 dBFS (`p90` -44 to -50), against about -22
  (`p90` -15) the morning before. The input was the cause, not the fold: that evening
  `mic=VOICE_RECOGNITION` gave -62 dBFS open and -61 closed (3 words in 23 s), and the owner's
  A/B on `MIC` came about 20 dB louder (56 words in 21 s), as recordings before 0.4.0 did. Since
  0.5.3 MIC is the default; a stored choice stays.
- `speechLevel` and `p90` say how loud the speech was as the recognizer heard it, after the
  `gain`: the median and the 90th percentile of the
  level of the voice detector's 32 ms windows while it heard speech, as RMS in dBFS (the measure of
  Android's compatibility definition, which asks VOICE_RECOGNITION to give a 90 dB SPL tone at
  -22 dBFS); `none` without speech. The speech recognition input has no automatic gain (on the
  razr about 20 dB under MIC), so compare `mic=VOICE_RECOGNITION` and `mic=MIC` lines of similar
  speech before blaming the voice detector for missed lines. The recorder's level display starts
  15 dB lower for VOICE_RECOGNITION.
- `medianRtf` is the median real-time factor of the complete utterances: decode time over audio
  length, timed inside the decode lock, so the wait for a partial result doesn't count. `finals` is
  how many were timed. Partial results keep their own lines, `Partial result (N ms, M new samples)`.
- `cpus` are the CPUs the thread that decoded the complete utterances was allowed to run on (its
  `Cpus_allowed_list` in `/proc/self/task/<tid>/status`), read as each one was decoded. Android
  sets them by the app's scheduling group, so the line says where the run was without adb: on the
  razr `0-7` is top-app (Satori in front), `0-6` foreground (the screen off under the recording's
  or the AI's foreground service) and `0-2` background (the screen off and no service: the three
  little cores, on which a whole-file transcription takes 4 to 7 times as long as in front, about 2
  to 4.5 times the speech models' estimate). When the group changed during the run the sets come in
  the order the decodes first ran on them, joined with `/` (`0-7/0-6`: the screen went off); `none`
  when no utterance was decoded.
- `lost` counts complete utterances that came after the recorder stopped waiting for the last words
  and released the speech model, or (since 0.6.0) that found the model of a newer capture in
  another language in memory, which would have written them in its script: they were never
  decoded and are missing from the transcript, though the recording's kept audio has them. The
  recorder waits for the stream's own end, at most 15 s (`MainViewModel` then says `Capture did not
  end within 15 s (state=<capture state>)`). Anything but 0 means the end of the recording took too
  long; the utterance being decoded at that moment is missing as well, though it counts among the
  `finals`.
- `ended` is `done` when the recognition got to the end of its audio, `cancelled` when it was cut
  off first (the recorder closed while it still ran, Transcribe again was left, or the better
  transcript after Stop was skipped), `failed` after an error, and `left` (since 0.7.1) when Stop
  came while the speech model was still read in and the recording keeps its audio, so what it
  recorded is transcribed after Stop instead (`finals=0`).

The microphone opens before the speech model loads, so the first words are kept while it loads.
Once per capture `SherpaOnnxDataSource` says how long the load took and how much audio waited for
it (a few ms when the model was in memory already):

```
Speech model ready after 1840 ms, with 1700 ms of audio waiting (parakeet-tdt-0.6b-v3-int8)
Speech model failed to load after 1200 ms (ModelDamagedException); 1100 ms of audio recorded and not transcribed
```

The first names the live model in use (since 0.6.0): the recording's language's, Parakeet v3 or
the Malayalam model, also when it was in memory already and no load line came. The second is an
error: the capture then fails with SpeechModelLoadException, and the kept audio holds what was
recorded. For the same reason `Recording thread`, `Audio config` and `Microphone input` now come
before the speech model's own load line (`Speech model parakeet-tdt-0.6b-v3-int8: 2 threads,
...`), and `Audio config` comes once per input opened (twice after a fallback) rather than once per
capture. `Recording stopped while the microphone opened` can come during the load as well.

A switch of the recording's language while it records (since 0.6.0, "Record as") changes the live
model between two utterances, after the one being said is out: the model in memory is freed and
the other loaded in its place, so the two are never in memory together, while the microphone, the
audio waiting to be transcribed and the kept audio go on. No `Capture start` or summary line comes
for it; the capture says:

```
Live speech model switched to indicconformer-ml-int8 (was parakeet-tdt-0.6b-v3-int8) in 640 ms, at 41200 ms of audio, with 700 ms of audio waiting
W Live speech model indicconformer-ml-int8 failed to load (ModelDamagedException); loading parakeet-tdt-0.6b-v3-int8 again
W Live speech model indicconformer-ml-int8 didn't load; back on parakeet-tdt-0.6b-v3-int8 after 2900 ms, at 41200 ms of audio
E No live speech model after a switch: neither indicconformer-ml-int8 nor parakeet-tdt-0.6b-v3-int8 loaded (IllegalStateException); 3100 ms of audio recorded and not transcribed
```

`in` is the time from the release to the end of the new load, during which the transcript waits
and the audio queues; `at` is how far into the capture's audio the switch came, and `waiting` how
much audio was queued behind it, which the recognition then catches up on. The second and third
lines come together: the new model didn't load, the old one is in again, the capture goes on with
it, and the recorder takes the switch back (`MainViewModel`, below). The last is an error: neither
model loads, so the capture ends as one whose model failed to load at the start, with what was
recorded in the kept audio. A switch made after Stop isn't carried out: the last words go to the
model they began with. While the model changes the capture is LISTENING again, as while its model
loads at the start: the finishing of an older recording waits with its next step (`Finishing waited
<ms> ms for a capture to open`, below), and a recorder closed meanwhile marks the audio waiting as
never transcribed. In a debug build the switch also has its two memory lines (below), `release of`
the one model and `load of` the other.

A switch while the speech model still loads at the start (since 0.7.1): while its files are read in
(`Loads one at a time, at a pace`, below) that load stops, and the capture loads the new language's
model in its place, so the one no longer wanted never takes its memory. `SatoriLoads` says `Pre-read
of <model> stopped ...` (or `Load of <model> not started ...`), then the capture:

```
Live speech model parakeet-tdt-0.6b-v3-int8 not loaded, as the recording switched to indicconformer-ml-int8 first
```

and its `Speech model ready` line names the new model. Once the native load runs, which nothing can
stop half way, the capture changes the model as soon as that load ends, before the recognition
starts, so the model loaded for nothing decodes nothing: the old model's `Speech model ready` line,
then `Live speech model switched to ... at 0 ms of audio`. On 2026-10-16 (0.7.0) such a switch came
2.5 s into Parakeet v3's 2.8 s load: the load ran to its end (RssAnon 57 -> 1261 MB), Parakeet was
freed 177 ms after it, before the Malayalam model began to load, and the two were never in memory
together; a cached app was killed during Parakeet's load.

Stop while the speech model still loads at the start (since 0.7.1): while its files are read in or
room is made for its parse (`Loads one at a time, at a pace`, below), in a recording that keeps its
audio, the load stops there and the capture ends at once instead of waiting seconds for the model
(Parakeet v3's 670 MB take about 5.6 s to read in cold). Nothing is loaded and nothing decoded; the
audio file keeps all of it, the recording counts as one with speech, and the finisher transcribes
it (`Finishing in the background`, the better transcript):

```
Stop came 2140 ms into the load of parakeet-tdt-0.6b-v3-int8: nothing loaded, 1900 ms of audio kept untranscribed for after Stop
```

`SatoriLoads` says `Pre-read of <model> stopped after <ms> ms, as Stop came first and the
recording's audio is kept for after Stop: ...` (or `Room for <model>'s parse given back after ...`,
or `Load of <model> not started, ...`), the speech summary `ended=left`, and `MainViewModel` `Capture
ended at Stop before its speech model was in; its audio is transcribed after Stop`, then `Recording
<id> handed to the finisher (recognizerFailed=true)`. A Stop during the native load, or in a
recording that keeps no audio, waits for the model as before, which then transcribes the queue.

Before any speech model loads, its files are checked (since 0.6.0 also what sherpa-onnx reads from
them, where the registry `SpeechModels` names it: the metadata of the model file and the tokens).
sherpa-onnx would end the whole process on a key it lacks or a line of tokens it can't parse, so a
file that fails is refused, the load fails with ModelDamagedException as above, and
`ModelDownloadManager` says why, in names, keys, values and counts only:

```
W Speech model indicconformer-ml-int8 refused, Damaged: model.int8.onnx metadata subsampling_factor=8, expected 4
W Speech model indicconformer-ml-int8 refused, Damaged: tokens.txt: 256 tokens, not 257
W Speech model indicconformer-ml-int8 refused, Unreadable: model.int8.onnx can't be read (IOException)
```

`Damaged` is a file the registry is wrong about (a download of the same commit brings the same
bytes) or one put there by hand; `Unreadable` may pass on the next try. A stretch of audio under
0.1 s is never handed to a speech model, live or for a file: it holds no word, and some
sherpa-onnx models end the process on one (`D <n> ms of audio not decoded: under 100 ms`, rare: the
voice detector gives out no utterance under 0.25 s, so only audio that ends just as speech begins
is that short).

An utterance whose text holds no letter or digit gives out no line either (since 0.7.1), live or
for a file, and a partial result without one isn't shown: `D <n> ms of audio decoded to no word (<k>
chars): no line`. A speech model writes such a text for a noise: Parakeet a lone ".", the Malayalam
model a vowel sign or two that no letter carries, which the clean-up of a line's Malayalam leaves
out. In 0.7.0 the start buzz, which the microphone picks up in the first half second, came out that
way as an empty first line of a Malayalam transcript.

As its audio starts, each capture says where it comes from (since 0.5.3), and then each change
while it records:

```
Capture start: source=MIC channels=1 device=builtin_mic/bottom fold=closed asked=auto gain=off
Capture route: device=builtin_mic/back (was builtin_mic/bottom)
Capture fold: open (was closed)
Capture gain now +24.0 dB (speech at -50.1 dBFS)
```

`source` is the input that opened (`feed` for a debug feed), `channels` its channel count,
`device` the routed device as in the summary (`pending` while Android hasn't routed it yet, which
the first `Capture route` line then gives), `fold` as in the summary, and `asked` and `gain` the
debug switches below (`asked=auto gain=off` when none is set). `Capture gain now` comes only with
`gain=auto`, each time the gain has moved by 6 dB or more. A switched device adds `Microphone asked
for: builtin_mic/back` before the start line, or `W No built-in microphone at <address> (built-in:
<devices>); Android routes the input` or `W Android refused the microphone <device>; it routes the
input`.

#### The microphone's debug switches (debug builds)

For measurements with the owner's voice, a debug build reads two system properties at each capture
start (a release build never; the defaults record as before). Set them with `adb shell setprop
<name> <value>` before a recording, and clear them afterwards with `adb shell setprop <name> ''`;
a value it doesn't know is ignored with `W Ignoring debug.satori.mic.<name>=<value>: ...`, and a
property it can't read with `W Ignoring debug.satori.mic.*: the properties can't be read`.

| Property | Values | What it does |
|---|---|---|
| `debug.satori.mic.device` | auto, bottom, back | bottom and back ask Android for the built-in microphone with that address (AudioRecord.setPreferredDevice, from AudioManager.getDevices(GET_DEVICES_INPUTS)); auto lets Android route, as before. `Capture start` and `Capture route` say which one it recorded from |
| `debug.satori.mic.gain` | off, 0-30 (dB), auto | A digital gain on the audio the voice detector and the recognizer get, never on the kept audio. A number is a fixed gain; auto aims the speech at about -26 dBFS with at most +30 dB. Speech is what comes 9 dB over the noise floor, the second quietest read of the last 2 s (reads under -100 dBFS, the zeros of an input that starts or that Android silences for another app, never count). The first speech sets the gain and louder speech lowers it, at once; quieter speech raises it by 3 dB per second of speech once the 2 s after it held a read 9 dB quieter (so it was speech, not the room getting louder), and between words it stays, so the noise there isn't pumped up. A step in the noise before anyone speaks is still taken for the first speech; the first `Capture gain now` line then names the room's level. No chunk clips: the gain on a chunk is held to what keeps its loudest sample at 0.98 of full scale |

Check before and after a session that nothing is left set: `adb shell getprop | grep debug.satori.mic`
must print nothing or empty values.

### Finishing in the background

Stop hands a recording to the finishing queue, which makes its better transcript, joins its lines,
writes its last notes, its name and its to-dos and links it into the mind map, one recording at a
time, while the next one may already record. Its lines go under `SatoriFinishing` and name a
recording by its id only:

- `Finishing queued <id>`: the hand-off, normally well under a second after Stop. `W Finishing has
  no job for <id>: the recording is gone, or its job couldn't be written` when it couldn't be
  queued.
- `Finishing started <id> at <step|the start>, attempt <n>`, then one line per step, `Finishing:
  <step> of <id> after <ms> ms`, with `, beside a capture with a model in memory, <n> MB
  available` while another recording records, and at the end `Finishing done <id> in <ms> ms:
  <Finished|NotesFailed|Discarded>`. `Discarded` is a recording a process death cut off, in which
  nothing was said, or (since 0.7.1) one handed off with `recognizerFailed=true` whose whole kept
  audio the Transcript step found no speech in, with no line holding a word: a quick-key press
  stopped before its speech model was in, say.
- The model: `Finishing <id> on Gemma 4 E2B, ready after <ms> ms`, `Finishing <id> on the live
  model`, or `W Gemma 4 E2B could not be loaded (<ms> ms), finishing <id> on the live model`.
  `Finishing <id> without the AI: AI notes off` (or `no live model on the phone`) when only the
  better transcript and the lines run.
- Gemma 4 E2B needs its file plus 600 MB free (`LoadFinishingModelUseCase.E2B_MARGIN_BYTES`) for
  every load of it: the finisher's, whether a capture runs beside the finishing or not, and Rewrite
  notes', Find again's and the map's (since 0.5.2): Satori must not push the phone into memory
  pressure. On 2026-10-13 a load of E2B with about its file's worth free, 0.5.0's bar with no capture
  live, put the phone in direct reclaim, and `lowmemorykiller` ended 45 cached apps in 2.6 s
  (`oom_score_adj` 920-999); 0.5.1 let the last try load it with up to 300 MB less than the file,
  which 0.5.2 removed. Each `<m> MB needed` below is the file's 3194 MB plus 600, 3794 MB. The bar
  is a floor: at 12:12 that day a load that passed it (about 5.0 GB free) still ended 10 cached apps
  while llama.cpp read the file in; the debug memtest below measures what a load costs.
- Gemma 4 E2B with no capture live (since 0.5.1): too little memory alone doesn't give the live
  model at once. First `Finishing waited <ms> ms for the speech model to be freed` (`, going on`
  after 5 s, `, a capture started` when one did, which defers the recording with nothing loaded
  beside the capture as it opens) if the recorder that closed still had it in memory; normally it is
  freed before the hand-off and there is no line. Then, when E2B doesn't fit, `Finishing <id>: no
  room for Gemma 4 E2B with no capture live (<n> MB available, <m> MB needed), trying again for up
  to 15 s`, and once a second until `Finishing <id>: room for Gemma 4 E2B at try <k>, after <ms>
  ms`, or after 15 s `Finishing <id>: still no room for Gemma 4 E2B after <ms> ms (<n> MB available,
  <m> MB needed), the live model does the work`, with the last try's figures. The model line
  follows: `W Gemma 4 E2B could not be loaded (<ms> ms), finishing <id> on the live model`. A try
  that gets past the memory check and still loads no E2B (a damaged file, E2B switched off
  meanwhile) has no `room` line: the model line says what the steps run on. `Finishing <id>: a
  capture started while Gemma 4 E2B didn't fit, after <ms> ms` comes before a deferral beside that
  capture, whose `<m> MB needed` is the last try's: the bar is the same.
- The better transcript: `Better transcript after Stop of <id> by <model>`, `No better transcript
  after Stop of <id> with <model>: <reason>`, or `W Better transcript after Stop of <id> with
  <model> failed`. `TooLong` is a recording the model's estimate (`SpeechModelChoice`, from the
  razr's own times) puts over three minutes (`TranscribeAfterStopUseCase.MAX_ESTIMATED_MS`): with
  Cohere Transcribe one over about 8 minutes, with Parakeet v2 one over about half an hour. With
  only the finisher's wake job keeping the app (after a reboot, `Finishing runs under the wake
  job`, no AI service), the threads are on the little cores and the estimate is theirs: one over
  about a minute and a half with Cohere, about 5 minutes with Parakeet v2. Transcribe again on its
  page takes any length. A recording stored in Malayalam (since 0.7.0) is transcribed again by the
  Malayalam model whatever is set, its whole audio with the values for files: `Better transcript
  after Stop of <id> by indicconformer-ml-int8`, and its speech summary says
  `model=indicconformer-ml-int8 vad=FILE`. Its `LeftMalayalam` is a recording that went back to
  English or German while it was recorded (a line in Latin letters after one in Malayalam script),
  its `AiModelLoaded` an AI model a load under way kept in memory, as the model in memory is
  unloaded first; its `TooLong` is one over about 20 minutes, on the little cores about 5. A
  recording in English or German handed off with `recognizerFailed=true` (since 0.7.1: its live
  recognizer failed, was cut off, or Stop came before its model was in) is transcribed whole from
  its kept audio by Parakeet v3 when nothing set for after Stop does it: `Better transcript after
  Stop of <id> by parakeet-tdt-0.6b-v3-int8`, with the AI model unloaded first and the step waiting
  the hand-off grace as one that loads a model; `No better transcript ... with
  parakeet-tdt-0.6b-v3-int8: LeftMalayalam` for one with Malayalam lines, which Parakeet would write
  over, and `NoSpeech` when its whole audio held none (the recording is then `Discarded`).
- Too little text for notes (since 0.5.3): the Notes step counts the words before any model is
  chosen, and with fewer than its notes ask for (20 for a final note,
  `GenerateFinalInsightUseCase.MIN_WORDS_FOR_FINAL_INSIGHT`, and 20 for the whole pass,
  `GenerateBatchInsightUseCase.MIN_WORDS_FOR_BATCH`) it says so at once and doesn't begin:
  `Finishing <id>: too little text for notes (<w> words, <n> needed), no model chosen for it`;
  `... for a final note (...), no model chosen for it; the notes it has stay` when only the lines
  after the live notes are too few; `... for a translation (...)` for a Translate recording. Home's
  line under the recording then says "There isn't enough text to write notes from" in place of its
  step until it is finished, and its page's notes tab says it too. The name, the to-dos and the map
  still run, and the first of them with something to do chooses the model, so a `Finishing: Notes
  of <id>` line no longer comes for such a recording. `Finishing <id>: no AI step had anything to
  do, no model loaded` ends a run in which none had. On 2026-10-13 at 14:20 a 26 s note of 17
  words began its Notes step at 3.0 s, waited 16.3 s for room for Gemma 4 E2B (`room for Gemma 4
  E2B at try 11`), and only then found too little text for the note.
- The languages (since 0.6.0), once the AI steps read the transcript: `Finishing <id>: transcript in
  <ml|en|de|unknown>, recorded in <ENGLISH_OR_GERMAN|MALAYALAM>, notes in <en|de>`. The first is
  what `LanguageDetector` finds in the whole transcript (`unknown` when it isn't sure: short, or
  English and German mixed), the second the language the recording is made in, the third the one its
  notes, name, to-dos and topics are written in: a Malayalam recording's say `transcript in ml,
  recorded in MALAYALAM, notes in en`. Since 0.8.0 its notes read its readings (below) with the
  prompts of an English recording, no line on reading Malayalam among them, in the language its
  transcript as it was said gives: English, whatever the setting. A Translate recording's notes are
  its target (`notes in the settings' language` while it has none of its own). The first is stored as the recording's
  `input_language` (since 0.6.0; nothing when the detector isn't sure), which held "it" for every
  recording before: an old default that nothing replaced.
- The Reading step (since 0.8.0): after the languages, a recording with lines only the reader reads
  (a Malayalam recording's) has them read into English one by one, each reading stored as it is made
  (`segment_readings`), before its AI steps, which then read the readings. A line with no current
  reading never goes to the AI model: it is left out. The step waits the hand-off grace and a capture
  that opens as any step that may load a model does, lets the AI model go (`cleanup`), waits for the
  speech model of the recorder that closed (`Finishing waited <ms> ms for the speech model to be
  freed`), and loads the reader (the reader's own lines, `SatoriReader` and `SatoriLoads`, below).
  Then, counts only: `Finishing <id>: read <n> lines in <ms> ms, <k> kept, <e> empty (<a> fragments,
  <b> under the floor of -1.5, <c> outside the band, <d> repeating, <f> blank, <g> not read)`, with
  `, <h> not stored` when a reading couldn't be stored. The empty ones are design D5's (a fragment of
  under 2 words or 6 letters, the model not asked; a mean log-probability under the floor, -1.5 until
  V3 sets it; a length outside V2's band of 0.500-1.079 tokens a source piece, from 12 pieces, or one
  that hit D15's cap; a run of 3 words said 3 times; no words) or a line the reader couldn't read.
  `Finishing deferred <id> at Reading: a capture is live, which the reader never reads beside` when a
  capture is live as it would load, or opened while it read (D14: the recorder that starts, and the
  capture-open path, stop the reader at once; it goes on from the next line once that capture is
  over). `Finishing of <id> stopped at Reading: the reader can't load beside <the speech model|the AI
  model|a capture> now; it runs again in 60 s` when something it never loads beside stayed in
  memory (the third time in a process `W Finishing of <id> stopped at Reading 3 times, the reader held
  off by <...>; its job waits for the next start`); the phone's memory stopping its paced load is the
  model's line above. Without the reader and none coming (design D7): `Finishing <id> without its
  AI steps: the reader isn't on the phone and isn't coming` (or `the reader failed`, after `Finishing:
  Reading of <id> failed`, or `its Reading step was skipped, and lines were never read`), then
  `Finishing done <id> in <ms> ms: Unread(why=<NO_READER|NOT_READ|SKIPPED>)`: no notes, no name, no
  to-dos and no map rather than the AI model's of Malayalam, which come once the reader can read the
  lines; the recording is then due to be made again (`transcription_sessions.redo_due`, `W Finishing
  could not store whether <id> is to be made again` with its exception when that can't be stored). A
  reader that failed counts one failure against the recording's backfill of readings, and so does a
  run that went on past the Reading step with lines never read (a job an older version left at its
  Notes step, say); a step this process skipped after it began twice counts two at once (the process
  ended in it twice); a reader not on the phone none (`W Finishing could not count the backfill of
  <id> that failed` with its exception when that can't be stored). The wake job's end never skips
  the Reading step (no `Finishing skipped Reading of <id>: the wake job's end cut it short <n> times`):
  each run of it stores what it read and the next goes on from the next line.
- The backfill of readings (since 0.8.0's R9, the owner's O8): whenever the reader is on the phone,
  AI notes are on and an AI model is on the phone or on its way, each Malayalam recording (made in
  Malayalam, or its transcript found Malayalam) that is due to be made again
  (`transcription_sessions.redo_due`: every Malayalam recording from before 0.8.0-dev3, which the
  migration to database version 13 marks, and one finished without its AI steps for want of its
  readings), without a finishing job, with fewer than 2 failures counted against it and with lines
  the reader reads, is queued again once no capture is live, whether its lines were read already or
  not (the page's work, the map's or a debug rerun read them too): `Backfill of readings queued <n>
  of <m> Malayalam recordings to be made again over their readings`, then for each `Finishing queued
  <id> again from its Reading step, to be made again over its readings` and `Finishing started <id> at
  Reading, attempt <n>, made again over its readings`. It looks as the app starts, and again as the
  reader comes, AI notes or the AI model set change, or the first run's AI model comes or goes; a
  capture that starts as it queues holds the rest back until it is over. Such a job comes after every
  other job in the queue (a recording made after it gets its notes first) and runs under the queue's
  rules (the 3 s grace before its first load included, for a recording started right then), but
  never beside a capture: none of its steps begins beside one, and one that starts stops the step
  under way at once, which runs again once that capture is over (`Finishing deferred <id> at <step>:
  a capture is live, which a recording made again never runs beside`). It says as its AI steps come
  what of the owner's stays: `Finishing <id> made again over its readings: <his notes stay|its notes
  are written again>, <his name stays|its name is the app's>, <k> to-dos he changed stay, <r> of the
  app's give way`. Before it replaces anything it keeps what the recording has, once, the first
  time (`kept_before_redo`, `notes_kept_before_redo`): its name, whose it was, its notes' strategy
  and its notes as they were, for the debug tools' `restore-before-redo` (below). One that finishes
  is done (`redo_due` 0); one whose new notes failed or came out empty keeps the old ones, ends
  `NotesFailed` and counts one failure, to be tried again. The owner's edits are told from the AI's work by the database: a note he saved in
  the note editor (`llm_insights.edited_at` set) keeps all the recording's notes; a name he typed
  with Rename (`transcription_sessions.name_by_owner` 1) stays; a to-do or idea whose last change
  came more than 5 s after it was found (`todos.updated_at > created_at + 5000`) stays, and the
  app's own that nobody touched go once the new ones are stored (SatoriTodos `To-do <id>: <status>
  -> gone (read again)`). Names and notes from before 0.8.0-dev3, which no column recorded, count as the app's.
  New notes replace the old in one write once written (the whole pass for an end-of-session
  recording or text longer than a final note reads, else one final note on every line); too little
  readable text takes the old ones away; notes that fail leave them, and so does a note he saves
  in the editor meanwhile, looked for in the very write that replaces them. A note he saves after the
  notes made again replaced the one he was editing is kept as a note of its own, his. A to-do he
  edits after the app took it away comes back with his edit, his. The name is made from the new notes, the owner's, or the readings, never
  from the old notes; a Translate recording keeps its translation. With too little readable text
  for a name or a to-do the old ones stay, as after Transcribe again, and with no notes left the
  map is made again without the model (`Finishing <id>: no AI step had anything to do, no model
  loaded`), the topics of the old notes gone. No "notes are ready" cue for it. `W Backfill of readings could not read the
  recordings`, `W Backfill of readings could not tell whether the reader and the AI model are in`,
  `W Finishing could not queue <id> again` and `W Backfill of readings stopped looking; the next
  start looks again`, each with its exception.
- The AI steps after the Reading step read the readings (since 0.8.0's R6): the notes, the name, the
  to-dos and the map of a recording with lines only the reader reads ask the model what they ask of
  an English recording whose lines are those readings, with the same prompts, no line on reading
  Malayalam among them and none on the language of the notes; each line keeps its id, so a to-do
  still cites its Malayalam line and a topic links to it. A line edited after it was read is left
  out of the steps after the edit (its reading is of its old text). The language they write in is
  the one the transcript as it was said gives (English for Malayalam, whatever the setting), the
  transcript itself stays as it was said, and so do the input language and every editor of it. The
  to-dos of a read line are found as an English recording's are, the spotter, the time parser and
  the gate's cues included, but a due time read in a reading is kept only where its line as it was
  said says when (a day, a part of it, a date, an hour in either spelling or in figures, "this" or
  "next week" or "month" in Malayalam: design D9), so a date the reader made up sets no due time;
  the gate of Malayalam lines is off for read lines, and what it would have dropped is counted
  (`SatoriTodos`' `Kept <k> found items of read lines ...` below). No other text of the recording
  in Malayalam letters reaches the model either: not a name as the notes' topic, not notes the name
  or the map would read (an older version's, or ones the owner edited: the readings are read
  instead), and not the recording's own to-dos shown to it as noted (they still count as known).
- The AI work outside the finishing makes the readings it lacks first, holding the AI and before
  its model loads (since 0.8.0's R6, `EnsureReadingsUseCase`): Rewrite notes and "Change type"'s
  rewrite, Find to-dos and ideas again, the text made again after Transcribe again, the map's work
  and the debug rerun. It reads by the Reading step's rules (the AI model let go first, the reader
  loaded paced, released and the native heap purged; nothing when every line has its reading), and
  says, under SatoriFinishing, counts only:

  ```
  Readings of <id> for <Rewrite|Find again|Text again|The map|Debug rerun>: read <n> lines in <ms> ms, <k> kept, <e> empty (<a> fragments, <b> under the floor of -1.5, <c> outside the band, <d> repeating, <f> blank, <g> not read)[, <h> not stored]
  W Readings of <id> for <work> missing: the reader isn't on the phone|the reader can't load beside <the speech model|the AI model|a capture> now|a capture opened, which stopped the reader|the phone's memory stopped the reader's load|the reader failed (<class>)
  ```

  With readings missing, the page's work doesn't run and says it failed (the page's existing "The
  notes couldn't be written", "... rewritten" or "Looking for to-dos and ideas failed"), and what
  the recording has stays: never the AI model on the Malayalam, nor notes of the rest of what was
  said (D7). The map leaves such a recording for a later run (`The AI couldn't run` for "Map this
  recording"); without the reader on the phone it maps it as before the reader, from its notes, or
  marks it read without topics, the model not asked, when it has nothing but lines the reader reads.
- Waiting for the AI model or the reader the first run downloads: `Finishing <id> up to its first AI
  step: the AI model is on its way`, then `Finishing of <id> waits for the AI model at <step>`, or for
  a recording with lines only the reader reads while the reader is on its way and not on the phone
  `Finishing of <id> waits for the reader at Reading`. Each job waits for what it needs, and
  `Finishing goes on with <n> that waited for the AI model` (or `the reader`) once its own wait is
  over: a job that waits for the reader is not let go while the AI model is on the phone. Home and
  the recording's page say "Waits for the AI model" for both. `W Finishing could not tell whether the
  reader is in` (or `is on its way`) with its exception.
- Gemma 4 E2B beside a capture (since 0.6.0): its room is measured only once that capture's speech
  model is in. `Finishing waited <ms> ms for a capture's speech model before Gemma 4 E2B` when the
  capture was still opening as the step began, or its live model was being changed after a switch
  of its language (the one is freed before the other loads, for a second or three); `..., deferring`
  after 10 s, which defers the recording as `a capture started as Gemma 4 E2B was to load` does.
  Beside a capture with the Malayalam model in, its room also keeps what a switch of that recording
  back to English or German loads, Parakeet v3 less the Malayalam model by their files, as the
  recorder's own load never waits: `Finishing <id>: Gemma 4 E2B's room beside the capture keeps 508
  MB for its speech model`, and the `MB needed` of a deferral for want of room includes it.
- The to-dos: `Finishing to-dos of <id>: Found(count=<n>, ideas=<n>, suggested=<n>, overCap=<n>)`,
  `Skipped` or `Failed`. `count` includes the ideas; `overCap` counts the suggestions past the
  three kept.
- Waits before a step that may load a model: `Finishing waited <ms> ms for a capture to open`
  (`, going on` after 10 s), while a new recording's speech model loads (since 0.6.0 also while a
  recording's live model changes after a switch of its language), and `Finishing paused:
  phone hot` with `Finishing goes on after a pause of <ms> ms` while a capture runs on a hot phone.
  A capture still opening after 10 s is not waited for again by the steps after, only once more
  when its microphone opens (a recorder that waited for the screen, say). The joining of lines
  loads no model, nor (since 0.5.3) does the better transcript with no speech model set for it:
  then the steps up to the notes run right after Stop, and the 3 s after a hand-off are waited by
  the first step that may load one (`Finishing: Notes of <id> after 3000 ms`, the Transcript and
  Lines lines a few ms after the start).
- Deferrals: `Finishing deferred <id> at <step>: a Translate capture is live`, `...: no room for
  Gemma 4 E2B beside a capture (<n> MB available, <m> MB needed)`, or `...: a capture started as
  Gemma 4 E2B was to load` (during the wait for the speech model to be freed), then `Finishing goes
  on with <n> deferred` once that capture has ended, at the latest with its hand-off; the deferred
  recordings are then finished before it, in the order they were stopped.
- The keep-alive: `Finishing waits for a keep-alive` when Android didn't have the AI's service in
  the foreground 5 s after its start (the wake job is scheduled then), `Finishing keep-alive gained
  after <ms> ms`, `Finishing runs under the wake job`, and `Finishing of <id> stopped for want of a
  keep-alive; its step runs again`. `Finishing skipped <step> of <id>: the wake job's end cut it
  short 2 times` for a step that outlasts every run the wake job gets. `W Finishing wake job not
  scheduled` (with its exception), `W Finishing wake job not scheduled (result <n>)` when
  JobScheduler refused it, and `W Finishing wake job not looked up`, all from
  FinishingWakeScheduler under the same tag.
- After a process death: `Finishing resumed <n> (recovered <m>)`, where `m` were cut off while they
  recorded; `Finishing took the length of <id> from its audio: <ms> ms, <ms> ms stored`; `Finishing
  skipped <step> of <id>: began twice`; and `Finishing takes the AI's service for <n> unfinished`
  once the app may start that service (after a reboot, when it opens), also for the one the wake
  job runs then.
- `Finishing queue empty, model released` once nothing is left.
- A recording deleted meanwhile: `Finishing of <id> cancelled`, or `Finishing of <id> stopped: its
  job is gone`.
- A model load the phone's memory stopped (since the 0.6.1 refix; every AI model load is paced,
  and stops after 10 s in all of MemFree under the pacing floor): the live model the AI steps run on is put in
  memory as the first of them begins, after Gemma 4 E2B gave way to it if it was tried, and a stop
  there ends the run before any AI step: `Finishing of <id> stopped at <step>: the phone stayed
  short of memory as the model loaded; it runs again in 60 s`, then `Finishing waits <ms> ms for
  memory before the next run` (the gate and the model are let go of meanwhile; the job shows as
  waiting) and `Finishing started <id> at <step>, attempt <n>` (its start isn't counted). The third
  stop in a process is a warning, `Finishing of <id> stopped at <step> for want of memory 3 times;
  its job waits for the next start`. Before, such a stop failed every AI step in turn and the
  recording was finished with no notes, name or to-dos for good.
- Failures, all warnings: `Finishing: <step> of <id> failed` (the next step still runs),
  `Finishing of <id> failed`, `Finishing of <id> runs once more`, `Finishing of <id> failed again;
  its job waits for the next start`, `Finishing of <id> was cancelled from inside`, `Finishing could
  not store how <id> ended; its job waits for the next start`, and `Finishing stopped on an error;
  the queue is taken up again at the next hand-off or start`. Since 0.7.4 an error counts as well
  as an exception. `Finishing: <step> of <id> failed` also comes with an error of code that could
  not be loaded or set up (ExceptionInInitializerError, NoClassDefFoundError) or of memory
  (OutOfMemoryError), and the next step still runs; on 2026-10-19 such an error out of the to-dos
  step ended the queue's worker and left the recording in "Finishing up". A run that fails on an
  error is run once more, and one that fails on an error again ends its job with what its steps
  made, as it would fail so in every process: `Finishing of <id> failed again on <error's class>;
  its job ends with what it made`. A cancel is never a step that failed. A run stopped from outside
  its steps (cancelled, its keep-alive lost, its job gone, a write of its job failed) whose work
  then throws something other than a cancel logs `Finishing of <id> threw as it was stopped`, and
  ends as that stop would: a cancelled job goes and never runs again (since 0.7.4's refix; before,
  it was a failed run, run once more). An error as the queue frees the model ends that drain as `Finishing stopped on an
  error` does, and the queue lets go of the AI work gate first (before, it kept it, and every later
  job waited behind it). Also, each with its exception:
  - `Finishing could not store the capture of <id>`: a crash during that recording won't be
    recovered, though its hand-off still queues it;
  - `Finishing could not queue <id>` (then `Finishing has no job for <id>`) and `Finishing could
    not delete the job of <id>` (a cancel or a delete);
  - `Finishing could not take up the queue` (the resume after a process death);
  - `Finishing queue could not be read; reading it again in <ms> ms` (what screens show, read again
    after 1 s, doubling up to 60 s) and `Thermal state could not be read`;
  - `Finishing could not register the live model`, `Finishing could not free the model` and
    `Finishing could not forget the model`;
  - `Finishing could not read the length of <id>` and `Finishing could not store the length of
    <id>` (a recording cut off, whose length comes from its audio);
  - `Finishing failed`: anything else the queue's background work threw, which ends no app.

`LlmRepositoryImpl` adds `W Not loading <file>: <n> MB available, <m> MB needed (the file is <k>
MB)` when Gemma 4 E2B doesn't fit, at each of the finisher's tries and for Rewrite notes, Find again
and the map (`<m>` is the file plus 600 MB for each of them), and
`Stored model forgotten` (or `forgetModel called during a model load, keeping the model stored`) as
the queue lets go of the model.

These lines replace MainViewModel's `Finishing: <step> after ... ms`, `Finishing done in ... ms
(discarded=..., failed=...)`, `Finishing on ...`, `Better transcript after Stop by ...` and
`Streaming stopped, snapshotLen=...`, which are gone with the recorder's own finishing.

### The recorder and the quick key

`MainViewModel`:

- `Stop: last words saved after <ms> ms, handed off after <ms> ms (exit=HOME)`, one line per Stop;
  `deleted` in place of `handed off` for a recording in which nothing was said
  (`exit=NOTHING_SAID`). `exit=BACK` is a Back pressed while it saved, `exit=REPLACED` a quick-key
  press that started the next recording meanwhile.
- `Recording <id> handed to the finisher (recognizerFailed=<true|false>)`: true when a capture of
  this recorder failed in any way but a lost microphone, or was cut off before what it recorded was
  transcribed. `E Error handing recording <id> to the finisher` when the hand-off failed.
- `W Capture did not end within 15 s (state=<IDLE|OPENING|LISTENING|TRANSCRIBING>)`.
- `Recording <id> switched to <category id> (<mode>) at <ms> ms` (since 0.5.3): "Record as" from
  the label at the top switched the recording, `<ms>` into it, to the category with that id (a
  starter's `voice-note`, `meeting`, `idea`, `todo`, else a UUID; never its name) and its mode
  (`SIMPLE_LISTENING`, `SHORT_MEETING`, `LONG_MEETING`). Its live notes follow that mode from their
  next interval and the finisher writes the last ones in it. The capture goes on untouched: no
  `Capture start`, `Speech model ready` or speech summary line follows a switch until Stop.
  `W Recording <id> not switched to <category id>: it or the category is gone, or
  translates`, or `E Recording <id> not switched to <category id>` with its exception: nothing
  changed, and the recorder said so. Over the lock screen the chip opens only on a hold of half a
  second, as the grille stops a recording there, and a tap shows "Hold to change" ("Zum Ändern
  gedrückt halten"). The switch is stored off the main thread, and the hand-off waits up to 2 s for
  it, also when Android closes the recorder just after the pick: `W Recording <id> handed to the
  finisher before its switch to another category or language was stored` when it waited in vain.
- `Session config: mode=<mode>, language=<ENGLISH_OR_GERMAN|MALAYALAM>, ...` as the recorder opens
  (`language` since 0.6.0): the language the recording is made in, whose live speech model the
  capture loads (`SherpaOnnxDataSource` names it in `Speech model ready ... (<model id>)`).
- `Recording <id> switched to <ENGLISH_OR_GERMAN|MALAYALAM> (<live model id>) at <ms> ms` (since
  0.6.0): the language row of "Record as" switched the recording's language, `<ms>` into it. It is
  stored with its live model as the transcript's; the capture changes its model between two
  utterances (`Live speech model switched to ...`, above), and live notes stop while it is in
  Malayalam and go on once it is back. `W Recording <id> not switched to <language>: it is gone or
  translates, or the model isn't on the phone`, or `E Recording <id> not switched to <language>`
  with its exception: nothing changed, and the recorder said so. When the capture couldn't load the
  new model: `W Recording <id>: the speech model of <language> didn't load, it goes on in
  <language>`, the switch is taken back and stored again (`E Recording <id> stays stored in
  <language> after its speech model didn't load (<outcome>)` if that store failed).
- `Live notes wait for the finisher` and `Live notes go on`, while recording, as the finishing of an
  older recording takes the AI and lets go of it; `AI busy (<holder>), the live model is registered
  once it is free` when the recorder opens meanwhile, and `W Live model not registered again`, with
  its exception, when that registration failed.
- As the recorder closes: `The recorder's own AI work frees the LLM as it ends`, or `AI busy
  (<holder>), its holder frees the model`, then `LLM resources released on session end` once it is
  freed (`E Error releasing LLM resources as the recorder closed` if that failed).
- Errors, each with its stack trace: `Error stopping STT at Stop`, `Error releasing STT model at
  Stop`, `Error closing the recording on leaving`, `Error closing the recording in onCleared`.
- Translate (since 0.6.0): `Session config: mode=REAL_TIME_TRANSLATION, ..., output=<en|de>`. The
  status line says which language goes in and which comes out ("German into English", "Into
  English" until the app is sure of the German), and "Already English: nothing to translate" when
  what is said is in the target language. Each live translation reads finished lines only, each
  once, and cites them (on 2026-10-14 each read the line being said too, and that line again once
  finished, so sentences came twice); the one after Stop reads the lines none cites. A translation
  loses quotes the model wrapped around all of it. The inner screen opens on the Translation tab,
  and the cover shows the newest translation under the lines it came from. Its to-dos are written
  in the target language, from what was said, without the spotter (`Finishing <id>: transcript in
  de, recorded in ENGLISH_OR_GERMAN, notes in en`), and the language that was spoken goes into the
  recording's `input_language` (it held "it", an old default, for every recording before).

`SatoriQuickKey`: a press while the recorder saves is `Press while SAVING: START`. The start waits
for the hand-off, then says `Recording saved after <ms> ms: START`, or `W Recorder still
<activity> after <ms> ms: SHOW` after 16 s.

### Services and cues

- `RecordingService`: `Buzz START|STOP|FAILED, <ms> ms after the service started`; `Stop for
  registration <n> ignored, recording registration <m>` (a stop meant for an older recording);
  `A newer command is on its way, the service stays for it`; `W Stop request not handled within 30
  s; stopping the service` (it was 120 s); `W Vibration setting not read within <ms> ms of the
  stop, no buzz for the failed start`.
- `RecordingServiceCtrl`: `Registered transcription flow <n>` (it was `Registering transcription
  flow`) and `Stopping recording service (registration <n>)`.
- `LlmProcessingController`: `Starting LLM processing service for <holder> (start <g>)`, `LLM
  processing service in the foreground (start <g>)`, `Stopping LLM processing service (start
  <g>)`. A holder is `finish:<id>`, `rewrite:<id>`, `transcribe:<id>`, `todos:<id>`, `map` or
  `map-older`.
- `LlmProcessingService`: `Starting LLM processing foreground service (start <g>)`, `Stop of start
  <g> ignored, a newer start holds the service`, `A newer command is on its way, the service stays
  for it`, and `W Foreground service refused, working without it`.

### The first run and home's offers

`SatoriFirstRun` (logcat only; ids, parts, states, MB and ms, never text), from the first run's
coordinator unless named:
- Stages: `stage=DOWNLOADING from=GET_READY malayalam=<bool> mobile=<bool>` at the Download tap,
  then `stage=READY from=DOWNLOADING ms=<n>` and `stage=DONE from=READY ms=<n>` at the first
  recording saved; `stage=GET_READY from=<stage> reason=speech_gone` when the speech files went
  after the setup; `looked_around`; `recording_saved ignored stage=<stage>` for a recording said to
  be saved before Ready (a capture the app's death cut off before a reset).
- A part (`speech`, `malayalam`, `ai_model`, `bigger_ai_model`, `reader`): `part=<part> state=RUNNING
  bytes=<n>/<m> MB` as it starts and at every tenth, with `rate_kbps=<n>` from the second; `state=
  VERIFYING bytes=<n>/<m> MB` as a file's check starts (no RUNNING line after the last one);
  `state=DONE`; `state=PAUSED reason=USER|RECORDING|NO_NETWORK|WIFI_ONLY|BLOCKED|NOT_ENOUGH_STORAGE|SERVER`;
  `state=FAILED reason=NO_CONNECTION|BLOCKED|SERVER|NOT_ENOUGH_STORAGE|OTHER|check`. Two failed
  connects in a row on a validated network read as `BLOCKED` (a download's connect gives up after
  5 s, a stalled read after 15 s), a socket Android refuses (EPERM) at once.
- Going on: `resume source=user` (Continue), `resume part=<part> source=try_again`, `resume
  source=network part=<part> bytes=<n> MB`, `resume source=retry after_ms=<n>`, and as the part's
  download starts again with bytes on the phone `resume part=<part> bytes=<n> MB
  source=app_start|user|network|retry` (only `app_start` also shows "Resuming from"). A recording
  holds a running part: `part=<part> state=PAUSED reason=RECORDING`, then 3 s after its end `resume
  part=<part> source=recording_saved` (AndroidDownloadManager says `Downloads held while recording`
  and `Downloads go on after the recording`).
- `model_folder_changed part=<part> ready=true`: files of a part that came another way (by hand,
  Settings) are on the phone and checked; a part whose own download ended says `state=DONE`.
- `mobile_data part=<part>`, `bigger_model planned`.
- The reader (0.8.0, design R7): Download with Malayalam's switch on and AI notes on plans it after
  the AI model; an install that has the Malayalam model, AI notes on and no reader gets it at the
  start, as AI notes are switched on, and as the Malayalam model lands (the first run's download or
  Settings'): `part=reader planned`, or `part=reader planned again` for one done whose files went
  since. Its files on the phone count once whole and each found once with its pinned SHA-256: files
  the debug tools fetched before 0.8.0-dev3 are read once (ModelDownloadManager below), then
  `model_folder_changed part=reader ready=true`, and nothing downloads. That read never runs while
  a recorder starts, records or saves; a download of it that ends then waits for it: `part=reader
  state=VERIFYING reason=RECORDING`, and the next look reads it. It downloads only while AI notes
  are on, over Wi-Fi unless Use mobile data was tapped on the strip, which names it "AI model"; two
  damaged files or server errors drop it alone (`part=reader state=FAILED ...`), and home's strip
  then offers the Malayalam strip's Try again for it, once (`SatoriNudge` stores `reader_retried`).
  ModelDownloadManager: `Reader <id>: <n> of 11 files found the pinned ones in <ms> ms`, `Reader
  <id>: the check of its files waits for a recorder, <n> unread`, `W Reader <id>: <file> is not the
  pinned file, its SHA-256 differs` (the first run then discards the build and downloads it), `W
  Reader <id>: <file> could not be read for its check (<exception class>)`; the record of the files
  found is `.verified` in the build's folder, names, sizes, times and hashes only. AndroidDownloadManager:
  `Starting the reader download of <id>`, `Reader download of <id> waits for the recording`, and the
  hold lines name the reader too (`Downloads held while recording: speech <id>, AI <variant>, reader
  <id>`).
- NetworkMonitor: `network connected=<bool> validated=<bool> metered=<bool>` at each change (from
  the default network's capabilities, so a VPN reads right).
- The screens: `Tap shielded: <ms> ms of the shield left`. FirstRunCues: `cue=SETUP_DONE|NOTES_READY
  shown=true|false reason=<why>` and `notification=NOTES_READY shown=...`. A tap on "Your notes are
  ready" opens that recording's page (since 0.7.1; before, home): `Notes ready tapped for <id>: its
  page opens`, or `...: a recorder is busy, nothing opens` while one starts, records or saves.
- Since 0.7.2 a notification that couldn't be posted for want of the permission is counted (in
  `reminder_prompt`, `notes_missed`, beside the shared "asked" flag) and the line says the count:
  `notification=NOTES_READY shown=false reason=NOT_ALLOWED missed=<n>`. The other reasons
  (`IN_FRONT`, `RECORDING`) count nothing. A count over 0 makes home's ping due from the first
  recording on (below).
- The debug hooks: `First run reset: ...` (it gives the `pm revoke` commands, as Android may keep
  the permissions), `Model source: <url>, <n> files` (since 0.8.0 a reader's file its manifest lists
  takes the manifest's size as well as its hash, so a toy reader of the same names counts on an
  emulator, ~/satori-audit/tools/reading/toy_reader.py; dump-settings says each reader build's
  `model.<id>=<check>[, pinned]`), and on an emulator `notes-ready for <id> in
  <s> s` (`--es action notes-ready [--es session <id>] [--ei after_s <0-600>]`: the notes of that
  recording are ready after the delay, as the finisher says; `W Refusing notes-ready: this is not an
  emulator`, `W notes-ready: after_s must be 0-600`).

`SatoriNudge`: `id=NOTIFY_PING|E2B_OFFER|FIRST_NOTES state=SHOWN|ACCEPTED|DECLINED|GONE`, one line
per change of the ledger. Since 0.7.2 the ping's first showing has a line before its `state=SHOWN`,
saying what made it due: `id=NOTIFY_PING trigger=notes_missed|recordings missed=<n>
recordings=<n>` (`notes_missed`: a notes-ready notification wasn't allowed, so it asks from the
first recording on, and before a first notes' confirmation not yet on show; its strip says so in a
line of its own above the question). After its Allow, Android's window answers with `id=NOTIFY_PING
window allowed=true|false`. A recording opened from home once the first notes exist ends a first
notes' confirmation still to come as well (`id=FIRST_NOTES state=GONE` without a showing), as its
notes have been seen.

### The database file

The database is `databases/satori.db` (with its `-wal` and `-shm` files). The app starts fresh: no
database is carried over from another install, so nothing renames a file before Room opens it, and the
field log has no line about it.

### Lines joined after Stop

`SatoriLines` has one line per finishing Lines step, also when nothing joins (then `a->a`), with
counts and an id only:

```
Lines 26->19 (open 3, lowercase 3, dangling 0, forced 1, continuer 0) in <id>
```

Transcribe again and the better transcript after Stop add `, transcribed again`. `W Lines of <id>
not joined: <exception class>`, with its stack trace, means the step's write failed and the
transcript and its places are as they were. A line in Malayalam script (since 0.6.0) is never
`open`: its speech model writes no punctuation, so only `forced` joins it to the next; a Malayalam
recording's live transcript is read with the live voice detection's maximum, as Parakeet's is.

### To-dos

Since 0.5.3 `SatoriTodos` has one line for every change of a to-do's status, with its id, the
status before and after, and where it came from; never its text:

```
To-do <id>: OPEN -> DONE (home)
To-do <id>: DONE -> OPEN (undo)
To-do <id>: SUGGESTED -> OPEN (page)
To-do <id>: DONE -> DONE (notification)
To-do <id>: OPEN -> gone (home)
```

- The statuses are `SUGGESTED`, `OPEN`, `DONE`, `DISMISSED` and `IDEA`; `gone` is a to-do that
  wasn't there (deleted meanwhile) or that this change deleted. A change that left the status as
  it was is logged too: a second tick, or Done on a reminder whose to-do is done already.
- The source: `home` for home's To do list as it first shows (its first five to-dos), a to-do's
  sheet on home and home's suggestions; `list` for a circle past the first five after Show all;
  `page` for a recording's page (its to-dos, their sheet, its suggestions); `notification` for
  Done on a reminder; `undo` for Undo after a tick or after an idea was deleted; `ideas` for the
  ideas page; `app` for the one-time recheck and move of to-dos an older version found;
  `transcribed again` (since 0.6.1) for a to-do or idea nobody had touched, which the to-dos and
  ideas found again after a Transcribe again in another language took the place of (see
  `SessionDetailsVM`'s `Text again` below); `read again` (since 0.8.0's R9) for one the AI found in
  a Malayalam recording's lines as they were said, which nobody had touched, and which the to-dos
  and ideas found in the readings took the place of (the backfill of readings, under SatoriFinishing).
- On 2026-10-13 no to-do on the owner's phone had ever been ticked off, while ticking worked on the
  emulator: every open one still had the time it was found as its last change. A tap on a circle
  that brings no `OPEN -> DONE` line never reached the app's store.

Since 0.6.0 the search for to-dos and ideas says, under the same tag, how many found items it
dropped before keeping any, and why; counts only. Since 0.7.2 every item it drops is counted:

```
Dropped <n> found items over the answer's limit in <recording id>
Dropped <n> found items that cite no segment in <recording id>
Dropped <n> found items without a text in <recording id>
Dropped <n> found items that are only a time in <recording id>
Dropped <n> found items that are appointments without a usable time in <recording id>
Dropped <n> found items written in another script in <recording id>
Dropped <n> found items that repeat their prompt in <recording id>
Dropped <n> found items that repeat the rules of their prompt in <recording id>
Dropped <n> found items that describe the text in <recording id>
Dropped <n> found items not in their segment in <recording id>
Dropped <n> found items a heading or a greeting in <recording id>
Dropped <n> found items that name no action in <recording id>
Dropped <n> found items without a request or commitment in their Malayalam in <recording id>
Dropped <n> found items without an idea put forward in their Malayalam in <recording id>
Dropped <n> found items that ask a question in <recording id>
Dropped <n> found items too short to name a thing to do in <recording id>
Dropped <n> found items cut off mid-phrase in <recording id>
Dropped <n> found items that copy their sentence without a cue in <recording id>
Dropped <n> found items already noted in <recording id>
Dropped <n> found items the same as another item in <recording id>
```

The lines come in this order, an item counted once, under the first reason that drops it. Since
0.8.0, after them, a reading of lines read into English (a Malayalam recording's, design D9) says
how many of the items it kept cite such lines, and how many of those the gate of Malayalam lines
(`that name no action`, `without a request or commitment in their Malayalam`, `without an idea put
forward in their Malayalam`), which is off for read lines, would have dropped, judged by the whole
of the lines they cite as they were said: counts for V4 to weigh whether that gate drops a to-do
the owner marks real.

```
Kept <k> found items of read lines in <recording id>; the gate of Malayalam lines would have dropped <n> of them
```

The ten
new ones of 0.7.2 close the drops that said nothing. On 2026-10-16 (r071-en-3-warm) the 1B answered
the en1 feed's to-dos with four items whose text was only a time ("the end of the month", "tomorrow
at 10", "on Friday at 3 pm", "this weekend"): all went, rightly, and `Found(count=0)` came with no
line. Now:

- `over the answer's limit`: items past the ten an answer is read for (`TodoPrompt.MAX_ITEMS`).
- `that cite no segment`: the item names no segment of the part it was read from.
- `without a text`: its text is empty, or nothing of it is left once its fillers went.
- `that are only a time`: nothing is left of its text once the time phrases of its segments are
  taken out, whether the model picked that time for it or none. Until 0.7.2 an item that picked no
  time went as `not in their segment` (its time words are no evidence of a thing to do), and one,
  "The end of the month", was kept as a suggestion.
- `that are appointments without a usable time`: an appointment the spotter found whose time was
  over when it was said.
- `that ask a question`, `too short to name a thing to do` (fewer than two words besides fillers,
  and no appointment), `cut off mid-phrase` (it ends on a word that needs more after it: "It had
  been") and `that copy their sentence without a cue`: the gate's drops (`TodoGate`), by its rule.
  A question's "?" rarely survives the cleaning of a text, so that line is rare.
- `already noted`: a repeat of a to-do or idea known already, the recording's own or another one
  still to do, also when the model couldn't load and only the spotter's finds were read.

The six new ones of 0.7.1 follow the evidence of 2026-10-16, when the 1B gave a Malayalam reading
that asked for nothing an open to-do that was the to-do prompt's own order on the copied words, and
a sentence on what the text describes as an open to-do and as an idea:

- `that repeat their prompt`: most of the item's words are words of its call's line of
  instructions (the order to read the Malayalam, the order on the copied words), the same rule as
  for notes (`SatoriNotes` below), or it writes a key of the answer into its text ("Capture:
  explicit", "said: ..."). An English or German part has no such line. An order made only of words
  about writing, letters, answers or notes ("Write the letter", "Answer the letters") is none.
- `that repeat the rules of their prompt` (since 0.7.3): the item gives back the rules of the
  to-do prompt or of its header's own words ("Find the to-dos and the ideas", "Never make up a
  time", "Do not repeat", "Repeat unless a new time is said"), by the rules of `PromptRules`
  (`SatoriNotes` below). The prompt's quoted example to-dos and ideas are no rules, so "Call the
  bank" stays, and an item whose words were said together in its part is the speaker's: an English
  or German item the speaker said in those words stays. A Malayalam part's item, which passes the
  check of its words by the Malayalam words copied for it, is held to them in full; since the review
  of 0.7.3 a to-do of everyday words stays there too ("Fill in the form", "Make a list", "Change the
  names on the list", "Set a time for the visit"), as a rule phrase of one everyday word is one only
  as written and a rule sentence's words only in its order.
- `that describe the text`: "The text describes ...", "The speaker talks about ...", "Der Text
  handelt von ...", "Es geht um ...", in any part: what the text is about, not a thing to do. Also
  without the subject ("Describes a sad story ...", "Tells the story of ...", "About a mother
  ...") and a story named with what it is about ("A poignant story about ...", "Story of ...").
  After a subject that is also a noun of a to-do ("recording", "book") the verb must be followed by
  what it reports, so "Recording states shown on the cover screen" and "Book talks with the
  publisher" stay.
- `that name no action`: a to-do of a Malayalam line (no English or German cue can be read there)
  whose English has no order ("Call the bank", "Hand over the papers"), no modal verb phrase
  ("Mother needs to see a doctor"), no request to be reminded and no time with a verb. A verb of
  the third person ("Describes ...", "Tells ...") is no order. An appointment needs none: "Doctor's
  appointment" names what is on.
- `without a request or commitment in their Malayalam`: a to-do of a Malayalam line that the live
  model wrote, not Gemma 4 E2B, whose copied words' clause in the line (from the comma or full stop
  before them to the one after them, or all of the line when it has none) holds none of the cues of
  `MalayalamTodoCues`: the obligative "-ണം" of a verb (വിളിക്കണം, ചെയ്യണം, വാങ്ങണം, ഇടണം, പൂട്ടണം,
  മാറ്റണം, and for the speaker അയച്ചുതരണം, വാങ്ങിത്തരണം), വേണം, പോണം, the promissive "-ആം"
  (കൊടുക്കാം, ചെയ്യാം; not എല്ലാം, the ordinals, grams or a "may be" such as ആകാം, വന്നേക്കാം), a
  reminder (ഓർമ്മിപ്പിക്കുക, മറക്കരുത്, മറന്നുപോകരുത്), or a day or a time (നാളെ, നാളത്തെ, ഇന്ന്,
  രാവിലെ, വൈകീട്ട്, മണിക്ക് also inside a word, the weekdays) in a clause not told in the past: a
  story's "ഒരു ദിവസം രാവിലെ ... പോയി" (one morning ... went) asks for nothing. The list is the
  owner's to check and extend.
- `without an idea put forward in their Malayalam` (since 0.7.1): an idea of a Malayalam line that
  the live model wrote whose clause puts no idea forward: neither "നന്നായിരിക്കും" (it would be good)
  and its kin ("-ആയിരുന്നെങ്കിൽ", if only it were) nor a request or a promise of the list above.
  On 2026-10-16 the 1B retold a reading as ideas. Gemma 4 E2B's ideas are not held to it.
- `the same as another item`: the same words as an item of the other kind, found in the same
  reading or kept for the recording: the same text is never both a to-do and an idea. Against the
  recording's own the new one goes; in one reading the stronger stays, an open to-do over an idea,
  an idea over a suggestion. Until 0.7.1 both stayed.

- `written in another script` (since 0.6.1 as `written in Malayalam`, for Malayalam script; since
  0.7.3 for any script but the Latin, `WrongScript`, as for notes below): the item's text is mostly
  in another script, a line of the transcript copied as it was said, or Telugu, Bengali and the
  rest; since the review of 0.7.3 also an item with one word of a script its part has no letter of
  ("Buy సాఫ్ట్‌వేర్ today" of a Malayalam part), while a name of up to three words in the part's own
  script stays ("Call രാധ കൃഷ്ണൻ"). Satori writes to-dos and ideas in English or German only, a Malayalam recording's in
  English, and such a copy passes the check of its words. The words the answer copies for an item
  from its segment (`said`) are never held to it: they are in the transcript's own script. On 2026-10-15
  a recording switched to Malayalam while it recorded got its two Malayalam lines as open to-dos
  this way. A part that holds a Malayalam line among lines in Latin letters (those Parakeet wrote
  before a switch) now asks for the copied words too, and tells the model that the text is
  Malayalam and the answer English, as a part all in Malayalam does. Since 0.7.1 it says so as
  orders on one line of instructions, the order on the copied words last ("For said, copy up to 8
  words of the item's segment that say it."): 0.7.0's sentence about the text, and its old order
  on "said", came back as a to-do.
- `not in their segment`: the item's content words are not in the segments it cites, nor in the
  ones their sentences run on into or come from (`TodoGrounding`, at least 40 % of them and one;
  two, or one found as itself or by four or more of its first letters: "big" alone doesn't find
  "bigger"). A German "zu" infinitive counts as the plain one ("einzukaufen" for "Einkaufen").
  These are copies: of the prompt's examples ("Make the clock bigger") or of other recordings'
  items, which on 2026-10-14 an English meeting got four of, each with a time of the meeting. A
  copy is dropped whatever time it has. Where the item's text can't be compared with what it cites
  (segments in Malayalam script, or a part in another language than the to-dos: a Translate
  recording's, a German part of English to-dos) the words are the ones the model copied from the
  segment for the item, in the transcript's own script; they are used for this check only and
  never stored. An item of a line in Latin letters among Malayalam lines is checked by its own
  words (since 0.6.1).
- `a heading or a greeting`: "Second, the budget", "See you next week". An ordinal that is part of
  the item ("Second meeting with Anna", "Third quarter report") makes no heading, nor does one in
  an appointment with its time or in what the spotter found after a cue ("Note to self: second
  dentist appointment on Monday at 10").
- The header's noted list now holds the recording's own items and only those of other recordings
  whose words are in the part read: the others can't be said again in it, and the 1B copied them.

### Notes and names: what an answer loses, and the second try

Since 0.7.1 `SatoriNotes` says how many pieces of a note, and which names, the app left out
because they repeat the instructions of their own call (`PromptEcho`); counts and an id only:

```
Dropped <n> note lines that repeat their prompt in <recording id>
Dropped <n> names that repeat their prompt in <recording id>
```

- The instructions are the lines of the call's user message that are no part of what was said:
  the line that tells the model to read a text in another language ("Instruction, never to be
  repeated in your answer: read the Malayalam below and write in English. ..."), the topic's
  sentence without the topic, and the lead-in "What was said:". Never the recording's own topic.
  An English or German recording's notes have none but under a topic, so this line is rare for
  them. The system prompt has its own rules and line since 0.7.3 (`that repeat the rules of their
  prompt`, below).
- A note line is its title, a point, a summary or an action item; a live note, the last note after
  Stop, each part of a long recording and the merge each say theirs, the whole pass once. A piece is
  left out when three quarters of its words that say something are words of the instructions (the
  words that only speak of a text, its language or its answer, "text", "language", "used",
  "written", and since the review of 0.7.1 of a translation, its audio, speech or content and who
  speaks it, "translated", "audio", "speech", "speaker", don't count, and small words neither), when
  it is only such words and no order of a thing to do ("Write the letter" stays), or when it holds
  four words in a row of one instruction line. On 2026-10-16 the 1B began a Malayalam recording's
  notes with two points made of the note on its language that 0.7.0 sent; "Translated from Malayalam
  to English." and a name "Malayalam Translation" go the same way.
- Since 0.7.2 a piece about a recording's medium is about the subject: "AI writes the notes",
  "Speech to text", "Notes from recordings", "Audio recording and transcription" stay. On
  2026-10-17 11:31 the owner described a voice-notes app in a Malayalam note (it records meetings,
  writes notes and minutes, runs offline); its points about notes and recordings went as echoes of
  the order to read the Malayalam, and its title and name as words of the kind of text. The words of
  the medium ("recording", "recorded", "audio", "speech", "transcript", "notes", "Aufnahme",
  "aufgenommen", "Notizen") still don't count as content, so a piece that only names the language,
  the text or the recording still goes: "Text is Malayalam", "Recorded in Malayalam", "Malayalam
  audio", "Auf Englisch aufgenommen", and one word of the medium alone, "Recording." or "Notes."
  An abbreviation of two capitals counts as content ("AI", "KI", "TV"; not a small word nor
  "EN", "DE", "ML"), and four words in a row of an instruction line make an echo also of only such
  words ("never about this recording, its text or its language").
- Since the review of 0.7.2 a piece of only such words that points at this recording goes too:
  "This is an audio recording", "Transcript of the recording", "Notes from the audio", "A recording
  of speech", "Audio recording", "Notizen der Aufnahme" ("this", or one word of the medium after an
  article, or words of the medium alone that name one). Plural and feature phrasings stay: "Notes
  from recordings", "Voice notes", "Records conversations", "Audio recording and transcription".
  The review found a junk transcript's second answer of such pieces kept as its note, and "Audio
  Recording" as its name. "Voice", "conversation", "Gespräch", "Sprachnotiz" and their kin are
  words of the medium now, and "talks", "talked", "gesprochen" words of the text ("The speaker
  talks in Malayalam", "Auf Englisch gesprochen" go). Words that say nothing was found
  ("unclear", "unknown", "nothing", "none", "mentioned", "unklar", "nichts", "erwähnt") count as no
  word, so "The subject is unclear" is made of the second try's own words and goes, and "Nothing."
  alone goes; "The budget is still unclear" stays. A second try also counts the name of the notes'
  language as a word of its instructions (never sent; the order says "its language"), so "The
  recording is in English" and "Die Aufnahme ist auf Deutsch" go there too. A Malayalam
  recording's name counts "Malayalam" so, also when it is asked from the English notes: "Malayalam
  Audio" is no name, "Malayalam Film Festival" is one.
- A note whose every piece repeats its prompt is no note. A name that does is none.
- The to-dos and ideas left out so are counted under `SatoriTodos`, with the search's other drops.

Since 0.7.2 a recording with enough words for notes (20) ends with a note unless the model gave
nothing usable twice, and with a name or a line saying the default name shows. The other pieces an
answer loses are counted too, a notes call or a name that leaves nothing is asked once more, and
the name's way is one line; counts, ids and an exception's class only:

```
Dropped <n> note titles that name only the kind of text in <recording id>
Dropped <n> note lines with no words in <recording id>
Notes of <recording id>[, part <k> of <n> | , the merge]: no point left in the answer (<why>), asking once more
Notes of <recording id>[...]: the second answer kept, <n> lines
Notes of <recording id>[...]: no note, no point in 2 answers
Notes of <recording id>, part <k> of <n>: no note, no point left in the answer (<why>), and no second try after 2 calls in a row gave none in 2 answers
Dropped <n> names that name only the kind of text in <recording id>
Dropped <n> names that are empty in <recording id>
Name of <recording id>: asking once more, from its transcript
Name of <recording id>: from its notes | from its transcript | the notes' title, after <n> answers
Name of <recording id>: none after <n> answers, the default name shows | the old name stays
Name of <recording id>: none, the model failed (<exception class>); the default name shows | the old name stays
```

- A title of the kind of text is one of only such words ("Audio Transcription Summary", "Voice
  Notes"), or one with a word left once its kind word went ("Release Notes"). A title or a name in
  the words of what it names stays, generic or not: two or more words that say something, each said
  in the note's points or in the text the name was asked from or the recording's notes ("Meeting
  Notes & Minutes" for notes about recording meetings and writing minutes; never for a budget
  meeting's notes, which don't say "notes"). A note's title is read after its points for that.
  Since the review of 0.7.2 such a name is a weak one: an ordinary meeting's notes say "meeting",
  "notes" and "minutes" too, and 0.7.2 named one "Meeting Minutes" where 0.7.1 took its note's title
  "Q3 Budget Review". So a note title the rules keep alone goes before it, with `Dropped 1 names
  that name only the kind of text` and `Name of <id>: the notes' title, after <n> answers`; with no
  such title the weak name is the name (the 11:31 note, whose note has no title). And a title made
  only of the words the name's prompt forbids ("audio", "recording", "transcript", "transcription",
  "summary", "analysis", "request", "text", and German "Aufnahme", "Transkript",
  "Zusammenfassung", ...) is never in the words of what it names: "Audio Recording" over a junk
  note's "This is an audio recording." stays a name of the kind of text.
- A note can keep its points and lose its title, as the 11:31 note did: a title of the kind of text
  whose words the points don't all say goes, and nothing asks for a title again or copies the name
  in as one. The note is its points, with `Dropped 1 note titles that name only the kind of text`.
- A line with no words is a blank point or the prompt's "..." copied.
- No point left (`<why>`: `it was blank`, `no point in full` for an answer cut off before its first
  point ended or one without any, `every point left out` when they all repeated the prompt or held
  no word): the call is asked once more, on the model in memory (the live model or Gemma 4 E2B, as
  the step has it; no load), in the same step and with the same pacing and counts, with one more
  order at the end of the user message's line of instructions ("... Write what is said about the
  subject, never about this recording, its text or its language.", in German "Festhalten, was über
  das Thema gesagt wird, nie etwas über diese Aufnahme, ihren Text oder ihre Sprache."). The system
  prompt, and its cached state, stay as they were. The second answer is kept when it has a point;
  the answers are never mixed. With no point in either the call gives no note, also none of a
  title alone (until 0.7.2 such a note was stored with no text). Each part of a long recording and
  its merge are asked once more on their own; a merge with no point leaves the parts as the notes.
  Once 2 calls of a pass in a row gave no point in either answer, a part or the merge that leaves
  none is not asked again (`no second try`): on a long transcript the wrong speech model made junk
  of, every part asked twice would about double the step. A call with a point starts the count
  again. A model that fails is not asked again. A translation is never asked again: nothing of it
  is filtered.
- The name: the model's answer from the notes (or from the transcript when no note has text), then,
  when that is no name and the recording has 20 words or more, one more answer from the transcript
  (with the notes as the words a name may be made of), then the first note title that names
  something (one the rules keep alone first), then none. None leaves the default name on the
  screens (the category's or the kind's); it is never stored, as a stored name is the owner's. A
  model that fails is not asked again: the notes' title stands in, or the `failed` line comes, with
  the finisher's `W Finishing: Name of <id> failed`. Make text again and Rewrite notes name and
  write the same way; Make text again, which replaces the name the old transcript gave, keeps that
  name when it finds none, and its line ends `the old name stays`.

### Satori's rules, other scripts and repeats (since 0.7.3)

On 2026-10-18 and 19 Gemma 3 1B, reading the owner's Malayalam recordings it mostly doesn't
understand, wrote three kinds of junk into notes, names and titles: Satori's notes prompt read back
as points ("Plain statements", "First person", "One idea per point", "No filler", "Keep it as it is
unless the sentence leaves no doubt"), titles and points in Telugu script (one mixed with Bengali
and Hebrew letters), and one made-up subject said again and again with small changes. Since 0.7.3
each goes before anything is stored, and `SatoriNotes` (notes, names, the map) and `SatoriTodos`
(to-dos and ideas) count it; counts and ids only:

```
Dropped <n> note lines that repeat the rules of their prompt in <recording id>
Dropped <n> note lines in another script in <recording id>
Dropped <n> note lines that repeat an earlier line of their note in <recording id>
Dropped <n> note lines that repeat an earlier note in <recording id>
Dropped <n> notes that repeat an earlier note in <recording id>
Dropped <n> names that repeat the rules of their prompt in <recording id>
Dropped <n> names in another script in <recording id>
Dropped <n> note titles for the name that repeat the rules of their prompt | in another script | that repeat their prompt in <recording id>
Dropped <n> map topics that repeat the rules of their prompt in <recording id>
Dropped <n> map topics in another script in <recording id>
Dropped <n> map points that repeat the rules of their prompt in <recording id>
Dropped <n> map points in another script in <recording id>
Dropped <n> summary sentences that repeat the rules of their prompt in theme <theme id>
Dropped <n> summary sentences in another script in theme <theme id>
```

The to-dos' two lines are `written in another script` and `that repeat the rules of their prompt`
(To-dos, above). Each piece is counted once, under the first reason that drops it: its script, the
instruction lines of its user message (`that repeat their prompt`, as since 0.7.1), the rules of its
system prompt, then, for a note's points and action items, the repeats. A note's line counts its
title, points, summary and action items, as the echoes' line does; the live notes say theirs per
note (and since 0.7.3 also their titles of the kind of text and their lines with no words), the
last note after Stop and the whole pass once each. `note lines that repeat an earlier note` (since
the review of 0.7.3) counts the pieces a note lost because an earlier note of the recording says
them, the note itself kept for what is new (below); `note titles for the name` counts the titles of
a recording's notes, stored by an earlier version, that were passed over when they were looked at
for its name, the notes keeping them.

- **The rules of the system prompt** (`PromptRules`). Every AI call's pieces are held to the
  sentences of its own system prompt (the to-dos' also to their header's fixed words): the notes
  prompts for notes and live notes, the name's for names, the to-do prompt for to-dos and ideas, the
  topics and summary prompts for the map. The JSON lines and what is quoted (examples and words not
  to use) are no rules, so "Remind me to call the bank" stays a to-do. A note's title and a name are
  held to them as the model wrote them as well as once cleaned (since the review of 0.7.3): "Goal:
  Provide English notes" goes, though "Goal: Provide", what is left once the kind of text goes from
  its end, says nothing, and "Short Plain Notes" is no name "Short Plain". A piece goes when:
  - it is made of rule phrases only, in any order and case: the phrases of the English and German
    prompts that are only rules ("one idea per point", "plain statements", "first person", "no
    filler", "leaves no doubt", "subject line of an email", "ein Gedanke pro Punkt", "schlichte
    Aussagen", "Ich-Form", "keine Füllwörter", "zweifelsfrei", ...), the ways the 1B says them
    ("keep it concise", "no filler words", "one point per point", "no repetition", "kurz und
    knapp", and since the review "1st person", "short and clear", "nothing extra", "brevity",
    "kurze Notizen", "knapp formuliert", "nichts erfinden"), and any two words of a list of the
    prompts ("Dates and places", "Facts, numbers, plans", "Summary and analysis"). Between them may
    stand small words and the words that go with a rule: of style ("clear", "plain", "single"), of
    the orders and what they say was done ("use", "avoid", "keep", "removed", "kept", "without",
    "entfernt", "verwendet"), the labels of a rule ("Style:", "Tone:", "Perspective:", "Stil:"),
    numbers, the languages, the words of the medium and of the text that name nothing to do
    something with ("Notes in English"), and the parts of a note. So "Fillers removed", "Without
    filler words", "Style: plain statements", "Perspective: first person", "Single idea per point"
    and "Schlichte Aussagen verwendet" go too. A phrase that is one everyday word once its small
    words are aside ("in English", "no lists", "Ich-Form", "at most 6 points", "do not repeat") is
    there only as written, its small words with it in a row, and words are compared as written,
    without their English endings or with a German one, never one as the start of another: a
    Malayalam part's to-dos "Fill in the form", "Make a list", "Stay in Germany", "Follow the
    instructions", "Translate the letter into German", "Answer the letter in German", "Change the
    names on the list" stay (the review found them dropped);
  - or it narrates the making of the answer: nothing but words of a stage of a task ("goal",
    "aim", "initial", "final", "output", "task", and since the review the ordinals and labels the
    1B numbers it with, "first", "last", "next", "key", "main", "erste", "letzte"), of making
    ("provide", "creation", "produced", "completed"), the languages, the parts of a note ("idea",
    "point"), of instructions ("instructions", "instructed", "angewiesen") and the words of the user
    message's instruction lines, saying a stage of the text, the medium or a language ("Aim: Deliver
    notes in English", "First idea: Note creation"), that the text is made in a language ("Note:
    English notes provided", the point 0.7.1 stored for the 28-second note of 2026-10-17, "Deutsche
    Notizen erstellt"), that a stage is done ("Task completed"), a stage of a part of a note ("Final
    idea", "Key point"), a stage of making with nothing made ("Goal: Provide"), what was done of
    the text alone ("Note creation", "Notes created"), an order to make it in a language ("Deliver
    English notes", a Malayalam part's to-do the review found stored), or instructions
    ("Instructions followed", "Instruction to write in English", "Wie angewiesen"); the labels
    "Topic:", "Context:", "Overview:" and "Subject:" start one too ("Topic: voice notes"). What a
    notes app does stays: "Creates notes from recordings", "Notes are generated", "Notes are written
    in English or German";
  - or four fifths of its content words, and three at least, are words of one rule sentence in the
    sentence's order ("Keep names, numbers, dates and places", "Leave out small talk, fillers and
    repeats"); "Set a time for the visit", three words of the to-do prompt's sentence on a visit at
    a set time out of its order, is a to-do (since the review);
  - or it holds five words in a row of one rule sentence, three of them content words ("Keep it as
    it is unless the sentence leaves no doubt");
  - or, since every call now has a system prompt, it is only words of the text and the medium that
    say nothing of a medium ("Recording.", "Notes.", "This is an audio recording."), as 0.7.2 read
    them under the user message's lines, now also for an English or German recording's first call.
  The prompts are long, so a real point that shares a word or two with them stays ("Speech
  recognition misheard the word", "Keep the names and numbers of the suppliers", "Action items for
  the next meeting", "Den Brief schreiben", "Long meeting."): one rule sentence at a time, three
  words at least, in order, the longer run. And a piece whose words the speaker said together is the
  speaker's: its words besides the small ones, those of the medium too, each within two words per
  word of it, and two more, of the others in what the call read ("Plain statements" said as "...
  in plain statements"); one such word with its small words said in a row ("In English"), or alone
  anywhere ("Recording."); German and English forms of a word said count ("einfacher" for
  "Einfache"). What the call read is the part's own text for a part of the pass, the whole recording
  for its merge, the tail for the last note, the transcript for a name and the map, the part for a
  to-do search. Until the review the words could stand anywhere in all of it, which kept "One idea
  per point" in a long English meeting that said "idea" once and "point" once. "Audio recording"
  stays for an English recording about an app that makes one, said so. A Malayalam transcript holds
  no English word, so none of this saves a Malayalam recording's junk.
- **Words of the medium alone** (`PromptEcho`, since 0.7.3): a past participle of the medium says
  what was done to the recording itself ("Voice recording transcribed", "Notes recorded",
  "Aufnahme transkribiert") and goes, as "This is an audio recording" does; the feature phrasings
  stay ("Records meetings", "Audio recording and transcription"). A first word with a colon after
  it is a label, never an order: "Note: Audio transcribed" goes, "Note the date" stays.
- **Another script** (`WrongScript`). The notes, titles, names, to-dos, ideas and the map are asked
  for in English or German, so a piece most of whose words are in another script than the Latin one
  goes: Telugu, Bengali, Hebrew, Devanagari, Tamil, Kannada, Arabic, CJK, Cyrillic, Greek, Thai and
  Malayalam itself. Latin letters with accents and the German umlauts are Latin. Counted by words,
  a word being in the script of most of its letters: a Malayalam word kept in a mostly English point
  (a name, say) is one word, however many letters it has ("Go to വിദ്യാഭ്യാസമന്ത്രാലയം" stays,
  three quarters of its letters Malayalam), half the words stays ("Meet രാധാകൃഷ്ണൻ"), more than
  half goes. Since the review of 0.7.3, where the script of what the call read is known (every call
  but a theme's summary): a word of two letters or more of a script it has no letter of drops the
  piece at once, half Latin as it is ("Bluetooth firmware కోసం update" for a Malayalam recording;
  any Malayalam word for an English one that has none), and one of a single letter is a sign
  ("π"); up to three words in a row of its own script count as one, a name ("Call രാധ കൃഷ്ണൻ"
  stays), more count each. Scripts written without spaces (Han, kana, Thai) count each letter as a
  word. The transcript and the to-dos' copied words are never held to it. A note title in another
  script, stored by an earlier version, never stands in for the name either.
- **A note doesn't repeat itself** (`NoteRepeats`). Within one note, a point that says nothing an
  earlier point of it doesn't goes ("Bluetooth firmware is essential" after "Bluetooth firmware
  is needed"), and so does an action item after an earlier action item: every word of it that says
  something is a word of the earlier one, stemmed, the words that only weigh it aside ("needed",
  "essential", "important", "complex", "task", and since the review "difficult", "challenging",
  "significant", "matters", "wichtig", "benötigt", "schwierig"), and it has two such words or the
  earlier piece's only one. Real lists that share a subject word stay ("Buy milk for the kids",
  "Buy bread for the kids"; "Meeting at 9", "Meeting at 3"), and so does a point that says more
  ("Call Anna about the rent" after "Call Anna"). Since the review numbers, names, abbreviations and
  single letters are compared as written, never one as the start of another ("Pay 1500 euros" and
  "Pay 15000 euros", "Call Paul" and "Call Paula", "Andreas" and "Andrea", "vitamin C" and "vitamin
  D", "the IT team", "Room A" and "Room B", "9 AM" and "9 PM", "the May rent", "one ticket" and
  "two tickets", "before" and "after" the meeting are two), and a point with "not", "no", "never",
  "nicht" or "kein" is never a repeat of one without, or the note would say the opposite of what
  was decided. Within one recording's notes, a later note mostly repeats an earlier one when more
  than half of its pieces (title, points, action items) each repeat a piece of one earlier note: a
  part of the whole pass after the parts before it, the last note after Stop after the live notes,
  a live note after the live notes before it. Since the review such a note loses the pieces an
  earlier note says (a piece of one word too, when an earlier piece has it) and keeps what is new:
  a live note of a meeting that says its subject again with "Anna brings the trailer" keeps that
  point, without its title when the title is the earlier one's (`note lines that repeat an earlier
  note`). With nothing new left it is none (`notes that repeat an earlier note`), and it is not
  asked again: the model said something, the same as before. For a Malayalam recording's pass, as
  the model can't read it, such a part goes whole, new words and all, as 0.7.3 had it for every
  note. The merge of a pass is never held to its parts, as it says them again on purpose. A pass of
  several parts with one note left keeps that note as a part alone (until 0.7.3 the same note was
  stored again as its summary). A live note left out so leaves its lines to the last note after
  Stop, as no note cites them. A made-up subject can't be told from a real one: its first note
  stays, and so does a repeat that adds a word that says something ("Bluetooth firmware requires
  updates" after "Bluetooth firmware is needed"); only what says nothing new goes.
- **The notes' titles for the name** (since the review of 0.7.3). A title of a recording's notes
  that an earlier version stored stands in for the name only when the rules of the notes prompt of
  the recording's kind in the name's language, its script and the line on a Malayalam recording's
  language keep it: "Plain statements", "Goal: Provide", "English Note Creation" and "Like the
  Subject Line of an Email" don't. Those passed over are counted when the titles are looked at:
  when the model's name was none or a weak one.
- **Never empty, still.** A note these rules leave without a point gets the same one more call as
  in 0.7.2 (`Notes of <id>...: no point left in the answer (every point left out), asking once
  more`), and a name they reject goes through the same chain (`Name of <id>: asking once more, from
  its transcript`, the notes' title, the default name). A Malayalam recording whose answers are all
  junk may so end with no note and the default name: an honest empty page beats junk. Nothing on a
  screen changes or says why.

### Older audio

`SatoriRetention` (AudioRetentionJob) for each run of the rules "Compress older audio" and "Delete
older audio":

```
Retention run (daily) starts
Compress <id>: freed 1.7 MB in 2400 ms
Retention run (daily) done in 2600 ms: compressed 1, audio deleted 0, recordings deleted 0, failed 0, skipped in use 0, no longer due 0, freed 1.7 MB
```

`daily` is the job that runs once a day while the phone charges, `at once` the one after a rule
changed. Per file `Compress`, `Delete audio` or `Delete recording`, and `W <action> <id> failed
after <ms> ms; its audio stays` with the exception. `W Retention run (<which>) failed`, `Retention
run stopped by Android (reason <n>); it runs again later`, and from AudioRetentionSchedulerImpl `W
Daily retention run not scheduled[ (result <n>)]` or `W At once retention run not scheduled[
(result <n>)]`. In a debug build DebugToolsActivity's dry run logs `Retention dry run, <N> days
ahead, compress after <D|never>, delete after <D|never>: compress <n> (<x> MB), delete audio <n>
(<x> MB), delete recording <n> (<x> MB)`, then `Retention dry run: <action> <id> (<x> MB)` per
file, and changes nothing (`W Retention dry run: retention_days_ahead needs a number of days, 0 or
more`).

`SatoriAudio` (RecordingAudioStore, CaptureAudioWriter): `Compressed the audio of a recording:
<ms> ms of WAV (<x> MB) as <ms> ms of AAC (<x> MB)`; `W The audio of a recording couldn't be
compressed; its WAV stays` with the reason, such as `The compressed audio plays <ms> ms, its WAV
<ms> ms`; `W The compressed audio of a recording can't be read; ignoring it`; `W What a compression
left couldn't be deleted`; and, for a capture that continues a recording whose audio was
compressed, `W This recording's audio was compressed, which a capture can't add to, so it is
deleted; the recording goes on without its audio`.

### The system prompt cache

The AI engine keeps the encoded state of each system prompt in the app's private
`cache/llm-prompts/<sha256>.bin` (at most 100 MB, the least recently used goes first; the key holds
the model file, the install, the load's llama.cpp settings and the prompt), so a step after a model
load reads the state instead of encoding the prompt again. The Gemma 3 1B defers its system turn to
the first user prompt, so only Gemma 4 E2B uses it. `ai-chat` says, after the native function's
name:

- `System prompt from cache: <n> tokens in <ms> ms`, or `System prompt cache miss (<reason>),
  encoding <n> tokens` (`no file`, `unreadable`, `token mismatch`, `not restored` or `out of
  memory`), and after that encode `System prompt cached: <n> tokens, <k> KB in <ms> ms` or `W
  System prompt cache not saved (<reason>)`.
- `W System prompt cache path not read; encoding without the cache`.

`InferenceEngineImpl` adds `W No system prompt cache: package info unavailable (<class>)`, `W No
system prompt cache for this model: its file changed while it loaded` and `W No system prompt
cache: its directory is unavailable`. Deleting a recording clears the cache, a second safeguard
since the system prompts hold nothing of a recording: `System prompt cache cleared`, or `W System
prompt cache not cleared: some of its files could not be deleted`, and `LlmRepositoryImpl` says `W
System prompt cache not cleared: <class>` when the clear itself failed.

### Each AI call (debug builds)

`LlmRepositoryImpl` gives each call's sizes, never its text, and since 0.7.2 how its reply is
sampled and a hash of what the model reads (a debug line, which a release build strips):

```
=== LLM INFERENCE === threads=auto inputChars=644 systemPromptChars=1009 userPromptChars=644 grammar=json-schema(254 chars) sampling=temp 0.30 top-k 40 top-p 0.95 min-p 0.05 promptHash=6e53c134
Tokens: ~161 input + 768 output budget
```

- `threads` is `auto`, or `2 (conservative)` on a phone found short of memory or for a long text.
  `inputChars` is the text the caller gave, `userPromptChars` the user turn the model reads (with a
  lead-in, a line of instructions, or the quotes of a call without a grammar). `grammar` is `none`
  (free text: the name, a live note), `gbnf(<n> chars)` or `json-schema(<n> chars)`: the size of
  what the reply must follow, never its text.
- `sampling` is how the reply's tokens are picked. Every call of the app's own work has the
  default, temperature 0.3 after top-k 40, top-p 0.95 and min-p 0.05, with no repeat penalty: 0.7.2
  keeps the values every release had, as nothing measured says that greedy decoding (temperature 0,
  always the most likely token) gives better notes, names, to-dos or ideas on these models, both
  Gemma model cards give 1.0, and Rewrite notes and Find to-dos and ideas again need an answer that
  can differ. Only DebugToolsActivity's rerun of a finishing step, with a temperature of its own,
  samples otherwise, and only its own calls: the next call without it, a recording's finishing
  too, has the default again.
- `InferenceEngineImpl` says `Updating sampler config: temp=<t> topK=<k> topP=<p> minP=<m>
  repeatPenalty=1.0`, and `ai-chat` `Sampler updated: ...`, only when the setting changes: each
  model load sets the default natively, so the app's own calls never bring either line. A rerun at
  another temperature brings one as its first call samples (and after each model load), and one
  as the first call after it puts the default back.
- `promptHash`: the first 8 hex digits of the SHA-256 of the system prompt, the user turn and the
  grammar (`gbnf:` or `json-schema:` and its text, nothing without one), each in UTF-8 and ended
  by a zero byte. Two calls with the same hash read exactly the same, so a comparison of reruns can
  tell an answer that differs from a prompt that differs; it holds nothing of the text. It doesn't
  name the model (the model's load line before it does) nor the output budget.
- `PerfLogger`'s `[llm-done] duration=... tokens=... speed=...tok/s` ends the call.

### How the AI models load (every build, since 0.6.1)

Every load of an AI model has its model's settings, a release build's too
(`lib-llama-android/src/main/java/com/arm/aichat/internal/LoadSettings.kt`, `ModelDefaults`): on
2026-10-15 a Rewrite after Transcribe again loaded Gemma 3 1B with 0.6.0's settings (llama.cpp's own,
nothing paced) and the low-memory killer ended 14 cached apps in 0.6 s ("device is in direct
reclaim"), while the memory session's run 4 with these settings, from 3,361 MB available, ended none
and caused no direct reclaim (lowest MemFree 226 MB, load 14.7 s, 19.0 tokens/s).

| Model (its file) | Loaded for | Settings |
|---|---|---|
| Gemma 3 1B (`gemma-3-1b-it-Q8_0.gguf`) | the live notes, live Translate, and the work after Stop, Rewrite notes, Find again and the map without E2B | the fast rung, run 4's: the file read in at 120 MB/s first, the layers' matrices copied into KleidiAI's layout at 120 MB/s with the tied output left in the mapping, `swa_full` off, a q8_0 KV cache (flash attention on), one output a batch; since 0.7.2 the copied matrices' pages given back once the copy is done (`dropcopied`, below) |
| Gemma 4 E2B (`gemma-4-E2B_q4_0-it.gguf`) | the work after Stop, Rewrite notes, Find again and the map | the same, run 3's, not measured on E2B yet |
| any other file (Gemma 3 1B IQ4_NL, Qwen 3.5 0.8B) | the live notes when chosen | the file read in and the weights copied at 120 MB/s, and the copied weights' pages given back (since 0.7.2), llama.cpp's own settings otherwise; no tied output mapped, as another file's matrices may be of a type KleidiAI doesn't take |

Both paces wait while MemFree is under the pacing floor until it is back at its resume level (150
and 200 MB on the razr, lowered once a wait finds the kernel not reclaiming, since 0.7.2: `The floor
of the paced waits`, below), and once a load has waited 10 s in all, its pre-read's and its copy's
waits together, it stops (`Loads one at a time, at a pace`, below): Gemma 4 E2B's work then falls back to the live model as for any load that fails. A
stopped load of the live model fails as a load stopped for memory: the work after Stop runs again
from that step a minute later (`SatoriFinishing`, above), the page's work reports its error and
leaves what was there. An AI model's file its pre-read can't read stops its load the same way,
as llama.cpp would read it in one burst (since the 0.6.1 refix). The memory session's run 1 (no reclaim at 120 MB/s)
set the pace. Each load takes the one-load gate, with the recorder's own load first (below). Since
0.7.1 the speech models load at a pace too, their files read in at 120 MB/s, with ONNX Runtime's
prepacking off (`Loads one at a time, at a pace` and `ONNX Runtime's options for the speech models`,
below).

Gemma 4 E2B's settings are one entry, `ModelDefaults.GEMMA4_E2B` in `LoadSettings.kt`. Should run 3
(E2B with these settings) end apps or reclaim directly, change its `LoadSettings.FAST` to
`LoadSettings.LEAN`, run 6's lean rung: repack off, so nothing is copied or paced, with the same
pre-read and context (about 0.15-0.2 GB of anonymous memory instead of about 1.1 GB, the prompt an
estimated 1.5-3.5 times slower), and the expectation in `LoadSettingsTest` with it.

As each AI model load begins `InferenceEngineImpl` says with which settings, in every build (the
model's file and its settings, no text):

```
Load settings of gemma-3-1b-it-Q8_0.gguf (Gemma 3 1B's): pre-read 120 MB/s, llama.cpp repack=on mmap=on directio=off swa_full=off kv=q8_0 fa=on ctx=4096 outputs=1 tiedout=mapped copypace=120 dropcopied=on
Load settings of gemma-4-E2B_q4_0-it.gguf (Gemma 4 E2B's; debug properties: repack, cold): pre-read 120 MB/s, llama.cpp repack=off mmap=on directio=off swa_full=off kv=q8_0 fa=on ctx=4096 outputs=1 copypace=120 cold=on
Load settings of Qwen3.5-0.8B-Q8_0.gguf (any other model's): pre-read 120 MB/s, llama.cpp repack=on mmap=on directio=off swa_full=on kv=f16 fa=auto ctx=4096 copypace=120 dropcopied=on
```

`debug properties:` names the settings a debug build's memtest or armed window changed from the
model's (below), by memtest.sh's keys (`load.pace` for the pre-read); `pre-read off` for none. The
llama.cpp part is the memory line's and `Model loaded in`'s. With `swa_full` off the KV cache can't
be rewound to the end of a system prompt, so Gemma 4 E2B's prompts come from their cache files
(`The system prompt cache`, above) or are encoded again; the 1B defers its system turn anyway.

### What a model load costs the memory (debug builds)

Since 0.5.2 a debug build, the one the phone runs, logs a memory line for each load and release of
a model, under the tag of the code that does it: `LlmRepositoryImpl` for Gemma 4 E2B and the live
AI model, `SherpaOnnxDataSource` for the speech models (the live model, and each whole-file run's
own). A release build reads and logs none of this. One line comes as the load or release ends, the
other 10 s later (the figures here are made up):

```
Memory: load of gemma-4-E2B_q4_0-it.gguf took 5092 ms; MemAvailable 4997 MB before, 4188 MB after; RssAnon 164 -> 1631 MB, RssFile 128 -> 3298 MB; PSI some avg10 0.00 -> 9.87, full avg10 0.00 -> 4.32
Memory: 10 s after the load of gemma-4-E2B_q4_0-it.gguf: MemAvailable 4103 MB; RssAnon 1672 MB, RssFile 3298 MB; PSI some avg10 3.10, full avg10 1.05
Memory: load of parakeet-tdt-0.6b-v3-int8 took 7980 ms; MemAvailable 3164 MB before, 2410 MB after; RssAnon 57 -> 742 MB (peak 760 MB), RssFile 148 -> 164 MB; PSI unreadable
```

- `load of` or `release of` the model's file (AI) or id (speech); `failed after` in place of
  `took` for a load that failed, and `stopped after` for a recording's speech model whose load
  stopped before the model loaded, as the recording was switched to the other language while its
  files were read in (since 0.7.1). The live speech model is the recording's language's
  (`parakeet-tdt-0.6b-v3-int8`, or `indicconformer-ml-int8` for Malayalam), and a switch of the
  language while recording has a release of the one followed by a load of the other. An AI load is timed as `LlmRepositoryImpl` asks for it: llama.cpp's
  load and context, and the system prompt the load sets (from the cache or encoded).
- `MemAvailable` is the kernel's (`/proc/meminfo`), what the low-memory killer works from; the
  app's bars use ActivityManager's `availMem`, which follows it closely.
- `RssAnon` and `RssFile` are Satori's own memory and the pages of the files it has mapped (a GGUF
  loaded with mmap), from `/proc/self/status`. A speech model's load (since 0.7.1, live and for a
  file) adds `(peak <n> MB)` after RssAnon: the highest the process had while it loaded, read
  every 25 ms from `/proc/self/status` alone, and before and after. On 2026-10-13 Parakeet v3's
  start peaked at 1,255 MB and settled at 748, which the figures before and after don't show; the
  plan expects the peak at about 750 MB with prepacking off.
- `PSI` is the kernel's memory pressure (`/proc/pressure/memory`): `some` is the share of the last
  10 s in which at least one task stalled on memory, `full` the share in which all did. It says
  `PSI unreadable` where the phone doesn't let an app read the file.
- `n/a` for a figure that couldn't be read. The voice detector's model (2 MB) has no line.
- A speech model's release (since 0.7.2; the live model's and each whole-file run's own) purges the
  native heap after the free, in every build: `mallopt(M_PURGE_ALL)` (API 34 and later), else
  `mallopt(M_PURGE)`, one JNI call in `libai-chat.so`. Parakeet v3's release had freed only part of
  the model at once on the razr (RssAnon 880 -> 658 MB, the native heap in use 31 MB), the rest
  coming back 8-11 s later with the 1B's load. Its line gives RssAnon before, after the free and after
  the purge, and `took` covers both:
  `Memory: release of parakeet-tdt-0.6b-v3-int8 took 131 ms; MemAvailable 2269 MB before, 3030 MB
  after; RssAnon 880 -> 658 MB freed -> 152 MB purged (M_PURGE_ALL, 46 ms), RssFile 138 -> 138 MB; PSI
  unreadable`. With a debug build's `debug.satori.mem.purge` off (read at each release; empty or on is
  on, a release build never reads it) it is `RssAnon 880 -> 658 MB freed, not purged
  (debug.satori.mem.purge=off)`, and RssAnon should fall only 8-11 s later, as in 0.7.1; `freed, not
  purged (neither M_PURGE_ALL nor M_PURGE took)` comes with `W Native heap not purged after the release
  of <model>: mallopt knows neither M_PURGE_ALL nor M_PURGE` (or `: <exception class>`, tag
  `SatoriLoads`, every build). The purge runs before the recognizer counts as out of memory, so the
  finisher's wait for it and a language switch's next load come after it. It holds the allocator's
  locks while it runs (about 40-55 ms with 500 MB to give back, on the emulator): the release runs on
  IO after the last decode, and a switch while recording only delays the next decode, never the
  microphone. The purge is the whole process's, so it can land while another recognizer loads or
  decodes: a whole-file run that a new recording's capture skips releases its recognizer while the
  capture's live model loads, and the live model's release when the app's screen goes can come
  during a whole-file run. It touches only freed memory, so the other one goes on, a few tens of ms
  later at most, and the run's memory is back before the new model's parse. A purge only gives
  back the allocator's free memory: should the line still show about
  650 MB after it, the memory is elsewhere (ART's heap, a mapping of ONNX Runtime's), and `adb shell
  run-as com.satori cat /proc/<pid>/smaps` right after the release, summed by mapping, says which.
  `W Ignoring debug.satori.mem.purge=<value>: not on or off` for a value it doesn't know.
- An AI load's line ends with how llama.cpp loaded it, the settings below, e.g. `; llama.cpp
  repack=on mmap=on directio=off swa_full=off kv=q8_0 fa=on ctx=4096 outputs=1 tiedout=mapped
  copypace=120 dropcopied=on` (Gemma 3 1B's and Gemma 4 E2B's own, above, every load's but a memtest's that
  changes them). `InferenceEngineImpl` says the same after the native load and context alone:
  `Model loaded in <ms> ms, llama.cpp ...`. The line is read after the whole load, so its RssFile
  after is the figure after the drop of the copied pages (`dropcopied`, below): about 450 MB for
  Gemma 3 1B since 0.7.2, where 0.7.1 had 1,151 MB (r071-en-3-warm).

A debug build's memtest (below) reads the settings from system properties for its own load, over
its model's, so that what each one does to a load's memory can be measured on the phone
(`~/satori-audit/tools/memtest.sh`): the llama.cpp ones in the table and `debug.satori.load.pace`
for the pre-read (`Loads one at a time, at a pace`, below). An empty or unset property is the
model's setting; a value it doesn't know is ignored with `W Ignoring debug.satori.llm.<name>=<value>:
not ...` (or `W Ignoring debug.satori.load.pace=<value>: not off or 10-1000 (MB/s)`) from
`InferenceEngineImpl`, and that setting stays the model's. Every other load, the app's own work in a
debug build too, has its model's settings and reads no property (since the 0.5.2 review fixes), so a
property left set after a memtest, say by a memtest.sh that lost the phone, changes nothing in daily
use. A release build reads none of them. The settings added since 0.5.2 (from `outputs` down) are
named in the lines only when they aren't llama.cpp's own, so 0.6.0's line is as it was, and
`tiedout=mapped` only with repack on, where it applies. A memtest with no property set has its
model's settings, so memtest.sh's `today` is the fast rung since 0.6.1; 0.6.0's settings, which no run
needs (their kills are measured), are `loadpace=off,copypace=off,tiedout=repack,swa_full=on,kv=f16,outputs=512,dropcopied=off`
in memtest.sh's keys (512 outputs reserve what llama.cpp's own batch did).

For a whole recording measured with a variant (the memory session's run 5: the live AI model's
loads during the recording and Gemma 4 E2B's after Stop, the app's own work), `adb shell am start
-n com.satori/com.satori.ui.debug.DebugToolsActivity --es action armloads --es
minutes <1-60>` makes every AI model load take the properties over its model's settings for that
long; `--es minutes 0` ends it early. `DebugTools` says `Debug load settings armed for every AI model load for <n> min`, `Debug
load settings no longer armed`, or `W Arm loads: minutes must be 0-60, not <value>`. The window is
held in memory only: it ends by itself, or with the process, so a property left set afterwards
changes nothing in daily use. Clear the properties after the run all the same.

Each property's empty value is the model's setting (`How the AI models load`, above): for Gemma 3
1B and Gemma 4 E2B load.pace 120, repack on, mmap on, directio off, swa_full off, kv q8_0, ctx 4096,
outputs 1, tiedout mapped, copypace 120, dropcopied on, populate on, cold off; for any other file llama.cpp's own
(swa_full on, kv f16, the batch's outputs, tiedout repack) with load.pace and copypace 120 and dropcopied on.

| Property | Values | llama.cpp |
|---|---|---|
| `debug.satori.load.pace` | off, 10 to 1000 (MB/s) | none: the pace of the app's pre-read of the file (`Loads one at a time, at a pace`, below); off reads nothing in first, and the load still takes the gate. For an AI model load it is read as these are, only under a memtest or an armed window |
| `debug.satori.llm.repack` | on, off | `use_extra_bufts`: Q4_0 and Q8_0 weights copied into the KleidiAI and repack kernels' layouts |
| `debug.satori.llm.mmap` | on, off | `use_mmap` |
| `debug.satori.llm.directio` | on, off | `use_direct_io`, O_DIRECT reads; turns mmap off, `directio=unavailable` where the file doesn't open so |
| `debug.satori.llm.swa_full` | on, off | `swa_full`: off keeps 1,024 cells for the sliding-window layers instead of the context |
| `debug.satori.llm.kv` | f16, q8_0 | `type_k`, `type_v`; q8_0 also sets `flash_attn_type` on, as a quantized V cache needs |
| `debug.satori.llm.ctx` | 1024 to 8192 | `n_ctx` (4096) |
| `debug.satori.llm.outputs` | 1 to 512 | `n_outputs_max` (llama.cpp's own: the batch, 512): how many tokens of a batch may ask for logits, which the compute buffer is reserved for (512 of E2B's 521 MiB); the app asks for one at most. The line then has ` outputs=<n>`, and ai-chat's `llama_context: n_outputs_max = <n>` and a smaller `CPU compute buffer size` show it applied |
| `debug.satori.llm.tiedout` | repack, mapped | with repack on, mapped turns `use_extra_bufts` off and sends only the layers' quantized matrices to KleidiAI's buffer type (`tensor_buft_overrides`, `blk.N.(attn_q\|attn_k\|attn_v\|attn_output\|ffn_gate\|ffn_up\|ffn_down\|inp_gate\|proj).weight`), so the tied output `token_embd` stays in the mapping: no 315 MiB `CPU_REPACK` copy for E2B, no 306 MiB second copy (and its 288 MiB temporary) for the 1B. ai-chat says `load: tied output left mapped; only the layers' matrices go to CPU_KLEIDIAI`, and then `load_tensors: CPU_KLEIDIAI model buffer size = 999.44 MiB` (E2B) or `707.03 MiB` (1B) with no `CPU_REPACK` line shows it applied (run 4 read 707.03 MiB and no CPU_REPACK; with tiedout repack: 999.44 plus 315.00 MiB of CPU_REPACK, and 1013.03 MiB) (`W load: tied output left in the repack buffers: no CPU_KLEIDIAI buffer type` if it couldn't); the line has ` tiedout=mapped`, only with repack on |
| `debug.satori.llm.copypace` | off, 10 to 1000 (MB/s) | `progress_callback`: llama.cpp's copy of the weights into the repacked buffers, paced tensor by tensor (a tensor isn't split), from the first copied tensor on (llama.cpp's setup before it, the vocabulary and the file's mapping, is no credit), waiting while MemFree is under the pacing floor (below) and while the recorder's own load runs; once the load has waited 10 s in all for memory, its pre-read's waits among them, it stops the load, which then fails and loads nothing. Tensors left in the mapping aren't paced (a tensor that went by faster than 100 GB/s counts as mapped). The bytes are llama.cpp's own count (`lib-llama-android/patches/0001-load-progress-bytes.patch`), which counts a duplicated tied output twice: E2B's mapped figure is about 2,494 MB with the output mapped. `SatoriLoads` then says `Weight copy of <model> at <r> MB/s: <n> MB copied, <m> MB left in the mapping, in <ms> ms; waited <ms> ms for memory (lowest MemFree <n> MB), <ms> ms for the recorder's load`, or as a warning with `; the load is stopped: MemFree under <f> MB for 10 s in all` (<f> the floor in effect) when it stopped it (the line's wait is the copy's own; the pre-read's line gives the rest) (then ai-chat's `W pace_weight_copy: the weight pacer stopped the load: the phone stayed short of memory`, llama.cpp's `llama_model_load_from_file_impl: cancelled model load` and the failed load's `The weight copy stopped the load: the phone stayed short of memory for 10 s`); the load line has ` copypace=<r>` |
| `debug.satori.llm.populate` | on, off | off maps the GGUF without MAP_POPULATE (`lib-llama-android/patches/0002-map-populate-switch.patch`, upstream PR #28055's direction): no read of the whole file before the first weight is used. The pages come in as they are used, the copied tensors' during the copy (at `copypace`'s pace when set), the ones read in place as the first prompt reads them; E2B's per-layer embeddings mostly never come in (RssFile after the load about 1.3 GB instead of 3.2). ai-chat says `load: no MAP_POPULATE; the file's pages come in as they are used`; the line has ` populate=off`. With a pre-read (every model's, unless `load.pace` is off), it reads in only the tensors the load reads in place, in full, from the file's tensor table (`InferenceEngineImpl`: `Without MAP_POPULATE: <k> tensors, <n> MB of the file's <m> MB, are read in place; the app reads those in first`, or `W Without MAP_POPULATE, but the file's tensor table wasn't read (<class>); the app reads all of it in first`): with the layers' matrices copied (repack on) E2B's tied output `token_embd`, `per_layer_model_proj` and its norms, 265 tensors and 342 MB, and the 1B's tied output and norms, 158 and 306 MB; with repack off all but E2B's per-layer embeddings, 1342 MB, and all of the 1B, 1014 MB. With `load.pace` off those come in from flash at full speed as the first prompt reads them (about 341 MB for E2B in a fraction of a second), and with `copypace` off the copied ones as llama.cpp copies them: measure this one with both on, the models' settings |
| `debug.satori.llm.dropcopied` | on, off | none of llama.cpp's own (`lib-llama-android/patches/0003-mapped-hook.patch`): once the last tensor is loaded, and before llama.cpp unmaps the head and tail of the mapping it doesn't use, the whole pages only the copied (repacked) tensors lie on go back to the kernel, `madvise(MADV_PAGEOUT)` out of the page cache and `MADV_DONTNEED` out of the mapping, then `posix_fadvise(DONTNEED)` where that acts (not under Android/data); every page a tensor used in place has a byte on stays mapped and cached. Gemma 3 1B gives back 706.77 of its 707.03 MiB copied, in 78 ranges; Gemma 4 E2B 998.87 of 999.42 MiB, in 141 (the host test `copied_pages_test`). `InferenceEngineImpl` says, in every build, `Copied pages of <file> given back: <d> MB of the <c> MB copied, in <r> ranges in <ms> ms; <k> MB still cached; RssFile <a> -> <b> MB`, or as a warning `Copied pages of <file> given back only in part: <k> MB of the <d> MB still cached (another mapping holds them, or the kernel kept them); ...`, `... given back only in part: <d> MB of the <c> MB copied unmapped in <r> ranges, but MADV_PAGEOUT may not act on them (the file is neither the app's nor writable by it); ...` or `Copied pages of <file>: madvise or posix_fadvise failed (<errno name>); ...`. `0 MB still cached` confirms MADV_PAGEOUT on the phone's kernel. The next load of the same model reads those pages from flash again (about 707 MB for the 1B, 5.9 s at 120 MB/s). On for the models' own and any other file's settings; the line has ` dropcopied=on` only where something is copied out of a mapping (repack and mmap on) |
| `debug.satori.llm.cold` | on, off | none: drops the model file's cached pages before the load, so it reads from flash; the line ends ` cold=on`. Since 0.7.2 with MADV_PAGEOUT (below); 0.7.1's posix_fadvise DONTNEED dropped nothing of a file under Android/data |

`cold` is a measuring aid: llama.cpp maps the whole file in at a load, which costs nothing new
with the file cached and 3.2 GB of page cache for Gemma 4 E2B without, so memtests compare like
with like only when each starts cold. Through 0.7.1 the drop was `posix_fadvise(DONTNEED)`, which
reaches only the FUSE file's own cache: the app reaches Android/data through FUSE with a BPF
backing, whose mappings map the lower file's pages, so it dropped nothing there (64 of 64 MB still
cached on the emulator), and every cold memtest on the phone started only as cold as the page cache
happened to be. Since 0.7.2 the drop maps the file, looks at each 8 MiB with mincore, maps in only
the pages that are cached (one read each, a minor fault, with MADV_RANDOM so nothing is read ahead
or from flash), pages each piece out with `MADV_PAGEOUT`, then calls `posix_fadvise` for what is left
where it acts (on the emulator, in an app's view of Android/data: 64 of 64 MB dropped in 7 ms, 0
major faults; half a file cached, the other half was not read in). `ai-chat` then says
`drop_cached_pages: <a> of the file's <m> MB were cached, <b> MB still are (MADV_PAGEOUT, then
posix_fadvise), in <ms> ms` (`W drop_cached_pages: the file's cached pages not all dropped: <reason>`
when a call failed). A file that is neither the app's nor writable by it gets `posix_fadvise` alone:
`drop_cached_pages: posix_fadvise only: the file is neither the app's nor writable by it, so its
cached pages can't be seen or paged out`. Pages another mapping holds stay. A pre-read of 8 MiB
pieces still finds about one readahead window (512 KiB) of each piece after the first cached, as
reading piece N reads ahead into N+1: a cold pre-read reads about 93.75% of a file, 2,994 of E2B's
3,194 MB. The system prompt cache's
file name holds the settings, so a state from one context never meets another. With `swa_full` off the KV cache can't be rewound to the end of a system prompt, since
the window may have reused its cells: `ai-chat` says `processSystemPrompt: windowed SWA cache —
full memory reset required (its cells may be reused)` and the prompt comes from its cache file
instead.

A debug build also measures one load from adb: `adb shell am start -n
com.satori/com.satori.ui.debug.DebugToolsActivity --es action memtest --es
model e2b` (or `1b`; `~/satori-audit/tools/memtest.sh <variant> <model>` sets a variant's
properties around it and samples the phone's memory). Under the AI's foreground service and the
AI work gate it lets go of any model in memory, waits up to 5 s for the service to be in the
foreground (as the finisher's loads do), loads the model by the app's own path with its settings
and the properties' over them (Gemma 4 E2B through LoadFinishingModelUseCase, so only with its file
plus 600 MB free; the live model with no bar), runs one fixed prompt of 66 made-up steps (5,930 characters, about 2,000
tokens) with an answer of 200 tokens, unloads the model, and waits 11 s for the release's line 10 s
after. It refuses as the debug transcribe does: beside a busy recorder, while a recording waits to
be finished or while the AI is held; a recording that starts meanwhile stops it. The run goes on in
the app's process (`DebugRuns`), held by the screen's view model while the screen is in front, so a
fold or a turn, which makes the activity again, neither stops it nor starts a second one.
`DebugTools` ends it with one of:

- `Memtest <model> done: load <ms> ms, prompt 5930 chars to the first token in <ms> ms, answer
  <n> tokens in <ms> ms (<x> tokens/s); AI service in the foreground` (or `; AI service not in
  the foreground`: the phone refused the service or it wasn't there after 5 s, and the process
  likely ran in the background group, on 3 of the 8 cores); the speed is the answer's tokens after
  the first over the time from the first to the last;
- `W Memtest <model> refused: a recorder is busy` (or `the AI is busy`);
- `W Memtest <model> not loaded after <ms> ms: <why>; AI service ...`, e.g. `Not enough free
  memory: <n> MB available, <m> MB needed`;
- `W Memtest <model> cancelled: a recording started; the model is let go; AI service ...`;
- `W Memtest <model> cancelled: the screen was closed; the model is let go` (Back during the run);
- `W Memtest <model> failed: <exception class>; the model is let go; AI service ...`;
- `W Memtest: no model <name>, only e2b or 1b`;
- `W Memtest <model> not started again: its screen came back after the app's process ended`
  (Android restoring the activity after its process ended while its task was still on top, as
  when the phone wakes or the next adb start finds that task: no load starts then, the screen ends
  with its task, and the start after it runs).

Since 0.8.0-dev2 the debug screen has a task of its own, `com.satori.debugtools`, kept out of
Recents (the debug manifest's `DebugToolsActivity`), so Satori from the launcher or the quick key
brings up Satori's own screens, never a debug one. The long tools, the memtest, the prewarm
probe, the rerun and the reader's three, run in the app's process (`DebugRuns`), held by their
screen's view model while the screen is in front, and the screen goes to the back
(`moveTaskToBack`) as soon as the run has started, past its rules and holding the AI's service,
and the service is in the foreground, which it waits for at most 5 s after the start: Android
starts a foreground service only while the app is in front, and once the screen has gone the
service keeps the process in the foreground group, the one a locked phone's runs have. `DebugTools`
says so once a run:

```
<action> runs with its screen in the back
```

with `<action>` the tool's: `memtest`, `prewarm`, `rerun`, `reader-download`,
`tokenizer-selftest` or `reader-selftest`. A service not in the foreground by then keeps the
screen in front, as before 0.8.0-dev2: in the back the service is all that keeps the process from
the cached ones, which Android freezes or kills without a line (seen on the emulator once its
service had left the foreground), while a screen in front keeps it, behind the keyguard too. So
does a move Android refuses (a task locked in front). `DebugTools` says why, once a run, and Back
on that screen still cancels the run:

```
W <action> keeps its screen in front: the AI service is not in the foreground
W <action> keeps its screen in front: its task didn't move to the back
```

Android ends a screen sent to the back itself (seen on the emulator's Android 15): the move puts
its task behind the home screen, and a task kept out of Recents is trimmed there at once (the
events log says
`wm_finish_activity: [...,com.satori/.ui.debug.DebugToolsActivity,recent-task-trimmed]`); Home
pressed with the screen in front (`--es front true`) neither trims it nor ends the run, only Back
does. So the run belongs to the process, not to its screen: the screen's view model holds it only
while the screen is in front, and nothing on a run's path waits on the screen, which may be
stopped, unfocused, behind the keyguard or gone. A screen Android leaves in the back ends with its
run, and its task with it (`finishAndRemoveTask`); the other actions end the same way, so no empty
task is left behind and the next tool's `am start` starts it as usual. A run its rules refuse (a
busy recorder, a recording waiting to be finished, the AI held) never leaves the front and ends at
once; one its own checks refuse a moment later (no input file, a file not the pinned one) may have
gone to the back first, and ends at once all the same. `--es front true` keeps the screen in front
for the whole run, as before 0.8.0-dev2 (an emulator, or a run someone watches); Back then cancels
the run with its `cancelled: the screen was closed` line. A run in the back can't be reached by
Back, and an `am start` while it runs starts that tool anew: the AI's tools refuse it while the AI
is held (`refused: the AI is busy`), and a second reader download refuses with `W Reader download
<variant> refused: another reader download is under way`. Wait for a run's last line before the
next start.

`memtest.sh` prints one line per run, the figures that compare across runs first:
`memtest <variant> <model>: <outcome>; direct reclaim <n> MB (scanned <n> MB, <n> stalls); lowest
MemFree <n> MB (<n> MB before); peak PSI full avg10 <x>; kills <n>; lowest MemAvailable <n> MB (<n>
MB before); refaulted file <n> MB; cached apps before <n>; load <ms> ms; <x> tokens/s; phone
<locked|launcher>, screen <...>, power <...>; cpuset <...>; AI service <...>; llama.cpp <settings>`,
then what the log says of the settings added since 0.5.2 (`tied output mapped: CPU_KLEIDIAI <n> MiB,
CPU_REPACK none (expected 999.44 MiB and none: ok)`, the `Weight copy of` line, `llama.cpp
n_outputs_max <n>`, `no MAP_POPULATE`, and the `Pre-read of` line of the run's own model, named as
the load's memory line names it), then `properties: reset, each read back empty`. A cold run with
`loadpace` whose pre-read read less than 95% of what it was to read is marked `NOT APPLIED:
loadpace(<n> of <m> MB read, under 95% of a file whose cache was dropped)`: the pre-read trusted a
page cache it couldn't see. With `populate=off` and `loadpace` the line must be the pre-read of the
tensors read in place (`NOT APPLIED: loadpace(not the tensors read in place)` otherwise). A file
whose cached pages can't be seen adds its line, and a pre-read that waited 10 s in all for memory
makes the run `not loaded`, with its `gave up` line.
Since 0.5.3 it knows `outputs`, `copypace`, `tiedout`, `populate` and `loadpace`
(`debug.satori.load.pace`), and the plan's names `prewarm=`, `pace=` and `repack_output=off` for the
last three; it empties `debug.satori.load.pace` and `debug.satori.ort.prepack` with the llama.cpp
ones before and after each run. `memtest.sh probe=<MB/s> e2b|1b|parakeet|cohere` runs the pace
probe instead, with the same sampling and summary: `memtest probe=<r> <model>: done, <n> MB of <m>
MB read; ...` (or `gave up, ...`), `(NOT APPLIED: ...)` when a probe that ran read less than 95%. `MEMTEST_SAMPLE_S=0.25` samples four times a
second; mem.tsv has pgscan_direct, allocstall_normal/movable, pgsteal_kswapd and
workingset_refault_file too. `MRUN_VARIANT=<key=value,...> sh m-run.sh <label> <feed> <s>` records a
whole feed under a variant with the same keys and values (and `ortprepack` on|off, and the plan's
names), refusing a key or value it doesn't know before it touches the phone. It reads each property
back before it arms the app's AI loads, goes on only once the app says `Debug load settings armed
for every AI model load for <n> min`, and on the way out ends the arming (`Debug load settings no
longer armed`) and empties every property, each read back with an answer that must come in full.
While the phone doesn't answer it tries again for 2 minutes (`MRUN_RESET_WAIT_S`), then warns with
the commands to clear them by hand and exits 3. Runs compare only like with like:

- One state for every run (`MEMTEST_STATE=locked`, the default, or `launcher`; the script refuses
  the other), on the charger. The state sets the process's CPU group, and with it how fast a load
  takes its memory: on 2026-10-13 Parakeet v3 loaded in 3.0 s on all 8 cores and in 9.0 s in the
  background group. If the first run says `AI service not in the foreground` or `cpuset
  /background`, run the whole session at the launcher instead.
- A `today` run before each variant and one after the last (today, X, today, Y, ..., today), 60-90
  s apart, and each variant read against the today runs on either side: every kill frees memory, so
  the runs after it start with more of it and fewer apps to end. Since 0.6.1 `today` is the
  model's own settings, the fast rung for Gemma 3 1B and Gemma 4 E2B.
- Direct reclaim, the lowest MemFree and the peak PSI full first; kills and MemAvailable after them.
- `directio` on e2b only with 6.5 GB available or more (the script refuses it below): all its
  weights go into anonymous memory, so the owner's usual 3.0-5.4 GB never pass its bar.

Should the script lose the phone before it has emptied the properties, it tries again for 2 minutes,
then warns with the keys and exits 3; clear them by hand then. An emptied property stays listed, so
check with `adb shell getprop | grep debug.satori | grep -v ': \[\]$'`, which must print nothing
(`debug.satori.load.pace`, `debug.satori.load.room` and `debug.satori.ort.prepack` apply to every
speech model load while they are set; the AI models' loads read the properties only under a memtest
or an armed window).

#### Loads one at a time, at a pace (every build)

Every model load, a release build's too: the live speech model at a recording's start (and at a
switch of its language), a speech model for a file (Transcribe again, the better transcript), the
live AI model and Gemma 4 E2B (`SatoriLoads`):

1. Takes the one-load gate, so two loads never start together (on 2026-10-13 at 14:21:40 the 1B and
   Parakeet did, and 4 cached apps went). The recorder's own load, its speech model, never waits
   for the gate: it goes ahead, and the other loads give way to it at their next piece (8 MiB read,
   or a tensor llama.cpp copies) and don't start while it runs. Only a native step already under
   way (ONNX Runtime making its sessions, llama.cpp mapping its file or making its context) runs on
   beside it.
2. Reads its files in first, 8 MiB at a time at its pace: an AI model's at its model's, 120 MB/s
   (`How the AI models load`, above; a memtest's `debug.satori.load.pace` over it, `off` for none);
   a speech model's at 120 MB/s too, in every build since 0.7.1 (the memory plan's run 5, not yet
   measured on the phone: Parakeet v3's 670 MB take about 5.6 s from flash, the Malayalam model's
   138 MB about 1.2 s, Cohere Transcribe's 2.9 GB about 24 s), with a debug build's
   `debug.satori.load.pace` off|<MB/s> (10-1000; empty is the 120) over it, read at each speech
   model load of a debug build (a release build never reads it). So llama.cpp's MAP_POPULATE and
   ONNX Runtime's reads find them cached (a load without MAP_POPULATE, `debug.satori.llm.populate=off`, only the
   tensors it reads in place). A piece already in the page cache (mincore) is skipped, so a file
   still cached from its last load costs about no time. mincore tells the truth only about a file
   the app owns or may write (Linux since 5.0 calls every page of any other one cached), so the
   pages of any other file are taken as not cached, and all of it is read and paced. While MemFree
   is under the pacing floor the next piece waits until it is back at the resume level, 150 and 200
   MB on the razr; since 0.7.2 such a wait also ends once MemFree, Cached and SwapFree have stood
   still for 500 ms, and the floor drops under that level (`The floor of the paced waits`, below). After 10 s of such waits in all
   (an AI model's pre-read and weight copy together) the load stops before its model loads
   (MEMORY_RESEARCH_PLAN P2): Gemma 4 E2B's then falls back to the live model, a speech model for a
   file fails as a load does, and its work says so. Only the recorder's own load goes on, since a
   recording must never fail: since 0.7.1 it stops reading then and loads at once, unpaced (0.6.1
   read the rest at the rate), and a pre-read of it that fails (other than a file it can't read,
   which the load reads itself) lets it go ahead unpaced as well. Its capture records meanwhile, the
   microphone, the queue and the kept audio as ever: only the live text waits, and the recorder's
   status line says "Getting ready" until the model is in. A recorder's load whose recording is
   switched to the other language while its files are read in stops at the next piece (or wait) and
   loads nothing, and the capture loads the new language's model instead (`A switch while the speech
   model still loads`, above); one that Stop comes to in a recording that keeps its audio stops the
   same way (`Stop while the speech model still loads`, above). The line says why: `as the recording
   wants another speech model now`, `as Stop came first and the recording's audio is kept for after
   Stop`, or `as the capture ended` (the recorder closed). The waits don't count toward the rate (no
   catch-up burst). A Transcribe again cancelled meanwhile stops before its model loads. A cold load
   (the memtest's `cold=on`) drops the file's cached pages before this, not after.
3. A speech model's load (since the review of 0.7.1) then makes room for ONNX Runtime's parse of the
   weights its ONNX files hold (Parakeet v3's 639 MiB, the Malayalam model's 131 MiB, Cohere's 149 MiB
   beside the 2.5 GiB it maps; the need the line names is that and an eighth: 719, 147 and 167 MB): that parse took Parakeet v3 from 54 to 714 MB RssAnon within one
   second on 2026-10-16, from cached files, while MemFree was 352 MB, and a cached app was killed in
   the direct reclaim it caused. So the load takes that memory at the same pace into a room of
   shared memory it holds, waiting while MemFree is under the floor as the pre-read does, until the
   room and MemFree together are the parse's need (the files and an eighth) and the floor more (150 MB,
   or the lowered floor), and
   gives the room back right before the native load, which finds that memory free. With MemFree that
   high already no room is held. Its waits count with the pre-read's toward the 10 s; after them the
   room is given back, and the recorder's load goes ahead unpaced, any other stops. A room that
   can't be made lets the load go ahead without it. A debug build's `debug.satori.load.room` off
   makes no room (empty or on is the default); with the pre-read off there is none either.
4. Loads (an AI model's weights copied at its copy pace, `debug.satori.llm.copypace` above), and
   gives the gate back.

```
Load gate: gemma-3-1b-it-Q8_0.gguf waited 812 ms for another load
Load gate: parakeet-tdt-0.6b-v3-int8, the recorder's, goes ahead of gemma-4-E2B_q4_0-it.gguf
Pre-read of gemma-4-E2B_q4_0-it.gguf: 3194 MB of 3194 MB read in 27120 ms at 120 MB/s; waited 0 ms for memory (lowest MemFree 412 MB), 0 ms for the recorder's load
Pre-read of gemma-4-E2B_q4_0-it.gguf, the tensors it reads in place: 344 MB of 344 MB read in 2867 ms at 120 MB/s; waited 0 ms for memory (lowest MemFree 412 MB), 0 ms for the recorder's load
Pre-read of gemma-4-E2B_q4_0-it.gguf: 3194 MB of 3194 MB read in 27640 ms at 120 MB/s; waited 500 ms for memory in 1 wait (longest 500 ms: 1 stood still; floor 122 MB, lowest MemFree 114 MB), 0 ms for the recorder's load
W Pre-read of gemma-4-E2B_q4_0-it.gguf gave up after 14210 ms: MemFree under 150 MB for 10 s; 501 MB of 3194 MB read; the load is stopped
Pre-read of parakeet-tdt-0.6b-v3-int8: 670 MB of 670 MB read in 5590 ms at 120 MB/s; waited 0 ms for memory (lowest MemFree 380 MB), 0 ms for the recorder's load
W Pre-read of parakeet-tdt-0.6b-v3-int8 gave up after 11840 ms: MemFree under 150 MB for 10 s; 220 MB of 670 MB read; the recorder's load goes ahead unpaced
W Pre-read of parakeet-tdt-0.6b-v3-int8 failed (IllegalStateException); the recorder's load goes ahead unpaced
Pre-read of parakeet-tdt-0.6b-v3-int8 stopped after 1840 ms, as the recording wants another speech model now: 220 MB of 670 MB read; parakeet-tdt-0.6b-v3-int8 is not loaded
Pre-read of parakeet-tdt-0.6b-v3-int8 stopped after 2110 ms, as Stop came first and the recording's audio is kept for after Stop: 250 MB of 670 MB read; parakeet-tdt-0.6b-v3-int8 is not loaded
Load of parakeet-tdt-0.6b-v3-int8 not started, as the recording wants another speech model now
Room for parakeet-tdt-0.6b-v3-int8's parse: 520 MB held in 4333 ms at 120 MB/s for its 719 MB, MemFree 352 MB before and 872 MB with the room; waited 0 ms for memory
Room for indicconformer-ml-int8's parse: none held, MemFree 1120 MB is room enough for its 147 MB
W Room for parakeet-tdt-0.6b-v3-int8's parse gave up after 10000 ms: MemFree under 150 MB for 10 s; 96 MB of 719 MB held; the recorder's load goes ahead unpaced
W Room for cohere-transcribe-14-lang-int8's parse gave up after 10000 ms: MemFree under 150 MB for 10 s; 40 MB of 167 MB held; the load is stopped
W Room for parakeet-tdt-0.6b-v3-int8's parse not made (ErrnoException); its load goes ahead without it
Room for parakeet-tdt-0.6b-v3-int8's parse given back after 1000 ms, as Stop came first and the recording's audio is kept for after Stop: 120 MB of 719 MB held; parakeet-tdt-0.6b-v3-int8 is not loaded
W Pre-read of parakeet-tdt-0.6b-v3-int8: encoder.int8.onnx's cached pages can't be seen (neither the app's nor writable by it, so mincore would call every page cached); all of it is read
W Pre-read of cohere-transcribe-14-lang-int8: encoder.int8.onnx.data (FileNotFoundException) not read; the load reads it itself
W Pre-read of gemma-3-1b-it-Q8_0.gguf: gemma-3-1b-it-Q8_0.gguf (IOException) not read; the load is stopped
```

`waited <ms> ms for memory (lowest MemFree <n> MB)` is a line without a wait; with waits it
counts them, the longest, how each ended (`until MemFree came back`: kswapd caught up; `stood
still`: the kernel wasn't reclaiming, and the floor dropped, below; `gave up`: the 10 s were
spent; `with MemFree unreadable`), and the floor in effect at the end. The gave-up lines and the
failed load name the floor in effect, `MemFree under <f> MB for 10 s`. The room's line has the same
count without the lowest MemFree.

The first MB figure is what wasn't cached and was read in (the in-place one counts whole 16 KiB
blocks, so a little more than the tensors); the load's own memory line follows the pre-read (its
time includes it). A load stopped so fails with `The load of <model> was stopped: MemFree stayed
under <f> MB for 10 s while its files were read in` (the memtest's `not loaded` line gives it), and
an AI model's whose file couldn't be read in with `The load of <model> was stopped: <file> (<class>)
couldn't be read in first` (a speech model's reads such a file itself). `Not started` comes when
the switch came after the last piece was read, or with the pre-read off, just before the native
load. `W Ignoring debug.satori.load.pace=<value>: not off or 10-1000 (MB/s)` for a value it doesn't
know (from `InferenceEngineImpl` for an AI model's load, from `SatoriLoads` for a speech model's,
which then has its 120 MB/s), and `W Ignoring debug.satori.load.room=<value>: not on or off`.

The room's `with the room` figure is what MemFree is as it is given back: the parse should leave
about 150 MB of it. On the phone the room is shared memory (RssShmem), not RssAnon, so the load's
memory line (below) still shows only the parse. A room line with `none held` most of the time
means the phone had the memory free anyway; `gave up` lines mean it hadn't, and a kill then is the
one to look at.

The pace itself is probed without a model: `adb shell am start -n
com.satori/com.satori.ui.debug.DebugToolsActivity --es action prewarm --es
model e2b|1b|parakeet|cohere|ml --es rate <MB/s>` (whatever the property says). Under the AI's gate
and service, with the memtest's rules (not beside a busy recorder, a recording waiting to be
finished or a held AI; a recording that starts stops it) and its screen in the back as the
memtest's (`prewarm runs with its screen in the back`), the model's files lose their cached pages
(`drop_cached_pages` lines from ai-chat) and are read in as a paced load's pre-read (its `Pre-read
of e2b: ...` line), then `DebugTools` says `Prewarm <model> done in <ms> ms: <n> MB of <m> MB read;
cached pages dropped first for <k> of <f> files; AI service in the foreground` (a cold probe reads
all of it: less means the pre-read trusted a page cache it couldn't see), or `W Prewarm <model> gave
up after <ms> ms: MemFree under <f> MB for 10 s; <n> MB of <m> MB read; cached pages dropped first
for <k> of <f> files; ...` (its pre-read line ends `; the probe stops`), `W Prewarm <model> refused:
a recorder is busy|the AI is busy`, `W Prewarm <model> not run: <why>` (e.g. `Cohere Transcribe is
not on the phone`), `W Prewarm <model> cancelled: a recording started`, `W Prewarm <model> failed:
<exception class>`, `W Prewarm: no model <name>, only e2b, 1b, parakeet, cohere or ml`, `W Prewarm
<model>: no rate of 10-1000 MB/s given (rate=<value>)`.

#### The floor of the paced waits (every build, since 0.7.2)

Every paced wait for memory (the AI models' pre-read and weight copy, the speech models' pre-read,
the room for a speech model's parse, the probe, and the first answer after an AI model's load,
`The first answer waits for memory`, below) waits while MemFree is under one floor, the process's
(`SatoriLoads`, `MemoryFloor`). On 2026-10-17 at 14:27 Gemma 4 E2B's paced load stopped with
4,155 MB available: MemFree sat at 128-150 MB, between the razr's low watermark (121 MB, under
which kswapd wakes) and the floor of 150 MB, so kswapd slept and the wait for 200 MB could only
end at the 10 s give-up. The floor comes from, in this order:

1. a debug build's `debug.satori.mem.floor` (64-400 MB, read as each paced load begins; the resume
   level is 50 MB over it), which starts each load at that floor while it is set. Never under 64
   MB, the stand-still rule's own lowest (the review of 0.7.2): a value a memtest left set holds
   every paced wait of the owner's recordings until the phone restarts, so the scripts read every
   such key back empty after each run;
2. the kernel's low watermark, the sum over the zones that hold memory of `low` without `boost`,
   from `/proc/zoneinfo`, read once per process, with the resume level half the way to `high`, 50
   MB at most. SELinux denies the file to every app since Android O, silently (a `dontaudit`
   rule), so the razr never gets this;
3. 150 MB and 200 MB, as through 0.7.1: the razr's.

Under any of them a wait under the floor ends once MemFree, Cached and SwapFree have all stayed
within 4 MB for 500 ms: the kernel isn't reclaiming then, and nothing changes until a page is
taken. The floor then drops to 24 MB under where MemFree stood, never under 64 MB, for the rest of
the process, so the next piece crosses the kernel's low and wakes kswapd. While memory moves the
wait goes on: MemFree rising (kswapd at work) until the resume level, MemFree falling (another app
taking memory), Cached falling while MemFree stays flat (kswapd keeping up with another app), or
SwapFree moving while both stay flat (kswapd swapping another app's memory out to zram as fast as
it takes memory, with file pages coming back as fast as they go: the squeeze of r071-en-3-warm,
about 100 MB/s to zram; since the review of 0.7.2, so such a squeeze never lowers the floor). A
SwapFree that can't be read leaves it to MemFree and Cached. The 10 s give-up stays the backstop,
with what it does as before: an AI load stops, the recorder's goes ahead unpaced. A wait whose
MemFree stands under 64 MB waits on, also the one that lowered the floor to 64 MB: no piece is
taken under the lowest floor. A debug build's `debug.satori.mem.still` off (read as each paced load
begins; empty or on is on) turns the stand-still rule off, for an A/B: the waits are 0.7.1's then.
`W Ignoring debug.satori.mem.floor=<value>: not 64-400 (MB)` and `W Ignoring
debug.satori.mem.still=<value>: not on or off` for values it doesn't know; a release build reads
neither.

```
Memory floor 150 MB, resume 200 MB: /proc/zoneinfo can't be read (EACCES); it follows the kernel from the first wait that stands still
Memory floor 142 MB, resume 169 MB: the kernel's low watermark (/proc/zoneinfo)
Memory floor 121 MB, resume 171 MB: debug.satori.mem.floor
Memory floor 150 MB, resume 200 MB: debug.satori.mem.floor cleared
Memory floor lowered to 122 MB (resume 172 MB): MemFree stood still at 146 MB for 500 ms in the pre-read of gemma-4-E2B_q4_0-it.gguf, so the kernel wasn't reclaiming
W Memory floor stays at 64 MB, the lowest it goes: MemFree stood still at 60 MB in the room for parakeet-tdt-0.6b-v3-int8's parse
```

A wait that lowers the floor to 64 MB with MemFree under it gives both of the last two lines, the
lowering and the warning, and goes on. The first comes once per process, as its first paced load begins (or `/proc/zoneinfo holds no
watermarks`, or `the kernel's low watermark (<n> MB) is under 64 MB`, which keep the 150 MB). A
lowering names the part that stood still: `the pre-read of`, `the weight copy of`, `the room for
<model>'s parse`, `the probe of`, `the wait before the first answer on`. Once loads go on below
the kernel's low watermark kswapd works during them, and Motorola's lmkd can kill on a PSI event
then (run 9 of 2026-10-17, with populate off: one kill, `PSI critical event`, while kswapd
reclaimed about 2.9 GB in 20 s): the rule lets E2B's load go on at about 4 GB available, but it
doesn't make E2B safe by itself.

#### The first answer waits for memory (every build, since 0.7.2)

In r071-en-3-warm (2026-10-16 14:36) the 1B's first answer began with the model just in, another
app started three WebView processes, MemFree fell to 122 MB and lmkd killed six cached apps 1.6-2.4
s into the answer. So the first answer after each AI model load (`LlmRepositoryImpl`: the first of
the finisher's steps, or of Rewrite notes, Find again, the map or Translate) waits before its
first native call while MemFree is under the floor of the paced loads (above), by the same rule:
until MemFree is back at the resume level, until MemFree, Cached and SwapFree stand still for 500 ms (the
floor then drops for the loads too), or 10 s at most. Then it begins, whatever MemFree is: it
never fails the answer. A cancel ends the wait, and the next answer waits instead. The answers
after the first, and the first after a model already loaded, don't wait. A debug build's
`debug.satori.llm.answerfloor` off (read at each first answer; empty or on is on; not armed like
the `llm` load settings) lets the answer begin at once, for an A/B; `W Ignoring
debug.satori.llm.answerfloor=<value>: not on or off` for a value it doesn't know. `SatoriLoads`
says, only when it waited:

```
First answer on gemma-3-1b-it-Q8_0.gguf waited 1350 ms for memory: MemFree 132 MB as it waited, 176 MB as it began (floor 122 MB)
First answer on gemma-3-1b-it-Q8_0.gguf waited 500 ms: MemFree stood still at 141 MB under the floor of 150 MB, so the kernel wasn't reclaiming; it began (floor now 117 MB)
W First answer on gemma-4-E2B_q4_0-it.gguf began after 10000 ms of waiting for memory: MemFree 118 MB, under its floor of 122 MB
First answer on gemma-3-1b-it-Q8_0.gguf waited 50 ms for memory, then MemFree couldn't be read; it began
```

The squeeze of r071-en-3-warm came during the answer, not before it: MemFree was 249-299 MB as
that answer began, so this wait would not have waited there. What changes that run is the drop of
the 1B's copied pages after its load (`How the AI models load`: about 707 MB more free before the
answer); this wait guards an answer that begins with MemFree already low.

#### ONNX Runtime's options for the speech models (every build, since 0.7.1)

Every load of a speech model, a release build's too: Parakeet v3 and v2, live and for a file,
Cohere Transcribe, and AI4Bharat's Malayalam model, live and for a file. ONNX Runtime's prepacking
(`session.disable_prepacking=1`) and its memory pattern (`EnableMemPattern=0`) are off, through
sherpa-onnx's provider config file: the provider is `cpu:<file>`, where 0.7.0 and earlier had
`cpu`, ONNX Runtime's defaults. The file is the app's own `files/ort/prepack-off.conf`, written at
every start and checked before each load (a missing file would end the process, `_Exit(-1)`); it
is written again if it is gone or changed (0.7.0's debug build wrote another first line, so the
first start of 0.7.1 writes it again). A debug build's `debug.satori.ort.prepack` on (read at each
load; empty and off are the default, a release build never reads it) loads with ONNX Runtime's
defaults, `cpu`, as before. On 2026-10-13 prepacking made Cohere Transcribe's weights about 2 GB of
anonymous memory (RssAnon 453 -> 2,498 MB) and gave Parakeet v3's start its 1.25 GB peak; with it
off, the memory line of the load (above) should show Cohere at about 0.3-0.6 GB of RssAnon and its
weights in RssFile, and Parakeet's peak at about 0.75 GB (the plan's figures; the cost, estimated
+10-20% of Cohere's time and +2-5% of Parakeet's, is unmeasured). Each load says, under
`SpeechRecognizers` and then `sherpa-onnx` (W, each line after one naming `session.cc`):

```
Speech model cohere-transcribe-14-lang-int8: 4 threads, greedy_search, ORT prepack off (cpu:/data/user/0/com.satori/files/ort/prepack-off.conf)
Provider string: cpu. Config path: /data/user/0/com.satori/files/ort/prepack-off.conf
Provider config: SessionConfig.session.disable_prepacking=1
Provider config: EnableMemPattern=0
Provider config: DEBUG=1
```

The `Provider config` lines come in any order. Without them the options didn't reach ONNX Runtime.
A load with prepacking on, a debug build's `debug.satori.ort.prepack=on` or a file that couldn't
be written, ends its `SpeechRecognizers` line `, ORT prepack on` and has no `Provider` lines.
`SpeechSessionOptions` warns `W Not turning ORT prepacking off: <path> can't be written` or `...: the
path of its config file holds "cuda" (<path>)` (the load then has ONNX Runtime's defaults), `W ORT
options not written to <path>; the speech models load with prepacking on` at start, and `W Ignoring
debug.satori.ort.prepack=<value>: not on or off` (prepacking stays off). Since the review of 0.7.1
only the models that run live (Parakeet v3 and v2, the Malayalam model) fall back so: a load of
Cohere Transcribe, whose packed copies would take about 2 GB, is refused instead with `W Not
loading a large speech model with ORT prepacking on: <path> can't be written` (or the "cuda" reason)
and fails as a load does (`PrepackingStaysOnException`), the live transcript staying. Look for
either warning before reading a load's memory line. Clear the switch after a session with `adb
shell setprop debug.satori.ort.prepack ''`.

### The reader (0.8.0, stage B1)

The reader reads a Malayalam line into English: IndicTrans2 distilled 200M, Indic to English, as
an int8 ONNX export, on the ONNX Runtime 1.28.2 sherpa-onnx ships (`lib-reader-onnx`), with the
text pipeline of `domain/reading` around it and its data source in `feature-reader`
(`ReaderDataSource`). In stage B1 only the debug tools below loaded it; since stage B2 the finishing's
Reading step reads a Malayalam recording's lines with it (`Finishing in the background`, above). Its files are a model kind of their own: each build in its folder,
`files/models/reader/<id>` (`indictrans2-indic-en-dist-200m-int8`, 11 files, 241,989,173 bytes;
`...-fp16`, the fallback), pinned to a commit with every file's size and SHA-256 (`ReaderModels` in
`ModelConfig.kt`).

Its download is the speech models' (`ModelDownloadManager`, every build): `Reader download <id>: <n>
of 11 files to fetch, <mb> MB`, then `Downloading <file>` (`from <mb> MB` when it goes on with a
Range request) and `<file> complete and checked (<mb> MB), file <k> of <n>, checked in <ms> ms`
for each file, `Reader download <id> completed`. A failure is the speech models' too (`Reader
download <id> failed at <file>: <why>`); a file whose SHA-256 is not the pin is deleted. Before
each load the files are checked by their size and each graph's first bytes; a file that fails
gives `W Reader <id> is Damaged: <file> <size>/<size> <state>; ...` (names and sizes only, as for
a speech model) and no load.

The load is a model load like any other, the one-load gate and the pace of a speech model's load
for a file (`Loads one at a time, at a pace`, above, the model named by its build's id under
`SatoriLoads`): it waits for the gate (`Load gate: <id> waited <ms> ms for another load`), gives
way to the recorder's own load, reads its 11 files in at 120 MB/s (`Pre-read of <id>: <n> MB of
230 MB read in <ms> ms ...`, the pacing's MB being MiB, a debug build's `debug.satori.load.pace`
over it), then makes room
for what its sessions turn anonymous (`Room for <id>'s parse: ...`): the weights its prepacked
graphs pack, 132 MB in the design's sessions (the encoder and decoder_with_past) and 220 MB with
all three packed, not the `.data` files; the fp16 build is priced at every weight in fp32 (882
MB), as its memory on ONNX Runtime's CPU path is unknown. After 10 s of waiting for memory in all
it stops before its native load (`... the load is stopped`). It never loads beside a live or
opening capture, the live speech model or an AI model, nor does the tokenizer alone; the caller
holds the AI work gate and lets the AI model go first. A capture that opens while it loads or reads
stops it at once: ONNX Runtime's load-cancellation flag on a load, its terminate flag on a running
Run (D14). A cancel of the caller does the same, and either way a line says how long the native
call took to return after the stop, from the stop to its return (V1's bar for a Run: 100 ms).

`SatoriReader`, every build, counts and ms only:

```
Reader indictrans2-indic-en-dist-200m-int8 loaded in 6120 ms: 18 layers, 8 heads of 64; prepack=session,arena=on,threads=4; parse room for 132 MB
Reader tokenizer of indictrans2-indic-en-dist-200m-int8 loaded in 410 ms: 122706 source pieces, 32296 target pieces
Reader indictrans2-indic-en-dist-200m-int8 Run stopped 14 ms after the cancel
Reader indictrans2-indic-en-dist-200m-int8 load stopped 230 ms after the cancel
Reader indictrans2-indic-en-dist-200m-int8 released in 180 ms
W Reader indictrans2-indic-en-dist-200m-int8 not loaded: a capture is live or opening
W Reader indictrans2-indic-en-dist-200m-int8 stopped for a capture that opened; released
```

The figures are made up. `not loaded:` also says `its files are not on the phone`, `... damaged or
incomplete`, `... can't be read`, `a speech model is in memory` or `an AI model is in memory`; a
configuration that doesn't read adds `W Reader <id>: config.json has no number <key>` (or `config.json
is no JSON (SerializationException)`), a purge of the native heap that fails right before a load of
the finishing's (since 0.8.0's Reading step, which purges what the AI model's unload left first)
`W Native heap not purged before the load of <id>: <exception class>` (or `: mallopt knows neither
M_PURGE_ALL nor M_PURGE`), a native
load that fails `W Reader <id> load failed after <ms> ms (<exception class>)` (ONNX Runtime's
message is the exception's, names and shapes only), and a release that throws `W Reader <id> not
released cleanly (<exception class>)`. `Run stopped` comes only for a Run under way when the stop came
(a later Run of a stopped reader returns at once), `load stopped` for a load that a stop ended, its
wait for the gate and its pre-read included. The tokenizer alone (the V0b selftest) is
`<id>/tokenizer` in its memory, stop, release and failure lines. In a debug build the load and the release have their
memory lines under `SatoriReader` too (`What a model load costs the memory`, above): `Memory: load
of <id> took <ms> ms; ...; RssAnon <a> -> <b> MB (peak <p> MB), ...`, the peak sampled every 25 ms
while it loads, and `Memory: release of <id> took <ms> ms; ...; RssAnon <x> -> <y> MB freed -> <z>
MB purged (M_PURGE_ALL, <ms> ms), ...`: the native heap is purged after every release, in every
build, as after a speech model's (`debug.satori.mem.purge` off skips it in a debug build).

#### The reader's debug tools (debug builds)

Three actions of `DebugToolsActivity` (`adb shell am start -n
com.satori/com.satori.ui.debug.DebugToolsActivity --es action <action> ...`), each run as the
memtest is, in the app's process and held by its screen's view model while the screen is in front,
its screen in the back once it has started (`reader-download runs with its screen in the back`,
`tokenizer-selftest ...`, `reader-selftest ...`; `--es front true` keeps it in front, where Back
cancels it). Their inputs and outputs are in `files/debug/reader`
(`/sdcard/Android/data/com.satori/files/debug/reader`), public text only: FLORES-200 and synthetic
lines from `~/satori-audit/tools/reading/push/` (`make_push.py`). The app makes the folder the
first time a tool runs (a selftest without its input says where to push it); a folder adb makes
is one the app can't enter. Lines under `DebugTools`, counts, timings and file names only.

- `reader-download [--es variant int8|fp16] [--es mobile true]`: the build's pinned files fetched
  on the phone, then every file's SHA-256 held to its pin, one kept from an earlier run too. Wi-Fi
  only unless `mobile` is true; never beside a recorder or another reader download (since
  0.8.0-dev2, as a run outlives its screen); a recording that starts, or the network
  turning metered, stops it and what came stays for the next run. Under the AI's service, so the
  screen can go off. Lines: `Reader download int8: 11 files, 241 MB, <mb> MB on the phone already;
  Wi-Fi only` (or `mobile data allowed`), every 3 s `Reader download int8: <a> MB of <b> MB (<p>%)`
  (`, checking a file` while a file's SHA-256 is computed), then `Reader download int8 done in <ms>
  ms: 11 of 11 files, 241989173 bytes, each SHA-256 the pinned one, in
  indictrans2-indic-en-dist-200m-int8`; or `W ... <file> has SHA-256 <x>, not the pinned <y>;
  deleted` and `W ... failed after <ms> ms: <k> of 11 files not the pinned ones`, `W ... failed after
  <ms> ms: <NO_CONNECTION|SERVER|...> (<why>)`, `W ... refused: a recorder is busy` (`no network`,
  `Wi-Fi only, and the network is metered (--es mobile true allows mobile data)`, `another reader
  download is under way`), `W ... stopped: a
  recording started; what came stays for the next run` (`the network is metered now`, `the network
  is gone`).
- `tokenizer-selftest [--es variant int8] [--es input v0b_inputs.tsv] [--es out v0b-int8.tsv]` (V0b):
  first `model.SRC`, `dict.SRC.json` and `dict.TGT.json` held to their SHA-256 (`Tokenizer selftest
  int8: model.SRC, dict.SRC.json, dict.TGT.json are the pinned files (SHA-256)`), the AI model let go
  as for the reader selftest (`Tokenizer selftest int8: the AI model was let go first`), then each line of
  the input (golden.py's TSV format: `name` and `input`, preprocessed by Satori's IndicPreprocessor
  and held to a `text` column when there is one; or golden's `v0b_inputs.tsv`, `name` and `text`,
  given to SentencePiece as it is) through the app's C++ SentencePiece and PieceIds. Each output row:
  `name, text, matches, pieces, ids, unknown, parts` (the ids those of the whole line, the tags and
  `</s>` included, not cut at 253). `Tokenizer selftest int8 done in <ms> ms: 1572 lines, <p> pieces,
  <u> unknown in <l> lines, <k> lines over 253 pieces; preprocessing as given on <m> of <c> lines;
  output v0b-int8.tsv; AI service in the foreground`. `~/satori-audit/tools/reading/v0b_compare.py`
  reads the pulled file.
- `reader-selftest [--es variant int8] [--es input flores200_devtest.mal.txt] [--es arm
  prepack=session|all|off,arena=on|off,threads=2|4] [--ei beam 1-8] [--es worstcase true] [--es cold
  true] [--es firststep true] [--ei lines N] [--es out <name>] [--ei stopafter <ms> | --ei stopload
  <ms>]` (V1 and V2): the reader loaded paced
  as above (the AI model let go first: `Reader selftest int8: the AI model was let go first`; with
  `cold`, the files' cached pages dropped first: `... cached pages dropped first for 11 of 11
  files`), each line read at the beam, one output row a line: `line, ms, first_ms, pieces, parts,
  tokens, steps, mean_log_prob, capped, english` (`first_ms` with `firststep`: the same line's
  first part read once more, cut at one token, the encoder and decoder_model's step, timed apart
  from the line's own `ms`), the memory lines of the load and release after the rows (`# Memory:
  ...`). `worstcase` reads one source instead, the first 253 pieces of the lines with the end held
  back to 255 steps at the beam (V1's worst case). Its output's own name holds every setting a run
  could differ in, `readings-<variant>-<arm>-beam<b>[-worstcase][-firststep][-lines<N>][-<input
  without .txt and .mal, its other points dashes, when not FLORES' devtest>][-stopafter<ms> or
  -stopload<ms>][-cold].tsv` (`-smoke` for smoke.mal.txt, `-v2b-noise` for v2b.noise.mal.txt; V2's run:
  `readings-int8-prepack-session-arena-on-threads-4-beam4-cold.tsv`), so no run overwrites
  another's; a run of the same settings overwrites its own, which memtest.sh pulls into its run's
  folder first. Before the load: `Reader selftest int8: RssAnon <x> -> <a> MB, the Java heap
  collected and the native heap purged (M_PURGE_ALL) before the load`. It ends 11 s after the
  release, so the release's memory line 10 s after comes first: `Reader selftest int8 done: 1012
  lines (<k> in parts, <e> empty, <c> capped), <p> pieces, <t> tokens, <r> decoder runs, in <ms> ms
  (<x> ms a line, <y> ms a run, the encoder and first step included); load <ms> ms; RssAnon <a> MB
  before the load, peak <p> MB, <b> MB after the release (<z> MB before its collection and purge);
  prepack=session,arena=on,threads=4, beam 4, cold; output <file>; AI service in the foreground`.
  The two ends are read alike, each after a collection of the Java heap and a purge of the native
  heap, so V1's "within 50 MB of its baseline" compares like with like: the baseline without what
  the AI model's free (which purges nothing) or an earlier release left in the allocator, the end
  without the vocabularies' Java heap the reader dropped; the figure in brackets is the one right
  after the release's own purge. The peak is sampled every 100 ms from before the load to after
  the release. `<y> ms a run` is all of the readings' ms over their decoder runs, the
  preprocessing, SentencePiece, the encoder and decoder_model's first step included, so it is not
  D2's per-token figure; with `firststep` the line adds `; first steps <f> ms a line, <z> ms a run
  after them` inside the brackets, decoder_with_past's step (D2: beam 4 at most 25 ms). The worst
  case: `done, the worst case: 253 pieces, 254 tokens, 255 decoder runs, capped, in <ms> ms (<y> ms
  a run, the encoder and first step included); ...`, or, from lines with fewer pieces, `W ...
  refused: the worst case needs 253 pieces, and <input>'s lines hold <n>; no output written`, an
  earlier output of that name left as it was. `~/satori-audit/tools/reading/score.py` reads the
  pulled file (chrF++ against FLORES' English, D5's length band, the speed), and
  `~/satori-audit/tools/memtest.sh <variant> reader` runs it with the memory samples.
- Since 0.8.0-dev2 a reader selftest makes a stop of its own, V1's stop timing without a screen or
  a key. `--ei stopafter <ms>` loads and reads as usual and, `<ms>` after the reading began (the
  reader loaded, its first line's reading under way; with `firststep` that line's first step counts
  in), makes the cancel a recording's start makes: the selftest's reading is cancelled, which
  reaches the engine's cancel through the data source's `stoppable()`. `--ei stopload <ms>` makes it
  `<ms>` after the creation of the three sessions began, the part of the load that ONNX Runtime's
  load-cancellation flag stops: the pre-read, the parse room, SentencePiece's model and the two
  vocabularies come before it, and nothing interrupts the vocabularies' parse (about 150 ms for
  vocabularies of the real ones' size on the emulator), so a stop there would time the parse's rest,
  not the flag. Not both. The data source's own line gives the figure, `Reader <id> Run stopped <x> ms after the
  cancel` for the Run under way or `Reader <id> load stopped <x> ms after the cancel` for the load;
  the reader is released and the native heap purged as after every cancel, with the memory lines of
  the load and the release (the selftest still ends 11 s after the release); no readings file is
  written and no `.partial` left, and an earlier file of that name stays. The run ends with its
  memory figures, then exactly one line:

  ```
  Reader selftest int8: RssAnon <a> MB before the load, peak <p> MB, <b> MB after the release (<z> MB before its collection and purge)
  Reader selftest cancelled: stopafter <ms>, the cancel came inside a Run; the reader is let go
  Reader selftest cancelled: stopafter <ms>, the cancel came between Runs; the reader is let go
  Reader selftest cancelled: stopload <ms>, the cancel came inside the load; the reader is let go
  Reader selftest cancelled: stopload <ms>, the cancel came after the load; the reader is let go
  ```

  Where the cancel came is where the data source's stop found the reader as it reached the
  engine, the stop the data source's own line goes by: `inside a Run` comes with a `Run stopped`
  line and `inside the load` with a `load stopped` one; `between Runs` (the preprocessing,
  SentencePiece, the English, a row written) has neither, the next Run not run; `after the load`
  has a `Run stopped` line when a Run was under way. A stop run's reader is the app's, wired as the
  app wires it, on native calls the selftest watches for that. A stop due after the reading's end
  never comes: the run ends as any other, its output named with the stop
  (`readings-...-stopafter<ms>[-cold].tsv`, so it never takes another run's name), and its last line
  adds `; stopafter <ms> never came: the reading ended first` (or `stopload`) before `; AI service
  ...`. Its refusals: `W Reader selftest refused: stopafter and stopload don't go together`, `W Reader
  selftest refused: stopafter takes a number of ms, not <x>` (or `stopload`).

Both selftests keep the memtest's rules (`DebugExclusive`): they start only while no recorder is
busy, no recording waits to be finished and nobody holds the AI work gate (`W ... refused: a
recorder is busy`, `the AI is busy`), hold the gate and the AI's service to their end, and a
recording that starts stops them, the reader's Run cancelled at once and the reader released
before the gate goes (`W ... stopped: a recording started; the reader is let go`; a capture the
data source sees first: `W ... stopped: a capture opened; the reader is let go`). Their other
endings: `W ... refused: no input file <path>; push one there`, `... refused: <file> not the pinned
file (SHA-256); run reader-download`, `... refused: its files are not on the phone` (the data
source's refusals), `W ... not loaded: the phone stayed short of memory for 10 s
(PacedLoadStoppedException)`, `W ... failed: <exception class>: <message>`, `W ... cancelled: the
screen was closed; the reader is let go`. Outputs are written as `<name>.partial` and renamed once
whole, so a file there is complete. Either selftest takes `--es dir <folder>`, a toy model in a
folder of `files/debug/reader` in place of the build (`~/satori-audit/tools/reading/toy_reader.py`,
for an emulator's run of the whole native path without the model): `Reader files from the folder
toy: a toy model, not the registry's, so not checked against it`, and the lines name the folder.


`seed-malayalam [--ei lines <n>] [--es kind backfill|new|both]` (0.8.0's R9 check; emulator only,
like `seed`): synthetic Malayalam recordings, made up, of the shape of spoken lines, with a
fragment and an English line among them. `backfill` writes two as an older version finished them,
without readings: `ml-seed-junk`, named, noted and with a to-do by the app, none touched, and
`ml-seed-edited`, whose name was typed with Rename, whose note was saved in the editor and one of
whose two to-dos was changed a minute after it was found; the backfill of readings queues both at
the next start of the app. `new` writes one just stopped and hands it off to the finishing queue
(`ml-seed-new-<ms>`). `Seeded <n> Malayalam recordings of <lines> lines: <ids>` under DebugTools.
On an emulator the reader is the toy one (`~/satori-audit/tools/reading/toy_reader.py`, the
eleven files served with a manifest and fetched with `firstrun-source` and `reader-download`),
whose readings D5 empties; the English lines give the notes text enough to be asked for.

`mark-owner-edits --es session <id>` and `restore-before-redo --es session <id>` (0.8.0's R9, on the
phone too): before database version 13 nothing recorded a rename or a note edit, so the backfill
takes a name and notes of before for the app's. The first marks the recording's name and notes as
the owner's (`name_by_owner`, `edited_at`), so a finishing made again leaves them; run it, for a
recording he says he renamed or whose notes he edited, before Satori's main screen first starts on
0.8.0-dev3, where the backfill begins: `mark-owner-edits: <n> of the name and notes of <id> marked
as the owner's`, or `W mark-owner-edits: no recording <id>`. The second puts back what a finishing
made again replaced, as it was kept then, marked his, and leaves the recording done (no more made
again): `restore-before-redo: <id> has the name and the <n> notes it had before it was made again`,
`W restore-before-redo: nothing was kept of <id>`, or `W restore-before-redo refused: the finishing
queue is at work`. Both under DebugTools, ids and counts only.
### Screens

- `SessionsViewModel` (home): `E Failed to load ideas` with its stack trace; home then shows no
  ideas row. `Recording <id> started from home in <ENGLISH_OR_GERMAN|MALAYALAM>` (since 0.6.0)
  for each recording the grille or "Record as" starts: the language it is made in, the one picked
  above the categories, else the one-tap Record's (English or German while the Malayalam model
  isn't on the phone, and for Translate). `W Failed to store the home language` and `W Couldn't
  look for the Malayalam model`, with the exception: the recording starts all the same.
- `IdeasViewModel`: `E Failed to load ideas`, `E Failed to load the recordings of the ideas` and `E
  Failed to change an idea` (a make-a-to-do, delete or Undo that failed), each with its stack trace;
  the page says so.
- `SessionDetailsVM` (a recording's page): `Find again on the live model` or `Find again on Gemma 4
  E2B` (`W Gemma 4 E2B could not be loaded, find again on the live model` with the cause), then
  `Find again: <n> to-dos, <m> ideas in <s> s`, or `W Find again: nothing stored after <s> s` when
  it couldn't run or failed (the page says why). `E Find again: LLM initialization failed`, `E Find
  again failed`, `E Failed to load the to-dos of the recording`, `E Failed to change a to-do`, and
  `E Error releasing the LLM after the work of the recording's page` (it was `... after rewriting
  the notes`; the rewrite's `Rewrite on ...` lines are as they were). A recording that starts
  stops either at once: `Rewrite of <id> stopped: a recording started; what was there stays` (or
  `Find again of <id> stopped: ...`), and the recording's page, opened again, says so (none when
  the new notes or the finds were being stored, which runs to its end). Since 0.8.0 Rewrite notes,
  Find again and Text again first make the readings a Malayalam recording's lines lack, before the
  model (`SatoriFinishing`'s `Readings of <id> for <work>: ...`, above); without them the work
  doesn't run, the page says it failed, and its `Rewrite on ...` or `Find again on ...` line never
  comes.
  "Change type" (since 0.5.3): `Recording <id> changed to <category id> (<mode>) on its page`,
  then, when the AI can write the notes and the category records in another mode, the rewrite's
  own lines (`Rewrite on ...`, a stop as `Rewrite of <id> stopped: ...`, after which the type stays
  changed and the old notes stay). A category in the same mode (Voice note to Idea, say) only files
  the recording under it: no model loads, and the notes stay as they are.
  `W Recording <id> not changed to <category id>: it or the category is gone, or translates`, or
  `E Recording <id> not changed to <category id>` with its exception: nothing changed. The
  sheet's language row (since 0.6.0): `Recording <id> changed to <ENGLISH_OR_GERMAN|MALAYALAM> on
  its page`; only the language is stored, the transcript and its model stay. English or German
  needs no model on the phone there, so a recording can always go back to it. `W Recording <id> not
  changed to <language>: it is gone or translates, or the Malayalam model isn't on the phone`, or
  `E Recording <id> not changed to <language>` with its exception: nothing changed.
- `RecordingAudioVM`: `Audio of <id> deleted from its page`, and `Transcribe again of <id> stopped:
  a recording started; the transcript stays` (none when the new transcript was being stored, which
  runs to its end). Transcribe again in Malayalam (since 0.6.0): the sheet offers "Malayalam
  (AI4Bharat)" for any recording but a Translate one, once its model is on the phone, at 3 s plus
  0.15 s per second of audio (an 18-minute meeting: about 3 min). Its transcript makes the recording
  Malayalam (Parakeet's makes it English or German again). The run itself is `SherpaOnnxDataSource`'s
  `Transcribed ... ms of audio again with indicconformer-ml-int8 in ... ms`, its summary line, and in
  a debug build the memory lines `load of indicconformer-ml-int8` and `release of
  indicconformer-ml-int8` around it.
  Since 0.6.1 a transcript in another language than the old one has the recording's text made
  again: one that changes the recording's language, or replaces a transcript with a line in the
  other script (Parakeet's lines before a switch to Malayalam while recording, or a language
  changed on the page, which leaves the transcript). The sheet says so on that model's line, where
  the page can do it (AI notes on with a model, no Translate recording); any other Transcribe again
  leaves the name, notes, to-dos and ideas as they are. Once the new transcript is stored:
  `Recording <id> transcribed again by <model>: in <ENGLISH_OR_GERMAN|MALAYALAM> now, its name,
  notes, to-dos and ideas to be made again` (0.6.0 said `in MALAYALAM now, its notes to be written
  again` and started Rewrite notes, whose topic was the old name). The page then makes them again
  (`SessionDetailsVM`), each in place of what the old transcript gave the recording only once it is
  made (since the 0.6.1 refix; the first 0.6.1 build took the old name and finds away as the
  transcript was stored): `Text again on the live model` or `Text again on Gemma 4 E2B` (`W Gemma 4
  E2B could not be loaded, text again on the live model` with the cause), then `Text again of <id>:
  <notes>, <name>, <to-dos>; found before: <n> removed, <k> kept in <s> s`, where the notes are
  `notes written` (without the old name as their topic, in place of the old ones), `too little
  text for notes, none kept` or `notes failed, the old ones stay`, the name `name written` (in place
  of the old one) or `no name written` (the old one stays: the owner typed one meanwhile, too little
  was said, or no title could be written), and the to-dos `<n> to-dos, <m> ideas found`, `no
  search` or `the search failed`. `found before` counts the to-dos and ideas the old transcript had
  given it: `removed` went in the write that stored the new finds (each is also a `SatoriTodos`
  line, `To-do <id>: IDEA -> gone (transcribed again)`), and `kept` stay, the owner's edited,
  ticked, kept or otherwise changed ones, and all of them when the search stored nothing (it
  failed, or the model couldn't read all of the transcript, which then stores nothing). A Malayalam
  recording's are all English. `E Text again of <id> failed` (the recording is gone or its lines or
  to-dos can't be read) and `E Text again: LLM initialization failed` say why nothing was made, and
  then nothing was changed. A recording that starts stops it as it stops Rewrite notes: `Text again
  of <id> stopped: a recording started; what was there stays`, and the page opened again says
  which part stopped, the notes or the search for to-dos and ideas (none once the search has
  stored what it found); what the steps before it made stays, and the old text the steps after it
  would have replaced stays too. Refused (the AI is busy, a recording waits to be finished) or with
  the page left, it changes nothing.
- `MindMapVM`: `Older recordings: <n> mapped, <m> without topics, <k> summaries in <s> s`, or `...,
  <k> summaries, gave way to finishing after <s> s` when a recording to finish came first (`gave
  way to a recording` when a recorder left idle). A recording that starts stops the map's work at
  its next recording or summary, or as the view closes: `The map's work stopped: a recording
  started; what is mapped stays`. The map's work frees the model it loaded as it ends, also then
  (`InferenceEngineImpl`'s `Model unloaded!`). Since 0.8.0 it first makes the readings its
  recordings' Malayalam lines lack, one recording at a time, before the model loads (`Readings of
  <id> for The map: ...`); a recording whose readings can't be made now waits for a later run. A
  recorder that leaves idle stops those readings at once, the reader released, and no model loads
  then: the same `The map's work stopped: a recording started; what is mapped stays`. Notes that
  hold a Malayalam letter (an older version's, or the owner's) are no input for its model: it reads
  the readings then.
- `DebugTools` (debug builds, Transcribe again from adb): `Debug transcribe done: session=<id>
  model=<model> outcome=<outcome> in <ms> ms`, or `W ... failed`. It runs only as the recording's
  page would, so `W Debug transcribe refused: session=<id>, a recorder is busy` (or `the AI is
  busy`, also while a recording waits to be finished), and a recording started meanwhile wins: `W
  Debug transcribe cancelled: session=<id> model=<model> after <ms> ms, a recording started; the
  transcript stays`. Like the page's run it holds the AI's service under `transcribe:<id>`
  (`LlmProcessingController` says `Starting LLM processing service for transcribe:<id>` when it is
  the first holder), so a run whose screen goes off stays in the foreground group, as the page's
  would (`cpus=0-6` on its summary line); before 0.5.1 it held none and went to the background
  group. The speech models' time estimate (`SpeechModelChoice.transcribeTime`) is the
  time in Android's foreground scheduling group (cores 0-6), where Transcribe again runs once the
  screen goes off under the AI's service and the better transcript after Stop runs under the
  finisher's: the load from `Speech model <model>: 4 threads` to `vad initialized for FILE`, the
  recognition from `Transcribed ... in` (2026-10-13). The `done` lines of 2026-10-12 came from runs
  with the screen off and no service, in the background group on the three little cores (0-2),
  4 to 7 times as long as in front, about 2 to 4.5 times the estimate; the better transcript after
  Stop under the finisher's wake job alone runs there too, and goes by those times
  (`SpeechModelChoice.backgroundTranscribeTime`). Measure it again after any change to the speed
  of Transcribe again, one model at a time with no AI model loaded, in the foreground group: a
  debug run with the screen off, whose summary line says `cpus=0-6`.
- `DebugTools` (debug builds, since 0.7.2, a finishing step run again): `adb shell am start -n
  com.satori/com.satori.ui.debug.DebugToolsActivity --es action rerun --es session <id> --es step
  notes|name|todos|reading [--es times 1-10] [--es temp 0-2] [--es from notes|transcript] [--es store
  true] [--es reader on|off] [--es model 1b|e2b] [--es window 1|2]` (`--ef temp` too). It runs as the transcribe job does: refused beside a recorder, while a
  recording waits to be finished or the AI is held, and stopped by a recording that starts; it holds
  the AI work gate as `debug-rerun` and the AI's service as `rerun:<id>`, loads the finishing's model
  (Gemma 4 E2B with its room, else the live model; paced as every load) once, and frees it at the
  end, whatever happens. Its screen goes to the back as the memtest's does (`rerun runs with its
  screen in the back`; `--es front true` keeps it in front). Notes run the whole pass for a
  recording whose notes the pass wrote (`END_OF_SESSION`, every recording finished after Stop's
  whole-file pass) or whose transcript is
  longer than a final note reads, else a final note on the whole transcript; a name is one answer
  from its notes (`from notes`, the default) or its transcript; to-dos read the noted list as at the
  finishing (without the recording's own finds and those found after them). Each run keeps its raw
  answers (`rerun-...` below) and logs its drops on the usual `SatoriNotes` and `SatoriTodos` lines,
  just before its own. With `temp` the runs' calls, and only theirs, are sampled at it
  (`AiSampling`; the inference line's `sampling=`); the next call is at the default again. Nothing
  is stored unless `store true`: then the last run's notes replace the recording's, its name goes
  only into a recording without one, and its to-dos' last run is a Find again that stores its
  finds.

  Since 0.8.0 (R8, for the design's V3 and V4) the notes, the name and the to-dos read the recording
  as the finishing does, each line only the reader reads as its reading: the readings it lacks are
  made first, before the model, as the page's work makes them (`Readings of <id> for Debug rerun:
  ...` under SatoriFinishing; they are stored, as the finishing stores them, being what the AI steps
  read, not what they write). `--es reader off` reads the lines as they were said instead,
  Malayalam and all, with the orders to read it, as 0.7.3 did: the baseline for comparison, which is
  never stored (`store true` with it is refused). `--es model 1b` runs on the live model alone,
  `--es model e2b` on Gemma 4 E2B as the finishing loads it, with its room, or not at all; without
  `model`, the finishing's choice. `step reading` takes neither: it loads no AI model, reads every
  line only the reader reads again with the reader alone (the step lets any AI model go first),
  stores nothing, and keeps each run's lines in an answer file,
  `files/debug/todo-answers/<session id>-rerun-reading-<run>-lines.txt`, tab-separated, a header and
  then one row a line read: its number, its id, what D5 made of it (`kept`, `fragment`, `floor`,
  `band`, `repeats`, `blank`, `not_read`), its source pieces and English tokens, its mean
  log-probability, whether D15's cap cut it, the line as it was said and the reader's English (also
  one D5 emptied, to be rated). `--es window 2` (reading only; V3's arm of design D1) reads each two
  lines only the reader reads, one after the other, as one text, a last one left over alone, D5's
  rules applied to the window: its file is `...-rerun-reading-<run>-windows.txt`, a row a window,
  with its lines' ids by commas and their texts one after the other. A window over the model's 253
  source pieces is cut at a word by the reader, as a long line is (D15): its pieces say so. Pull them
  with `adb exec-out run-as com.satori cat files/debug/todo-answers/<file>`; they hold what was said,
  so they never go to the log. The lines:

  ```
  Debug rerun <step> of <id> on <model>, <n> runs at temp 0.30 top-k 40 top-p 0.95 min-p 0.05 (the default|an override)[, from its notes|transcript][, reader off]
  Debug rerun reading of <id> on the reader, <n> runs[, 2 lines read together], nothing stored
  Debug rerun <step> of <id> <k>/<n>: <counts> in <ms> ms
  Debug rerun <step> of <id> done, stored nothing|the last run
  W Debug rerun of <id> refused: a recorder is busy|the AI is busy|no such recording|step must be notes, name, todos or reading|times must be 1-10|temp must be 0-2|from must be notes or transcript|store must be true or false|reader must be on or off|model must be 1b or e2b|step reading takes no temp, reader or model|store needs the reader on|window must be 1 or 2|window is for step reading
  W Debug rerun of <id> stopped: a recording started
  W Debug rerun <step> of <id> <k>/<n> failed: <exception class>
  W Debug rerun <step> of <id> not started: <why the model didn't load>|its readings are missing (no reader|held off|stopped|no memory|failed)
  ```

  `<model>` is `Gemma 4 E2B`, `the live model` or `the live model, Gemma 4 E2B not loaded (<class>)`;
  with `model e2b` and no E2B the run doesn't start: `Gemma 4 E2B is switched off or not on the phone`
  or `Gemma 4 E2B not loaded (<class>)`.

  The counts: for notes `a final note, <p> points, <t> titled, <a> with action items` or `<n> notes
  of the pass, ...` or `no note`; for a name `a name kept (<n> chars)`, `a name kept (<n> chars,
  generic but in its subject's words)` for one the finishing puts a specific note title before, or
  `no name, echo|rules|other script|generic|empty` (`rules` and `other script` since 0.7.3),
  `no name, too few words to ask for one`, `no name, the model
  failed (<class>)`; for to-dos
  `<n> found, <i> ideas, <s> suggested, <c> over the suggestions' cap`; for a reading (since 0.8.0:
  every line only the reader reads, read again as the Reading step reads one, the AI model let go
  first, nothing stored) `<n> lines|windows of 2 lines read|stopped for a capture|not loaded
  (<why>)|stopped for want of memory|failed (<class>) in <ms> ms, <k> kept, <e> empty (<a>
  fragments, <b> under the floor, <c> outside the band, <d> repeating, <f> blank, <g> not read)`,
  each count of windows when it read windows. None holds a text.

The to-dos and ideas add no line of their own: their counts are in the finishing's to-do line, and
MainActivity says `Moved <n> to-dos to the ideas` once per install. In a debug build each answered
window of the to-do search is kept as the model gave it, in the app's private
`files/debug/todo-answers/<session id>-<window>.json` (it holds what was found, so it never goes to
the log; `adb exec-out run-as com.satori ls files/debug/todo-answers`); the ideas come in the same
answers. Since 0.7.2 the notes' and the name's answers are kept beside them, one file per call:
`<session id>-<tag>.txt` with the tags `notes-final-0` (the last note after Stop; `-1` for its
second answer), `notes-part<k>-0|1` and `notes-merge-0|1` (the whole pass), `name-0` and `name-1`
(the name's two answers), and `rerun-<step>-<run>-<the call's tag>` for the debug rerun above. A
step run again first deletes its own old files (a pass deletes all the notes' files, as its notes
replace them all). Deleting the recording deletes all of them (`SatoriDebug` says `W <n> raw answers
of a deleted recording were not deleted` if some stay).

### Pull the logs

```sh
adb pull /sdcard/Android/data/com.satori/files/logs ~/satori-field-logs/$(date +%F)
adb shell dumpsys activity exit-info com.satori > ~/satori-field-logs/$(date +%F)/exit-info.txt
adb shell dumpsys dropbox --print data_app_crash data_app_anr data_app_native_crash > ~/satori-field-logs/$(date +%F)/dropbox.txt
```

The dropbox keeps crash and ANR reports for a few days, also of crashes the field log missed.

## Silent test recordings (debug builds)

`feature-stt/.../DebugAudioFeed.kt` lets a debug build take its next recording from a WAV file instead
of the microphone, at real-time pace, then silence until it is stopped. The whole pipeline runs:
speech recognition, notes, name, to-dos and reminders, with nothing audible.

```sh
# 16 kHz mono 16-bit PCM; macOS can make one from text
say -v Daniel -o /tmp/feed.aiff "Remind me to call the bank tomorrow at ten."
afconvert -f WAVE -d LEI16@16000 -c 1 /tmp/feed.aiff /tmp/feed.wav
adb push /tmp/feed.wav /sdcard/Android/data/com.satori/files/debug/feed.wav
adb shell touch /sdcard/Android/data/com.satori/files/debug/feed.wav   # push keeps the old time
adb shell am start -n com.satori/com.satori.quickkey.QuickKeyActivity   # start
adb shell am start -n com.satori/com.satori.quickkey.QuickKeyActivity   # stop, later
```

The file is renamed to `feed.wav.used` when a recording takes it, so it plays once. A feed older
than ten minutes is deleted instead of played, so one left behind by a test never replaces the
microphone in a real recording. Release builds and an absent file record from the microphone as
usual. `say -v Anna` gives German.

## Kept audio

With "Keep audio of recordings" on (the default), each recording keeps what the speech
recognizer heard, from the microphone or a debug feed alike: `files/audio/<session id>.wav` in
the app's own storage (16 kHz mono 16-bit PCM, about 2 MB a minute, left out of backup like the
rest) and `<session id>.idx` beside it, which says when each capture began and where each
transcript line was said. Deleting a recording deletes both. Its page plays the audio, shares it
as a file named like the Markdown export, and can transcribe it again with the speech model on
the phone, which is how speech models are compared on the same recording.

Settings' "Compress older audio" replaces an older recording's WAV with `<session id>.m4a` (AAC,
32 kbit/s, about 8 times smaller; it still plays, is shared as an .m4a and can be transcribed
again), and "Delete older audio" deletes it, both off by default; the `.idx` stays. A recording's
"Keep this audio" leaves its audio out of both. AAC is lossy: a recording meant for comparing
speech models needs "Keep this audio".

On a debug build adb can copy it too:

```sh
adb exec-out run-as com.satori ls files/audio
adb exec-out run-as com.satori cat files/audio/<session id>.wav > recording.wav
```

## The cover's camera band

On the cover in Motorola's full-screen mode the window takes in the band with the cameras (see
`ui/design/GrilleCameraBand.kt` and DEV_SETUP.md for an emulator). Android treats that band as
covered: when the control that has input focus lies in it, Android scrolls the whole window until
the control clears the band, and every screen in the window is then drawn off by as much. Since
0.6.1 nothing in the band takes input focus, and a line under `SatoriCover` says when the window is
drawn off its top anyway, on each frame its offset changes and once more when it is back: `lower`
with the band at the top (the cover upside down), `higher` with the band at the bottom (upright).
Android animates such a scroll, so one scroll can write a few lines with a growing offset before
it settles; the last one is where it stayed.

```
W SatoriCover: Window drawn 291 px lower than its top: Android scrolled it to show the focused control
W SatoriCover: Window drawn <n> px higher than its top: Android scrolled it to show the focused control
W SatoriCover: Window drawn at its top again
```

0.6.0 had no such line. Its screenshots of 2026-10-15 (the cover upside down) show the window drawn
291 px lower: just what moves home's grille, 58 px from the top of the 349 px band, clear of it.
Home and the recording's page started some 680 px down and ran off the bottom of the screen.
