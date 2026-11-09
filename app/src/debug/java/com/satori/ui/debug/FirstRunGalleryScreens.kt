package com.satori.ui.debug

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.satori.domain.usecase.todo.ReminderPrompt
import com.satori.presentation.sessions.SessionsUiState
import com.satori.ui.design.ClearOfCameraBand
import com.satori.ui.design.Grille
import com.satori.ui.design.rememberNoticeState
import com.satori.ui.screens.HomeList
import com.satori.ui.screens.OnboardingScreen

/** Home's list is drawn narrow below this height, as on the cover. */
private val COMPACT_HEIGHT = 600.dp

/** Draws the first-run gallery state called [name] ([FirstRunGallery]); [category] names a starter category. */
@Composable
internal fun FirstRunGalleryScreen(name: String, category: (String) -> String) {
    val setup = FirstRunGallery.setup[name]
    val home = FirstRunGallery.home(name, category)
    when {
        // As MainActivity draws the first run: clear of the cover's camera band. Android's
        // microphone window and settings pages open as in the app; the rest is only logged.
        setup != null -> ClearOfCameraBand {
            OnboardingScreen(
                state = setup,
                actions = GalleryFirstRunActions,
                onRecord = { Log.i(FirstRunDebug.TAG, "Gallery: record") }
            )
        }
        home != null -> HomePreview(home)
    }
}

/**
 * Home with its recordings and, above them, the status strip, which home's list draws itself; the
 * strip's pills are only logged.
 */
@Composable
private fun HomePreview(state: SessionsUiState) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Grille.colors.void)
            .systemBarsPadding()
    ) {
        val compact = maxHeight < COMPACT_HEIGHT
        Column(Modifier.fillMaxSize()) {
            HomeList(
                state = state,
                compact = compact,
                listState = rememberLazyListState(),
                nowMs = System.currentTimeMillis(),
                showAllTodos = false,
                reminderPrompt = ReminderPrompt.None,
                notices = rememberNoticeState(),
                onShowAllTodos = {},
                onShowSuggestions = {},
                onOpenIdeas = {},
                onTurnOnReminders = {},
                onCompleteTodo = { _, _ -> },
                onOpenTodo = {},
                onOpenRecording = {},
                onDismissError = {},
                onStripTap = { Log.i(FirstRunDebug.TAG, "Gallery: strip $it") },
                modifier = Modifier.weight(1f)
            )
        }
    }
}
