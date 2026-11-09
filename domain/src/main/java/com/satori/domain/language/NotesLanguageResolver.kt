package com.satori.domain.language

import com.satori.domain.model.NotesLanguage
import com.satori.domain.model.RecordingMode
import com.satori.domain.repository.SettingsRepository
import com.satori.domain.repository.TranscriptionRepository
import kotlinx.coroutines.flow.first
import java.util.Locale

/**
 * Finds the language of the AI's output for one recording and one call, from the user's setting,
 * the text the call reads, and what the recording already has ([NotesLanguageChoice] holds the
 * rule). With [NotesLanguage.AUTO], the first confident result in English or German is stored on
 * the recording, so later notes, the name, the to-dos and the export agree even when a later call
 * reads a short text. A Malayalam text gives English, whatever the setting, and stores nothing:
 * each call on it finds it again, and a transcript made again in English or German still gets its
 * own language stored. With Auto a text clearly in another language gives English too
 * ([NotesLanguageChoice.OTHER_LANGUAGES_IN_ENGLISH]) and stores nothing either.
 *
 * A translation recording's output language is its translation target, which this never reads
 * or overwrites; for it the answer is only the language that was spoken.
 *
 * @property deviceLanguage The phone's language code; read at each call, since the user can change it
 */
class NotesLanguageResolver(
    private val settingsRepository: SettingsRepository,
    private val transcriptionRepository: TranscriptionRepository,
    private val deviceLanguage: () -> String = { Locale.getDefault().language }
) {
    /**
     * @param sessionId The recording the call is for
     * @param text What the call reads: a transcript, or a part of one
     * @return "en" or "de"
     */
    suspend fun resolve(sessionId: String, text: String): String {
        val setting = settingsRepository.getSettings().first().notesLanguage
        if (setting != NotesLanguage.AUTO) {
            val malayalam = LanguageDetector.MALAYALAM.takeIf { LanguageDetector.isMalayalam(text) }
            return NotesLanguageChoice.choose(setting, detected = malayalam, stored = null, device = null)
        }
        val session = transcriptionRepository.getSession(sessionId).first()
        val keepsNotesLanguage = session != null && session.mode != RecordingMode.REAL_TIME_TRANSLATION
        val stored = if (keepsNotesLanguage) session?.outputLanguage?.takeIf { it.isNotBlank() } else null
        val detected = NotesLanguageChoice.detect(text)
        if (detected != null && NotesLanguageChoice.writesIn(detected) && keepsNotesLanguage && stored == null) {
            transcriptionRepository.setOutputLanguageIfUnset(sessionId, detected)
        }
        return NotesLanguageChoice.choose(setting, detected, stored, deviceLanguage())
    }
}
