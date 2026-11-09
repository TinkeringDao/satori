# First-Run Architecture & Module Contract (0.7.0)

This document records the module boundaries, state ownership, and debug test hooks for the 0.7.0 first-run onboarding, background download coordinator, status strip, and Malayalam language integration (`docs/ONBOARDING_DESIGN_ADHD.md`).

All components are integrated in the main source tree: `FirstRunCoordinatorImpl` is bound in `FirstRunModule`, `FirstRunViewModel` drives the onboarding flow, and the debug gallery renders the production `OnboardingScreen` and `HomeList` composables.

Conventions:
- UI strings live in `values/strings_grille_*.xml` (English) and `values-de/strings_grille_*.xml` (German, using the neutral infinitive).
- Diagnostic logs emit only IDs, counts, milliseconds, and megabytes under tags `SatoriFirstRun` and `SatoriNudge`.

## Module & File Ownership

| Path | Responsibility | Subsystem |
|---|---|---|
| `domain/.../firstrun/FirstRunStage.kt`, `FirstRunFlow.kt` | Onboarding stages, transition order, launch routing, page indicators, quick-key readiness, 700 ms tap shield | State & downloads |
| `domain/.../firstrun/FirstRunPlan.kt` | Ordered download plan, optional Malayalam pack handling, retry wait state, 300 MB storage headroom, `SpaceShortfall` | State & downloads |
| `domain/.../firstrun/NetworkPolicy.kt` | Network selection per download, `BLOCKED` state after consecutive connection failures, retry backoff | State & downloads |
| `domain/.../firstrun/FirstRunCoordinator.kt` | Coordinator contract, `FirstRunSnapshot`, `FirstRunProgress` | State & downloads |
| `domain/.../firstrun/Nudges.kt` | `NudgeId`, `NudgeState` | Home strip & nudges |
| `domain/.../model/DownloadState.kt`, `DownloadFailure.kt` | `Paused(PauseReason)`, `Verifying`, and `BLOCKED` states | State & downloads |
| `domain/.../finish/FinishingStatus.kt` | `Phase.WAITS_FOR_MODEL` when a recording finishes before the LLM is downloaded | Recorder & finishing |
| `data/.../firstrun/FirstRunPrefs.kt`, `FirstRunStore.kt`, `NetworkMonitor.kt`, `StorageCheck.kt` | Persistent first-run preferences, `ConnectivityManager` capability tracking, and storage headroom checks | State & downloads |
| `data/src/{debug,release}/.../config/ModelSource.kt`, `ModelSourceInstaller.kt`, `data/src/debug/AndroidManifest.xml` | Debug-only local test server hook for model downloads; release builds resolve directly to the production registry | Debug infrastructure |
| `presentation/.../firstrun/FirstRunUiState.kt`, `FirstRunActions.kt` | UI state and user actions for the setup screens | State & downloads |
| `presentation/.../firstrun/StripState.kt` | Home screen download/status strip state | Home strip & nudges |
| `presentation/.../di/FirstRunModule.kt` | Hilt bindings for `FirstRunCoordinator` | State & downloads |
| `presentation/.../main/MainUiState.kt` | `firstRecordingCoach` and `aiModelPending` flags | Recorder & finishing |
| `presentation/.../sessions/SessionsUiState.kt` | Home list `strip` state | Home strip & nudges |
| `app/.../ui/screens/FirstRunCopy.kt` | Slot-by-slot copy mapping for every setup state; `FirstRunTags` | Onboarding UI |
| `app/.../ui/screens/ModelSizeLabel.kt` | Shared model size formatting for onboarding and Settings | Onboarding UI |
| `app/src/main/res/values*/strings_grille_firstrun.xml` | First-run onboarding strings | Onboarding UI |
| `app/src/main/res/values*/strings_grille_strip.xml` | Home status strip and nudge strings | Home strip & nudges |
| `app/.../ui/screens/FinishingStatusText.kt`, `strings_grille_finishing.xml` | "Waits for the AI model" status line | Recorder & finishing |
| `app/.../ui/screens/ModelFacts.kt` | `BLOCKED` title and description in Settings download screens | Settings UI |
| `app/src/debug/.../ui/debug/DebugToolsActivity.kt`, `FirstRunDebug.kt` | Debug intent actions for resetting and staging first-run states | Debug infrastructure |
| `app/src/debug/.../ui/debug/FirstRunGallery.kt`, `GalleryStates.kt`, `FirstRunGalleryScreens.kt` | 23 deterministic gallery states from section 5.9 of the onboarding design | Debug infrastructure |
| `app/src/testDebug/.../FirstRunStringsTest.kt`, `FirstRunGalleryTest.kt` | Copy length assertions (A4-A7, A13) and gallery state coverage tests | Debug infrastructure |
| `~/satori-audit/tools/throttle_server.py` (outside the repo) | Local HTTP server serving synthetic model payloads at a configurable byte rate with fault injection | Debug infrastructure |

