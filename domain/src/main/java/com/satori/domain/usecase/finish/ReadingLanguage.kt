package com.satori.domain.usecase.finish

import com.satori.domain.language.NotesLanguageResolver
import com.satori.domain.model.RecordingMode
import com.satori.domain.model.TranscriptionSession

/** The languages the app writes in, and so the only translation targets a name or a topic can take. */
private val APP_LANGUAGES = setOf("en", "de")

/**
 * The language the user reads what the AI writes about [session] in, as opposed to its notes:
 * its name and its mind map topics. A Translate recording is read in its target language when the
 * app has that language; any other recording, or a target the app doesn't have, gets the notes
 * language of [text] ([NotesLanguageResolver.resolve]).
 *
 * @return "en" or "de"
 */
internal suspend fun NotesLanguageResolver.readingLanguage(session: TranscriptionSession, text: String): String =
    session.outputLanguage?.takeIf { session.mode == RecordingMode.REAL_TIME_TRANSLATION && it in APP_LANGUAGES }
        ?: resolve(session.id, text)
