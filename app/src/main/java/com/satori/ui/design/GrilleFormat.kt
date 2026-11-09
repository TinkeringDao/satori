package com.satori.ui.design

import android.content.Context
import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.satori.domain.model.RecordingMode
import com.satori.ui.R
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The two app languages, as BCP-47 codes. */
val AppLanguageCodes = listOf("en", "de")

/** Language names are shown in their own language, so they are not translated. */
fun languageName(code: String): String = when (code) {
    "de" -> "Deutsch"
    else -> "English"
}

/** Device language if it is one of [AppLanguageCodes], else English. */
fun deviceAppLanguage(): String =
    Locale.getDefault().language.takeIf { it in AppLanguageCodes } ?: "en"

/**
 * A length as the app shows it everywhere, the recorder's timer included: m:ss, and h:mm:ss
 * from one hour on ("0:00", "2:41", "24:10", "1:24:10"). Whole seconds, rounded down.
 * Always ASCII digits, which is all Doto has; set it with [DotoNumber] in a Doto style.
 */
fun formatDuration(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total / 60) % 60
    val s = total % 60
    return if (h > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.ROOT, "%d:%02d", m, s)
    }
}

/** Time of day in the user's 12/24-hour preference. */
@Composable
fun clockTime(timestamp: Long): String = clockTime(LocalContext.current, timestamp)

/** [clockTime] outside composition, e.g. for an export. */
fun clockTime(context: Context, timestamp: Long): String =
    DateFormat.getTimeFormat(context).format(Date(timestamp))

/** "Today, 09:30", "Yesterday, 18:02", "Mon 6 Oct, 18:02" (calendar days, not 24 h windows). */
@Composable
fun relativeDateTime(timestamp: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    val time = clockTime(timestamp)
    val then = Calendar.getInstance().apply { timeInMillis = timestamp }
    val now = Calendar.getInstance()
    val sameYear = then.get(Calendar.YEAR) == now.get(Calendar.YEAR)
    val dayDiff = if (sameYear) now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR) else Int.MAX_VALUE
    return when (dayDiff) {
        0 -> stringResource(R.string.time_today, time)
        1 -> stringResource(R.string.time_yesterday, time)
        else -> {
            val pattern = DateFormat.getBestDateTimePattern(locale, if (sameYear) "EEEdMMM" else "EEEdMMMyyyy")
            "${java.text.SimpleDateFormat(pattern, locale).format(Date(timestamp))}, $time"
        }
    }
}

@Composable
fun modeLabel(mode: RecordingMode): String = stringResource(modeLabelRes(mode))

/** The mode's name as a string resource, for use outside composition. */
@StringRes
fun modeLabelRes(mode: RecordingMode): Int = when (mode) {
    RecordingMode.SIMPLE_LISTENING -> R.string.g_mode_note
    RecordingMode.SHORT_MEETING -> R.string.g_mode_meeting
    RecordingMode.LONG_MEETING -> R.string.g_mode_long
    RecordingMode.REAL_TIME_TRANSLATION -> R.string.g_mode_translate
}

@Composable
fun modeDescription(mode: RecordingMode): String = stringResource(
    when (mode) {
        RecordingMode.SIMPLE_LISTENING -> R.string.g_mode_note_desc
        RecordingMode.SHORT_MEETING -> R.string.g_mode_meeting_desc
        RecordingMode.LONG_MEETING -> R.string.g_mode_long_desc
        RecordingMode.REAL_TIME_TRANSLATION -> R.string.g_mode_translate_desc
    }
)