## Subsystem Breakdown

- **State, Plan, and Downloads**: `FirstRunStage`, `FirstRunFlow`, `FirstRunPlan`, `NetworkPolicy`, `FirstRunCoordinator`, `FirstRunUiState`, `FirstRunActions`, `DownloadState`, `DownloadFailure`, `FirstRunPrefs`, `FirstRunStore`, `NetworkMonitor`, `StorageCheck`, `ModelDownloadManager`, `AndroidDownloadManager`, `DownloadStateManager`, `ModelConfig.kt`, `FirstRunViewModel`, `ModelDownloadService`, `ModelDownloadNotificationManager`, `MainActivity` (stage routing and quick-key readiness), `QuickKeyRequest` (`NOT_SET_UP` handling), and `SatoriApplication`.
- **Onboarding Screens**: `OnboardingScreen.kt`, `GetReadyScreen.kt`, `GettingReadyScreen.kt`, `OnboardingStepLayout` (with docked footer and 700 ms tap shield), `MicrophoneAsk.kt`, `OnboardingPageIndicator.kt`, `FirstRunCopy.kt`, `ModelSizeLabel.kt`, `strings_grille_firstrun.xml`, and `strings_grille_onboarding.xml`.
- **Recorder and Finishing**: `MainScreen.kt`, `RecorderCopy.kt`, `strings_grille_recorder.xml`, `MainViewModel.kt`, `MainUiState.kt`, `RecordingFinisher.kt`, `FinishRecordingUseCase.kt`, `FinishingStatus.kt`, `FinishingStatusText.kt`, `strings_grille_finishing.xml`, `RecordingCues.kt`, `RecordingHaptics.kt`, `LlmProcessingNotificationManager.kt`, `FinishingNotificationText.kt`, and `strings_grille_notifications.xml`.
- **Home Strip and Nudges**: `HomeList.kt`, `SessionsScreen.kt`, `SessionsViewModel.kt`, `SessionsUiState.kt`, `StripState.kt`, `Nudges.kt`, `StatusStrip`, `NudgeLedger`, `strings_grille_strip.xml`, `strings_grille_home.xml`, `TodoReminderPermission.kt`, and debug-only scoping for `QuickKeyAdbSetup`.
- **Settings**: `AdvancedSettingsScreen.kt`, `ModelSettings.kt`, `SettingsRows.kt`, `SettingsScreen.kt`, `SettingsViewModel.kt`, `AdvancedSettingsViewModel.kt`, `ModelsUiState.kt`, `ModelStatus.kt`, `GrilleSheets.kt`, `GrilleCameraBand.kt`, `CategoriesScreen.kt`, `SavePlaceSetting.kt`, `LicensesScreen.kt`, `AppLicenses.kt`, `NotesLanguageChoice.kt`, `SessionDetailsSheets.kt`, and `ModelFacts.kt`.
- **Malayalam Support**: `LanguageKey` composable beside `RecordAsChip.kt` in `MainScreen.kt` top-bar actions and cover-screen timer row, `strings_grille_language.xml`, `MainViewModel.recordIn`, `TranscribeAgainUseCase.kt`, `RecorderAwareBetterTranscript.kt`, `SpeechModelChoice.kt`, `ModelLoadPacing.kt` (`otherLoad`), and Malayalam Unicode normalization in `domain/.../language/`.

## Integration Boundaries

- `FirstRunCoordinator`: `RecordingFinisher` calls `recordingSaved()` when a non-empty recording is handed off, holds recordings as `WAITS_FOR_MODEL` while `aiModelPending` and AI notes are enabled, and shows the first-recording coach while `firstRecordingAhead` is true. `HomeList` renders `later` and download plan state in the status strip and invokes `retry`, `useMobileData`, and `getBiggerModel`. Unit tests use `FakeFirstRunCoordinator` (`presentation/src/test/.../firstrun/FirstRunFakes.kt`).
- `FirstRunUiState` and `FirstRunActions`: `FirstRunViewModel` produces `FirstRunUiState` and implements `FirstRunActions`; the onboarding composables render `FirstRunCopy` and state metrics. The debug gallery binds `GalleryFirstRunActions`.
- Downloads resolve URLs through `SpeechModels.of` and `modelConfigForVariant` so debug builds can redirect requests to the local test server via `ModelSource`.

## Debug Test Hooks

In debug builds, invoke `am start -n com.satori/com.satori.ui.debug.DebugToolsActivity` with:
- `--es action firstrun-reset`
- `--es action firstrun-stage --es stage READY`
- `--es action firstrun-source --es url http://10.0.2.2:8099/` (or `off`)
- `--es action dump-settings`
- `--es screen firstrun_<state>` to open a specific gallery state (omitting a known state lists all available gallery states).

`python3 -I ~/satori-audit/tools/throttle_server.py --rate 100` serves synthetic model files on port 8099 with fault injection options documented in `--help`.
