package com.satori.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.satori.domain.model.DownloadProgress
import com.satori.domain.model.DownloadState
import com.satori.domain.model.LlmModelVariant
import com.satori.domain.model.PauseReason
import com.satori.ui.R
import com.satori.ui.design.ActionRow
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleSpace
import com.satori.ui.design.GrilleTopBar
import com.satori.ui.design.PillAction

/**
 * Screen for the optional AI model, opened from the Settings row "Get the AI model" or from a
 * recording that has no model. The first run has no such step: its AI model downloads after the
 * first recording.
 *
 * - [DownloadState.Idle]: what the model does, how big it is ([variant]), that it downloads once
 *   and needs an internet connection now. "Download" is the one filled action and "Not now" the
 *   quiet one.
 * - [DownloadState.Downloading]: Doto percentage, dot progress and megabytes.
 * - [DownloadState.Verifying] and [DownloadState.Paused]: the same, with one line under it, in the
 *   download notification's words: the files being checked, or why it waits (a recording, the
 *   network, Wi-Fi, room); it goes on by itself, as the first run's does, so no action is offered.
 * - [DownloadState.Completed]: a short confirmation and "Continue"; the screen also closes itself.
 * - [DownloadState.Error]: the cause and what to do about it, "Try again" and a way to skip.
 *
 * A top bar's back button calls [onSkip].
 *
 * @param onContinue Closes the screen once the model is ready
 * @param isStandalone Picks the line under the download ([llmLaterLine]); true wherever the screen
 *   opens now, as it has no other mode since the first run lost its AI model step.
 */
@Composable
fun LlmDownloadScreen(
    downloadState: DownloadState,
    variant: LlmModelVariant,
    onStartDownload: () -> Unit,
    onSkip: () -> Unit,
    onRetry: () -> Unit,
    onContinue: () -> Unit = {},
    isStandalone: Boolean = true
) {
    val skip = PillAction(stringResource(R.string.g_onb_not_now), onClick = onSkip)

    val primary: PillAction?
    val secondary: PillAction?
    when {
        downloadState == DownloadState.Completed -> {
            primary = PillAction(stringResource(R.string.g_onb_continue), onClick = onContinue)
            secondary = null
        }
        downloadState == DownloadState.Idle -> {
            primary = PillAction(stringResource(R.string.g_onb_download), onClick = onStartDownload)
            secondary = skip
        }
        downloadState is DownloadState.Error -> {
            primary = PillAction(stringResource(R.string.g_onb_retry), onClick = onRetry)
            secondary = skip
        }
        else -> {
            primary = null
            secondary = null
        }
    }

    // A new state starts at the top instead of keeping the previous state's scroll position.
    key(downloadState.onboardingStage()) {
        OnboardingStepLayout(
            footer = if (primary != null || secondary != null) {
                { ActionRow(primary = primary, secondary = secondary) }
            } else {
                null
            },
            header = {
                GrilleTopBar(
                    title = null,
                    onBack = onSkip,
                    backDescription = stringResource(R.string.g_back)
                )
            }
        ) {
            val held = llmHeldLine(downloadState)
            when {
                downloadState is DownloadState.Downloading -> OnboardingDownloadProgress(
                    title = stringResource(R.string.g_onb_llm_downloading),
                    state = downloadState,
                    showResumeNote = true
                )
                held != null -> OnboardingDownloadProgress(
                    title = stringResource(R.string.g_onb_llm_downloading),
                    state = DownloadState.Downloading(downloadProgressOf(downloadState) ?: DownloadProgress(0L, 0L, 0)),
                    note = stringResource(held)
                )
                downloadState == DownloadState.Completed -> OnboardingHeading(
                    title = stringResource(R.string.g_onb_llm_ready_title),
                    body = stringResource(R.string.g_onb_llm_ready_body)
                )
                downloadState is DownloadState.Error -> OnboardingDownloadFailed(
                    reason = downloadState.reason,
                    size = modelSize(variant)
                )
                else -> {
                    OnboardingHeading(
                        title = stringResource(R.string.g_onb_llm_title),
                        body = stringResource(R.string.g_onb_llm_body)
                    )
                    Spacer(Modifier.height(GrilleSpace.m))
                    OnboardingDownloadNote(modelSize(variant))
                    Spacer(Modifier.height(GrilleSpace.m))
                    Text(
                        text = stringResource(llmLaterLine(isStandalone)),
                        style = Grille.type.caption,
                        color = Grille.colors.ash
                    )
                }
            }
        }
    }
}

/**
 * The last line before the AI model is downloaded: that the app works without it, and during
 * onboarding that it can be downloaded later in Settings. Opened from Settings ([isStandalone])
 * the owner is there already, so the line stops at the first half.
 */
internal fun llmLaterLine(isStandalone: Boolean): Int =
    if (isStandalone) R.string.g_settings_llm_works_without else R.string.g_onb_llm_later

/**
 * The line under the AI model's bar while its download is checked or held, as the download
 * notification says it; null for every other state. Paused by the owner or blocked, it says
 * the first run's words for that.
 */
@StringRes
internal fun llmHeldLine(state: DownloadState): Int? = when (state) {
    is DownloadState.Verifying -> R.string.g_dl_checking
    is DownloadState.Paused -> when (state.reason) {
        PauseReason.RECORDING -> R.string.g_dl_paused_recording
        PauseReason.NO_NETWORK -> R.string.g_dl_no_connection
        PauseReason.WIFI_ONLY -> R.string.g_dl_waiting_wifi
        PauseReason.SERVER -> R.string.g_dl_server
        PauseReason.NOT_ENOUGH_STORAGE -> R.string.g_dl_space
        PauseReason.BLOCKED -> R.string.g_fr_err_blocked_title
        PauseReason.USER -> R.string.g_fr_paused
    }
    DownloadState.Idle, is DownloadState.Downloading, DownloadState.Completed, is DownloadState.Error -> null
}

/** Where a checked or held download stands, if known. */
private fun downloadProgressOf(state: DownloadState): DownloadProgress? = when (state) {
    is DownloadState.Verifying -> state.progress
    is DownloadState.Paused -> state.progress
    else -> null
}
