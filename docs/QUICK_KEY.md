# Quick key

A power-key double-press starts a Satori recording in the category chosen in Settings. With the
default settings, the next double-press stops it. It uses Motorola's My Key, the same hook as the
existing Qira binding: no root and no accessibility service.

Status: the entry activity, its settings, the rules for what a press does and MainActivity's
handling of a press are built, and the binding below was checked on the razr on 2026-10-11.
Settings has a "Quick key" row that opens the "Power key" sheet (see "Settings"). Binding the key
is a one-time adb step; Satori can't set it itself (see "Can Satori set the key itself?"). From
version 0.5.0 on, a press right after Stop records again at once: the recording that was stopped
is finished in the background (see "What a press does").

| Part | Where |
|---|---|
| Entry activity | `app/src/main/java/com/satori/quickkey/QuickKeyActivity.kt` |
| Repeat filter | `app/src/main/java/com/satori/quickkey/QuickKeyRepeatFilter.kt` |
| Intent contract to MainActivity, and what a press does | `app/src/main/java/com/satori/quickkey/QuickKeyRequest.kt` |
| Settings and storage | `data/src/main/java/com/satori/data/quickkey/` |
| Manifest entry | `app/src/main/AndroidManifest.xml`, `QuickKeyActivity` |
| Strings | `app/src/main/res/values*/strings_grille_quickkey.xml` |

## How a press reaches Satori

1. A power double-press makes My Key (`com.motorola.mykey`) run the intent URI stored in
   `Settings.System` `my_key_tap_app`, as long as `Settings.Secure`
   `double_tap_power_button_gesture` is `2`. Verified with Qira on the razr on 2026-10-08.
2. `QuickKeyActivity` drops the press if it comes within a second of the last one it forwarded.
   Otherwise it reads the quick key settings, starts MainActivity with the request and finishes,
   all inside `onCreate`. It draws no window.
3. MainActivity decides from what Satori is doing whether the press starts, stops or cancels a
   recording (see "What a press does"), and does it.

`double_tap_power_button_gesture` is an AOSP key (`Settings.Secure.DOUBLE_TAP_POWER_BUTTON_GESTURE`,
hidden but readable): `0` opens the camera, `1` the wallet. `2` is Motorola's value for My Key,
which the `razr` helper relies on.

### The binding

| Target | `my_key_tap_app` |
|---|---|
| Qira recording (today, `razr button record`) | `intent:#Intent;launchFlags=0x00040000;package=com.lenovo.qira;action=com.lenovo.qira.action.QUICK_LAUNCH;S.launch_with_action=pay_attention;end` |
| Satori | `intent:#Intent;action=com.satori.action.QUICK_KEY;launchFlags=0x10000000;component=com.satori/com.satori.quickkey.QuickKeyActivity;end` |

