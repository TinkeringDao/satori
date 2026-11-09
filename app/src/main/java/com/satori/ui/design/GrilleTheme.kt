package com.satori.ui.design

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.satori.domain.model.ThemeMode

/** Access to the Grille tokens, like `MaterialTheme.colorScheme`. */
object Grille {
    val colors: GrilleColors
        @Composable @ReadOnlyComposable get() = LocalGrilleColors.current
    val type: GrilleType
        @Composable @ReadOnlyComposable get() = LocalGrilleType.current
}

/** Pills, circles and the 24 dp sheet radius are the only shapes in the app. */
val GrilleShape = RoundedCornerShape(24.dp)

/**
 * App theme. Grille tokens drive everything; the Material scheme is derived from them so
 * that Material components we still use (text selection, Markdown, menus) stay on palette.
 *
 * MainActivity also hands [themeMode] to Android (UiModeManager.setApplicationNightMode), so
 * the window behind Compose and the splash screen of the next start match it; while it does,
 * [isSystemInDarkTheme] reports the app's mode.
 */
@Composable
fun GrilleTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) DarkGrilleColors else LightGrilleColors
    val type = DefaultGrilleType

    MaterialTheme(
        colorScheme = colors.toMaterial(),
        typography = type.toMaterial(),
        shapes = Shapes(
            extraSmall = GrilleShape,
            small = GrilleShape,
            medium = GrilleShape,
            large = GrilleShape,
            extraLarge = GrilleShape
        )
    ) {
        CompositionLocalProvider(
            LocalGrilleColors provides colors,
            LocalGrilleType provides type,
            LocalContentColor provides colors.ink,
            content = content
        )
    }
}

/** True when the user turned animations off in system settings. */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

private fun GrilleColors.toMaterial(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = ink, onPrimary = void,
        primaryContainer = graphite, onPrimaryContainer = ink,
        inversePrimary = void,
        secondary = ash, onSecondary = void,
        secondaryContainer = graphite, onSecondaryContainer = ink,
        tertiary = ash, onTertiary = void,
        tertiaryContainer = graphite, onTertiaryContainer = ink,
        background = void, onBackground = ink,
        surface = void, onSurface = ink,
        surfaceVariant = graphite, onSurfaceVariant = ash,
        surfaceTint = Color.Transparent,
        inverseSurface = ink, inverseOnSurface = void,
        // Errors are not red: red only ever means the microphone is live.
        error = ink, onError = void,
        errorContainer = graphite, onErrorContainer = ink,
        // Material's roles: outline edges a control, outlineVariant only decorates.
        outline = ash, outlineVariant = unlit,
        scrim = scrim,
        surfaceBright = graphite, surfaceDim = void,
        surfaceContainerLowest = void, surfaceContainerLow = graphite,
        surfaceContainer = graphite, surfaceContainerHigh = graphite,
        surfaceContainerHighest = graphite
    )
}

/**
 * The app reads its own styles; these are for Material's components. Its time pickers set the
 * hour, the minute and the ':' between them in the display styles, and Doto would draw that ':'
 * as a cross of dots, so the display styles are the large number size in Space Grotesk Light.
 */
private fun GrilleType.toMaterial(): Typography {
    val display = displayNumber.copy(fontFamily = SpaceGroteskFamily, fontWeight = FontWeight(300))
    return Typography(
        displayLarge = display, displayMedium = display, displaySmall = display,
        headlineLarge = title, headlineMedium = title, headlineSmall = title,
        titleLarge = title, titleMedium = heading, titleSmall = label,
        bodyLarge = body, bodyMedium = body, bodySmall = caption,
        labelLarge = label, labelMedium = label, labelSmall = caption
    )
}
