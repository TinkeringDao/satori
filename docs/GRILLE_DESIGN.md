# UI redesign: "Grille"

Status: direction approved 2026-10-08 (Grille, one-tap record, follow-system theme).
Updated 2026-10-10 (wave 2a): numbers, lines, touch targets, errors, notices, sheets and the word
"Notes" for the AI output, as below.

## Intent

A personal, fully offline recorder and transcriber for the razr 70 plus, often started one-handed or
from the cover screen and often in airplane mode. The UI has three jobs:

1. Start recording instantly.
2. Make it obvious that the app is capturing.
3. Make what was said, and what the AI pulled out of it, easy to find later.

The brief is very minimal and retro, inspired by Nothing Phone fonts and graphics.

Constraints:
- Fonts are bundled; nothing is downloaded at runtime.
- English and German only.
- ViewModels and domain logic stay, with feature parity, except for the one-tap flow, the audio
  level signal, the AI title on stop, the Markdown export and the steady timer described below
  (the last three added on 2026-10-09 after the first test on the phone).
- The model download screens are restyled, not rebuilt: the offline model-import work will replace them.

## Visual language

### Colour

| Token | Dark (main look) | Light | Use |
|---|---|---|---|
| `void` | `#000000` | `#FFFFFF` | background |
| `ink` | `#FFFFFF` | `#000000` | primary text, lit dots, primary button fill |
| `graphite` | `#141414` | `#F2F2F2` | raised surfaces: sheets, fields |
| `ash` | `#8E8E8E` | `#6B6B6B` | secondary text, icons, the edge of outlined controls |
| `unlit` | `#2E2E2E` | `#D6D6D6` | hairlines, disabled |
| `mesh` | `#2E2E2E` | `#949494` | dots that are off: the grille's mesh, dot progress and sliders, page dots |
| `rec` | `#D71921` | `#D71921` | the microphone is live. Nothing else is red |
| `scrim` | black 60 % | black 12 % | dims the screen behind sheets and dialogs |

Outlined controls (outline pills, unselected chips, the switch when off) are edged in `ash`, at
least 3:1 on `void` and `graphite`. `unlit` would almost vanish on white, hence `mesh` for dots.

Errors use `ink` text with an outlined warning mark (`ErrorNote`), not red, so that red stays
unambiguous. Not Doto's "!": it is a dotted bar that reads as the overflow icon. Every error is in
the app's language and ends with what to do next; exception text is logged, never shown.

### Type

Two families, both SIL OFL and bundled:

- **Doto** (variable, `ROND` axis 100 for round dots, like NDot). Only digits at 28 sp or larger
  (the timer, lengths, a download percentage) and the wordmark. It is never used for running text
  or below 28 sp. Numbers are set with `DotoNumber`: Doto draws ':' '.' ',' and '%' as five-dot
  crosses, so every character other than 0-9 is Space Grotesk Light. Lengths read m:ss
  everywhere, the timer included (h:mm:ss from one hour on, `formatDuration`). Small readouts
  such as slider values and sizes are Space Grotesk with tabular digits.
- **Space Grotesk** (variable 300–700). Everything else: sentence case, left-aligned, no all-caps
  labels.

| Style | Family | Size / line height | Weight |
|---|---|---|---|
| `timer` | Doto | 64 / 64 (inner), 52 / 52 (cover) | 700 |
| `displayNumber` | Doto | 36 / 40 | 700 |
| `listNumber` | Doto | 28 / 32 | 800 |
| `title` | Space Grotesk | 22 / 28 | 500 |
| `transcript` | Space Grotesk | 17 / 26 | 400 |
| `body` | Space Grotesk | 16 / 24 | 400 |
| `label` | Space Grotesk | 14 / 20 | 500 |
| `caption` | Space Grotesk | 13 / 18 | 400 |

### Shape, space and motion

- 8 dp grid with 24 dp side margins.
- Pills for buttons, circles for icon buttons, 24 dp top radius for sheets. No other radii.
  Every pill and chip is at least 48 dp tall; chips that don't fit a row wrap to the next.
- Hairlines (`unlit`) of exactly one physical pixel separate list rows instead of cards. No
  shadows, no gradients, no tonal surface tints. Text fields are an underline: `ash`, `ink` while
  focused.