- The URI names the action as well as the component. `Intent.parseUri` gives every `#Intent;`
  URI the action `android.intent.action.VIEW` unless it names one (AOSP `Intent.parseUriInternal`,
  main branch, read on 2026-10-10), and VIEW doesn't match `QuickKeyActivity`'s intent filter.
  That still gets through today: Android checks an explicit intent against the target's filters
  only when the target opts in (AOSP `SaferIntentUtils`: on Android 15 a compat change that is
  off by default, on Android 16 the component's `android:intentMatchingFlags`). With the action,
  the intent matches the filter, so hardening the manifest later can't break the key.
- `0x10000000` is `FLAG_ACTIVITY_NEW_TASK`. Qira's URI carries only `FLAG_ACTIVITY_NO_USER_ACTION`
  (`0x00040000`) and still works, so My Key probably adds a new task itself; Satori sets the flag
  anyway.
- `Intent.parseUri` reads `action=` as it stands, `component=` with
  `ComponentName.unflattenFromString` and `launchFlags=` as a hex or decimal number (AOSP
  `Intent.java`).

## Bind it with adb

This is the only way to bind the key (see "Can Satori set the key itself?").

```sh
adb shell settings put secure double_tap_power_button_gesture 2
adb shell "settings put system my_key_tap_app 'intent:#Intent;action=com.satori.action.QUICK_KEY;launchFlags=0x10000000;component=com.satori/com.satori.quickkey.QuickKeyActivity;end'"
adb shell settings get system my_key_tap_app       # check
```

The inner single quotes matter: without them the phone's shell splits the URI at each `;`.

Try it without the key. `am start` parses an `intent:` URI with `Intent.parseUri` (AOSP
`Intent.parseCommandArgs`), the parser this URI format is made for, so the first line below should
send what a press sends. Watch the log line the activity writes on every press:

```sh
adb shell "am start 'intent:#Intent;action=com.satori.action.QUICK_KEY;launchFlags=0x10000000;component=com.satori/com.satori.quickkey.QuickKeyActivity;end'"
adb logcat -s SatoriQuickKey     # Quick key: record as voice-note in ENGLISH_OR_GERMAN, stop if recording: true
```

`am start -n <component>` and `am start -a <action>` send different intents (no action, or no
component), so they don't show whether the binding itself works.

Back to Qira: `razr button record` (Qira recording), `razr button qira` (prompt bar) or
`razr button camera`. The helper is `~/.local/bin/razr`, outside this repository; a
`razr button satori` that writes the lines above is planned. Choosing an app for the double-press
in the phone's Settings rewrites `my_key_tap_app`, so the binding has to be set again afterwards
(seen with Qira).

## Settings

`QuickKeySettings`, stored in a DataStore file of its own, `files/datastore/quick_key.preferences_pb`.
Like every file of the app it is excluded from backup and device transfer
(`res/xml/data_extraction_rules.xml`).

| Setting | Default | Meaning |
|---|---|---|
| `categoryId` | `voice-note`, the voice-note starter category | Category a quick recording is filed under |
| `stopOnSecondPress` | `true` | A press while recording stops it, and one while the recorder gets ready cancels it; when off, such a press only shows Satori |
| `language` | English or German (key absent) | The language a quick recording is made in (since 0.6.0): `ml`, Malayalam, records with AI4Bharat's Malayalam model and writes no live notes |

`QuickKeySettingsRepository` (bound by Hilt to `DataStoreQuickKeySettingsRepository`) has
`observe()`, `get()`, `setCategoryId()`, `setStopOnSecondPress()` and `setLanguage()`. A damaged or
unreadable file gives the defaults, and the activity also uses them if reading takes longer than a
second; a language code this version doesn't know reads as English or German. The repository
doesn't check that the category exists, nor that the Malayalam model is on the phone; MainActivity
does, when it starts the recording: a press set to Malayalam records in English or German while the
model isn't there (`W Quick key: the speech model of MALAYALAM isn't on the phone, recording in
English or German`, `SatoriQuickKey`), and so does a category that translates.

The two classes are plain Kotlin, so they can move to `:domain` unchanged if the settings screen
should depend only on domain interfaces.

### The "Power key" sheet

Settings > Power key opens `QuickKeySheet` (`SettingsScreen.kt`, `QuickKeySheet.kt`;
`SettingsViewModel` reads and writes the settings above). It has one sentence ("Press the power key
twice to start a recording."), the category to record as (the user's categories, from
`CategoryRepository.observeAll()`; the first one is shown when the chosen one was deleted), the
language to record in (English or German · Malayalam, the row of Record as; Malayalam only while
its model is on the phone, else "Download Malayalam in Advanced settings", which opens Advanced
settings; no row for a category that translates), and the "Press again to stop" switch. The choices
apply as they are made.

The setup from a computer is in debug builds only (the owner's decision of 2026-10-15): one quiet row,
"Set up on a computer", at the foot of the sheet opens the three `adb` lines of "Bind it with adb",
with **Copy** and **Share** (`QuickKeySetup` and `QuickKeyAdbSetup` in `app/src/debug`, plain ASCII;
its test, in `app/src/testDebug`, reads that block from this document, so the two cannot differ). A
release build has neither the row, nor the commands, nor the code that makes them.

## What a press does

With the stop switch on (the default), the key is a toggle: a press starts a recording and the
next one stops it. Getting the recorder ready takes a moment (the microphone opens at once and the
speech model loads next to it), and Stop saves the last words before the recording is handed over
to be finished: normally well under a second, at most the recorder's drain guard of 15 s. The
last notes, the AI name, the to-dos and the map are written in the background afterwards, one
recording at a time, while the next one may already be recording; home shows how far they are. A
press must not open a second recorder, and a stop must not get lost, so what a press does depends
on what Satori is doing:

| Satori is | Stop switch on | Stop switch off |
|---|---|---|
| Not set up yet (onboarding) | Shows Satori | Shows Satori |
| Idle | Starts a recording | Starts a recording |
| Getting a recorder ready to record | Cancels that start, as Back would: nothing is recorded | Shows Satori; the recording starts when ready |
| Recording | Stops it, as the notification's Stop does | Shows Satori |
| Saving a recording that was just stopped | Starts a recording once it is saved | Starts a recording once it is saved |

- A press that comes within a second of the last one `QuickKeyActivity` forwarded is dropped as
  a repeat (`QuickKeyRepeatFilter`). Forwarded, a double-press that reaches Satori twice would
  cancel its own start.
- A stop while the recorder gets ready cancels the start rather than stopping the recording once
  it runs, which would only leave a recording of a second or two.
- A press while a recording is being saved waits for the hand-off, at most 16 s, then starts the
  next recording in place of the recorder that saved it. Still saving after 16 s, it only shows
  Satori, and that recorder goes home once it is done. While the press waits, the recorder that
  saves doesn't go home by itself, and Satori stays over the lock screen
  (`ActiveRecorder.startInFlight`), so the lock screen doesn't flash between the two recorders.
  A recording saved this way in which nothing was said is still deleted, but without home's
  "Nothing was said" notice: home is seen next only once the new recording is stopped, and the
  notice would then read as being about that one.
- A quick start opens its recorder directly above the sessions list, so there is never a second
  recorder. The idle recorder it replaces stays on show until the new one comes, so home doesn't
  show in between, over the lock screen either.
- `RecordingSessionManager.sessionState` can't tell these apart. `RecordingService` reports
  `Recording` only when a transcript segment arrives, so not in the first seconds of a recording
  or during silence, and it stops right after the hand-off. MainActivity reads the state from the
  recorder instead (`ActiveRecorder`): `MainUiState.isRecording`, `saving` and
  `isStartingRecording`. A recorder a press has navigated to counts as getting ready before it
  opens, which it can't while the phone is locked, and one that is done saving counts as idle even
  before the screen has taken it home.

`QuickKeyRequest.actionFor()` implements the table; the KDoc of `QuickKeyRequest` says where each
state is read and how MainActivity carries out each answer.

## The intent MainActivity receives

`QuickKeyRequest` builds and parses it, and its KDoc is the contract, including the steps
MainActivity has to take.

| Part | Value |
|---|---|
| Target | MainActivity (explicit), with `ACTION_MAIN` and `CATEGORY_LAUNCHER` |
| Flags | `NEW_TASK`, `CLEAR_TOP`, `SINGLE_TOP` |
| `com.satori.extra.QUICK_RECORD_CATEGORY` | String, never blank: the category id to record as |
| `com.satori.extra.QUICK_STOP` | Boolean: the stop switch (see "What a press does") |
| `com.satori.extra.QUICK_RECORD_LANGUAGE` | String: the language's code, `ml`; absent for English or German (since 0.6.0) |

`QuickKeyRequest.from(intent)` returns null for any other intent, and for intents replayed by
Recents.

### Why the intent and the manifest look like this

Read in AOSP `ActivityStarter.complyActivityFlags` and `Task.java` (main branch, 2026-10-10):

- **Own task for the trampoline.** `taskAffinity=""` and `excludeFromRecents` put `QuickKeyActivity`
  in a throwaway task, so Satori's task keeps MainActivity at its root and Recents never shows the
  trampoline.
- **Launcher-shaped intent.** When a press creates Satori's task, the forwarded intent becomes the
  task's root intent. A later tap on the launcher icon is compared with it
  (`Task.isSameIntentFilter`, which ignores extras); if action and categories differ, Android starts
  a second MainActivity on top. When the task already exists, its root intent stays the launcher's:
  `Task.setIntent` keeps the first intent unless the root activity sets `relinquishTaskIdentity`.
- **`CLEAR_TOP` with `SINGLE_TOP`.** A running MainActivity receives `onNewIntent`. Without
  `SINGLE_TOP`, `CLEAR_TOP` would destroy and recreate it, and the recording screen with it.
  Activities above MainActivity in Satori's task close, for example an app opened from Satori's
  share sheet.
- **Recents.** Recents relaunches a task with its root intent plus
  `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`. That intent can be an old press, so
  `QuickKeyRequest.from()` ignores it. MainActivity also has to skip the request in `onCreate` when
  `savedInstanceState` isn't null, since a fold or a restored process hands the old intent over again.

## Security

- `QuickKeyActivity` is exported without a permission because My Key is another app. Any app on the
  phone can therefore start, stop or cancel a quick recording, just as the key does. It can't choose
  the category or the language (the activity never reads the incoming intent) and can't read
  anything. Android stops
  apps in the background from starting activities (Android 10 and later, with a few exemptions), and
  a recording is visible: Satori opens, and the notification and the microphone indicator appear.
- MainActivity is exported for the launcher, so an app can also send the extras to it directly.
  MainActivity has to treat them as untrusted input, for example an unknown category id, or a
  language code it doesn't know (read as English or German).

## Can Satori set the key itself?

No: binding the key stays the adb step above, and Satori's Settings only explain it
(`g_quickkey_setup_desc`). Read in AOSP `SettingsProvider.java` and `Settings.java` (main branch,
2026-10-10); not tried on the razr, and Motorola could have changed this.

- `my_key_tap_app` is a `Settings.System` key outside `Settings.System.PUBLIC_SETTINGS`. For such
  keys SettingsProvider throws `IllegalArgumentException` at any app that targets API 23 or later,
  unless the app is privileged (part of the system image) or runs as system, shell or root.
  `WRITE_SECURE_SETTINGS` doesn't lift this (`enforceRestrictedSystemSettingsMutationForCallingPackage`),
  so an in-app binding with that permission is not expected to work. adb can write the key because
  `adb shell` runs as shell.
- `double_tap_power_button_gesture` is a `Settings.Secure` key. Writing it needs
  `WRITE_SECURE_SETTINGS`, which adb can grant to a sideloaded app once the manifest requests it
  (`adb shell pm grant com.satori android.permission.WRITE_SECURE_SETTINGS`). That
  alone doesn't point My Key at Satori, so the manifest doesn't request the permission. Any app may
  read the key (it is annotated `@Readable`).
- Reading `my_key_tap_app` should work: SettingsProvider treats keys that the AOSP `Settings`
  classes don't declare as readable (`checkReadableAnnotation`). Settings could then show whether
  the key points at Satori, unless Motorola declares the key without `@Readable`.

## Open checks on the phone

| Check | What to expect | How to check |
|---|---|---|
| Press while the phone is locked | The recorder a press starts shows over the lock screen (`MainActivity.showOverLockScreen`) and records at once; anything else hides behind it again. Over the lock screen the record button stops only on a long press, and a tap says "Hold to stop". A press right after Stop records again without the lock screen flashing. | Lock, double-press, `adb logcat -s SatoriQuickKey`; press again 1-2 s after Stop |
| Phone folded (cover screen) | Unknown whether MainActivity opens on the cover screen (display 1) or waits for unfolding. Satori may have to be allowed on the external display in Motorola's settings first. | Fold, double-press, `RAZR_DISPLAY=1 razr shot` |
| Microphone after a press | Android lets an app start a microphone foreground service only while it is in the foreground; `RecordingServiceController.startService()` returns false otherwise. MainActivity is on screen after a press, so it should start; check locked and folded too. | Once MainActivity handles the press |
| Two presses within a second | The second is dropped, and the log says `pressed again within 1000 ms, ignored` | The `am start` line above twice in one `adb shell` call, separated by `;` |
| A second press while the recorder asks for the microphone (first recording only) | `CLEAR_TOP` probably closes the permission prompt, which runs in Satori's task. With the stop switch on, the press cancels the start anyway; with it off, the recorder would then report the microphone as denied and need another start. | `adb shell pm revoke com.satori android.permission.RECORD_AUDIO`, press, press again while the prompt shows |
| Writing `my_key_tap_app` with `WRITE_SECURE_SETTINGS` | `IllegalArgumentException` (see above) | A debug-only `Settings.System.putString` after `pm grant` |
| Reading `my_key_tap_app` | Readable | A debug-only `Settings.System.getString` |
| Whether My Key's picker offers app shortcuts | Unknown. If it does, a static shortcut could replace the adb step. | Open the double-press setting and look |

## Still to wire

- **`razr button satori`** in the helper, writing the two settings above, with the action in the URI.
