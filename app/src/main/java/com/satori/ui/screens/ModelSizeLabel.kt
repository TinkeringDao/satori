package com.satori.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale
import kotlin.math.roundToLong

/** Bytes per megabyte as the download screens count them (10^6), and per gigabyte. */
private const val BYTES_PER_MB = 1_000_000.0
private const val BYTES_PER_GB = 1_000_000_000.0

/** The no-break space the app puts before a unit. */
private const val NBSP = '\u00A0'

/**
 * The size of a download in the words of the first run and, once they use it, of Settings: the
 * nearest megabyte below 1 GB, one decimal above, written as the [locale] writes decimals, with a
 * no-break space before the unit: "670 MB", "138 MB", "808 MB", "1.1 GB", in German "1,1 GB". One
 * model has one size everywhere, from the registry's bytes.
 */
fun modelSizeLabel(bytes: Long, locale: Locale): String {
    val megabytes = (bytes / BYTES_PER_MB).roundToLong()
    return if (megabytes < 1_000L) {
        "$megabytes${NBSP}MB"
    } else {
        String.format(locale, "%.1f%sGB", bytes / BYTES_PER_GB, NBSP)
    }
}

/**
 * [modelSizeLabel] in the app's language, for a screen: the first run's sizes, and Settings' once
 * they come from the registry's bytes too. Changes with the language.
 */
@Composable
fun rememberSizeLabel(): (Long) -> String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { { bytes -> modelSizeLabel(bytes, locale) } }
}