- Actions: one `ActionRow` in every sheet, dialog and onboarding step, a quiet way out on the left
  and the filled primary on the right (stacked when a label doesn't fit). Confirmations are
  sheets (`ConfirmSheet`). Short confirmations ("File saved") are a notice at the bottom
  (`NoticeHost`) that goes away by itself and never moves the content.
- Motion only answers an action or shows the live signal: grille dots follow the audio level.
  The timer holds still apart from its seconds; a blinking colon at 64 sp read as flicker on the
  razr (changed 2026-10-09). Respect "remove animations": the grille then shows a steady ring.

## The one bold element: the grille

The record control is a circular grille of dots, like a microphone mesh.

| State | What it looks like |
|---|---|
| Idle | `unlit` dots with an `ink` disc of dots in the centre. Label "Record" |
| Preparing | Dots sweep once around the ring while the models load; recording may already be capturing |
| Recording | Dots light (`ink`) outward from the centre in proportion to the audio level; the centre becomes a `rec` square. Label "Stop" |
| Finishing | Steady ring and no centre; disabled until the session is finalized |

It sits docked at the bottom centre like a camera shutter (112 dp, 48 dp+ touch target) on home and
on the recording screen. On the cover screen it moves to the right of the timer.

## Screens

```
Home (inner)                        Recording (inner)
┌──────────────────────────────┐    ┌──────────────────────────────┐
│ satori                 ⌕  ⚙  │    │ ←   Voice note        Notes  │
│                              │    │                              │
│ Standup with infra team      │    │ 12:47         (Doto timer)   │
│ Today 09:30          24:10   │    │ ● Recording   (rec dot)      │
│ ──────────────────────────── │    │                              │
│ Voice note                   │    │ Transcript   Notes           │
│ Yesterday 18:02       2:41   │    │ ‾‾‾‾‾‾‾‾‾‾                    │
│ ──────────────────────────── │    │ earlier lines in ash …       │
│ …                            │    │ the live line in ink ▌       │
│                              │    │                              │
│          (grille)            │    │          (grille)            │
│           Record             │    │            Stop              │
│    Notes ▾  (mode chip)      │    │ AI ready                     │
└──────────────────────────────┘    └──────────────────────────────┘

Cover screen recording (480 x 410 dp usable)
┌──────────────────────────────┐
│ 12:47                 (grille│
│ ● Recording            Stop) │
│ …the deadline is next week   │
└──────────────────────────────┘
```

| Screen | What changes |
|---|---|
| Home (`SessionsScreen`) | A Doto wordmark, search and settings icons. Hairline list: title, relative date, Doto duration. Docked grille: one tap creates a session with the quick-record defaults and opens Recording, which starts by itself. A mode chip under the grille opens the new-recording sheet. Empty state: "Tap the grille to record". |
| New-recording sheet (replaces `NewSessionDialog`) | Mode, language (English or German), name/topic, AI timing. "Record" starts recording with these choices and remembers mode and language as the quick-record defaults. |
| Recording (`MainScreen`, `TranscriptionSection`, `InsightsSection`) | Doto timer, rec state line, text tabs, transcript (finished lines in ash, live line in ink with a dot cursor), insights as hairline blocks (title, Markdown summary, action items as dot bullets). Grille docked at the bottom. One status line for the AI. The cover screen uses a compact layout. When recording stops, a recording without a name gets a short AI title about what was said; a name the user gave is never replaced. |
| Session details | Header with the title (tap to rename), date, Doto duration. Text tabs. Rows for segments and insights with edit actions. An overflow sheet to share or save the recording as a Markdown (.md) file, make an AI copy, or delete it. |
| Search | Bare field with a hairline underline. Results show a bold match in the snippet and the source name. |
| Settings | Grouped hairline rows, hardware-style toggles, a dot-step slider, a sheet for prompt editing. Same settings as today. |
| Licences | Hairline list with expandable texts; adds the Doto and Space Grotesk OFL. |
| Onboarding, STT/LLM download | Restyled with tokens and components only; the flow is unchanged until offline import replaces it. |

## Behaviour changes

1. **One-tap record.** Settings gain `quickRecordMode` (default Simple Listening) and
   `quickRecordLanguage` (device language if English or German, else English). The recording route
   gains `autostart`. With `autostart=true` the screen starts streaming as soon as `initialize()`
   finishes and permissions are granted.
2. **Audio level.** `SttDataSource` exposes `audioLevel: StateFlow<Float>` (0..1, RMS of each 100 ms
   read, smoothed), passed through `SttRepository` to `MainViewModel` as a separate `StateFlow`, so
   that 10 Hz updates don't recompose the whole screen.
3. **Languages.**
   - UI locales: English and German only (`values`, `values-de`); the other 23 locale folders are
     removed.
   - Language pickers offer English and German.
   - `SupportedLanguages.ALL` stays in code for now: the STT model is multilingual, and the model
     choice is under research.
4. **AI title on stop.** After the last insight, while the model is still loaded, `stopStreaming()`
   names an unnamed recording through `AutoNameSessionUseCase`: a 2-6 word title from the
   transcript, or from the insights of a long recording (`prompt_session_title`, English and German,
   written in the session's output language). `SessionTitles` drops labels, quotes and Markdown and
   rejects titles that only say "summary" or "recording"; the first specific insight title is the
   fallback. `setNameIfUnnamed` writes only while the name is empty, so a name the user gave always
   stays. "Stop and go back" now leaves once this finishing step is done.
5. **Markdown export.** The details menu shares the recording as a `.md` file (FileProvider
   `${applicationId}.exports`, files under `cache/exports/<session id>/`, deleted after a day or with
   the recording) or saves it through the system "Save to" picker. The file has the name, date and
   length, the insights with their to-dos, and the transcript with the time of each line.
6. **Steady timer.** The timer no longer blinks; the ViewModel ticks on whole seconds of
   `SystemClock.elapsedRealtime()`.

## Non-goals

- Offline model import.
- Firebase removal.
- Saving audio.
- NPU work.

Each is its own task. The UI must not block them: for example, the Recording screen takes its state
from the ViewModel and doesn't care where the audio comes from.

## Verification

- `./gradlew assembleDebug` passes; install on the razr; screenshots of the cover screen (display 1)
  via `razr shot`; the inner screen in the unfolded state.
- Content descriptions on every icon button, touch targets of 48 dp or more, and contrast of 4.5:1
  or more for text. `ash` on `void` measures about 6.4:1 in dark and 5.3:1 in light. `unlit` is never
  used for text.
- Fold and unfold on the recording screen keeps recording and form state (`rememberSaveable`).

## Build order

1. Fonts and licences; `ui/design` package: tokens, type, `GrilleTheme`, components (Grille,
   PillButton, CircleIconButton, Hairline, ListRow, TextTabs, Toggle, DotSlider, Sheet, TopBar,
   DotProgress).
2. Audio level flow; quick-record settings; `autostart` route.
3. Home, the new-recording sheet, Recording (inner and cover), then build and check on the device.
4. Session details, Search, Settings, Licences, onboarding restyle.
5. Strings: English and German; remove other locales and dead UI (old theme, gradients, sky banner,
   shimmer, demo screens, unused fonts).
6. Full build, install, screenshots, self-critique pass.
