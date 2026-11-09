package com.satori.ui.design

import android.graphics.Rect
import android.view.Surface
import android.view.WindowManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.satori.data.monitor.SystemProperty
import com.satori.ui.BuildConfig

/** The edge of the window a [CameraBand] runs along. */
enum class BandEdge { TOP, BOTTOM, LEFT, RIGHT }

/**
 * The strip of the razr's cover screen that holds its cameras, when Satori is drawn around them.
 *
 * Motorola gives an app only the part of the cover above the cameras unless the app asks for the
 * whole of it (the manifest's moto_cli_default_display_mode). Then the window spans the cover, and
 * the cameras sit at one end of a band along an edge of it: the display's cutout. Upright the band
 * runs along the bottom, upside down along the top, and on its side along the left or the right
 * edge. Home and the recorder put the grille in the free part of a band along the top or the
 * bottom ([CameraBandRow], when [bandForGrille] says it fits); every other screen, and those two
 * beside a band on a side, keep everything out of it ([clearOf]).
 *
 * Nothing in the band may take input focus. Android treats the band as covered: when the control
 * that has focus lies in it, Android scrolls the whole window until that control clears the band,
 * and every screen drawn in the window afterwards is off by as much. Out of touch mode a window's
 * first focus goes to the first control from the top, which upside down is the grille. On
 * 2026-10-15 (0.6.0, the cover upside down) the window was drawn 291 px lower, just what brings
 * home's grille, 58 px from the top, clear of the 349 px band: home and the recording's page
 * started some 680 px down and ran off the bottom of the screen, while the sheets, in windows of
 * their own, were drawn where they belong.
 *
 * @property edge The edge of the window the band runs along
 * @property depth How far the band reaches into the window from that edge
 * @property cameraLength How much of the band's length the cameras take, at one of its ends
 * @property cameraAtStart The cameras are at the band's left end (a band along the top or the
 *   bottom) or at its top end (one along a side), else at the other end
 */
@Immutable
data class CameraBand(val edge: BandEdge, val depth: Dp, val cameraLength: Dp, val cameraAtStart: Boolean) {

    /** Runs along the top or the bottom, the cover upright or upside down: the grille can sit in it. */
    val horizontal: Boolean get() = edge == BandEdge.TOP || edge == BandEdge.BOTTOM

    /** Runs along the top, the cover upside down. */
    val atTop: Boolean get() = edge == BandEdge.TOP

    /** The band as insets from its edge of the window. */
    fun insets(): WindowInsets = when (edge) {
        BandEdge.TOP -> WindowInsets(top = depth)
        BandEdge.BOTTOM -> WindowInsets(bottom = depth)
        BandEdge.LEFT -> WindowInsets(left = depth)
        BandEdge.RIGHT -> WindowInsets(right = depth)
    }

    /** The side of the window the band is on, for the system bars there. */
    val sides: WindowInsetsSides
        get() = when (edge) {
            BandEdge.TOP -> WindowInsetsSides.Top
            BandEdge.BOTTOM -> WindowInsetsSides.Bottom
            BandEdge.LEFT -> WindowInsetsSides.Left
            BandEdge.RIGHT -> WindowInsetsSides.Right
        }
}

/**
 * The cover's camera band of the window this is drawn in, or null when there is none: on the inner
 * screen, whose punch-hole camera sits inside the status bar, and on the cover while Motorola keeps
 * the app above the cameras. Read again when the configuration changes, as when the cover turns.
 *
 * Motorola hands a full-screen app neither the cutout's insets nor its bounds (both are empty in
 * the window's insets), so the band is the part of the display's own cutout inside the window. A
 * debug build on a display without such a cutout, an emulator, can have the razr's cameras drawn
 * in with the property `debug.satori.cover.band` ([simulatedCameras]).
 */
@Composable
fun cameraBand(): CameraBand? {
    // Read so that a fold or a turn of the cover, which change the configuration, recompose this.
    LocalConfiguration.current
    val density = LocalDensity.current
    val window = LocalContext.current.getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds ?: return null
    val display = LocalView.current.display ?: return null
    val inWindow = display.cutout?.boundingRects.orEmpty().mapNotNull { rect ->
        val part = Rect(rect)
        if (!part.intersect(window)) return@mapNotNull null
        IntRect(part.left - window.left, part.top - window.top, part.right - window.left, part.bottom - window.top)
    }
    val band = cameraBandOf(window.width(), window.height(), inWindow, density.density)
    if (band != null || !BuildConfig.DEBUG) return band
    val cameras = simulatedCameras(SystemProperty.read(SIMULATED_BAND_PROPERTY)) ?: return null
    val rotation = display.rotation
    val turned = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
    val naturalWidth = if (turned) window.height() else window.width()
    val naturalHeight = if (turned) window.width() else window.height()
    val block = IntRect(naturalWidth - cameras.width, naturalHeight - cameras.height, naturalWidth, naturalHeight)
    return cameraBandOf(window.width(), window.height(), listOf(rotated(block, naturalWidth, naturalHeight, rotation)), density.density)
}

/**
 * The camera band of a window [windowWidth] by [windowHeight] pixels with the bounding [cutouts]
 * of the display's cutout in the window's coordinates, or null when there is no such band.
 *
 * A cutout at an edge of the window makes a band along that edge, as deep as the cutout reaches
 * into the window. One in a corner touches two edges; the band runs along the one it reaches into
 * least, where the cameras lie along the band: the razr's 495 x 349 px cameras make a band 349 px
 * deep along the bottom or the top of the cover held upright, and along a side of the cover on its
 * side. A cutout thinner than [MIN_BAND_SHARE] of the window across the band (a punch hole in the
 * status bar) is no band, nor one deeper than [MAX_DEPTH_SHARE] of it (a strip along the other
 * edge), nor one that leaves no room beside the cameras ([MAX_CAMERA_SHARE]).
 */
internal fun cameraBandOf(windowWidth: Int, windowHeight: Int, cutouts: List<IntRect>, density: Float): CameraBand? {
    if (windowWidth <= 0 || windowHeight <= 0) return null
    val bands = BandEdge.entries.mapNotNull { edge ->
        val across = if (edge == BandEdge.TOP || edge == BandEdge.BOTTOM) windowHeight else windowWidth
        val along = if (edge == BandEdge.TOP || edge == BandEdge.BOTTOM) windowWidth else windowHeight
        val camera = cutouts.filter { touches(it, edge, windowWidth, windowHeight) }
            .maxByOrNull { depthOf(it, edge, windowWidth, windowHeight) } ?: return@mapNotNull null
        val depth = depthOf(camera, edge, windowWidth, windowHeight)
        val length = if (edge == BandEdge.TOP || edge == BandEdge.BOTTOM) camera.width else camera.height
        if (depth < across * MIN_BAND_SHARE || depth > across * MAX_DEPTH_SHARE) return@mapNotNull null
        if (length <= 0 || length > along * MAX_CAMERA_SHARE) return@mapNotNull null
        val atStart = if (edge == BandEdge.TOP || edge == BandEdge.BOTTOM) {
            camera.left <= windowWidth - camera.right
        } else {
            camera.top <= windowHeight - camera.bottom
        }
        depth to CameraBand(edge, (depth / density).dp, (length / density).dp, atStart)
    }
    return bands.minByOrNull { it.first }?.second
}

private fun touches(rect: IntRect, edge: BandEdge, width: Int, height: Int): Boolean = when (edge) {
    BandEdge.TOP -> rect.top <= 0
    BandEdge.BOTTOM -> rect.bottom >= height
    BandEdge.LEFT -> rect.left <= 0
    BandEdge.RIGHT -> rect.right >= width
}

private fun depthOf(rect: IntRect, edge: BandEdge, width: Int, height: Int): Int = when (edge) {
    BandEdge.TOP -> rect.bottom
    BandEdge.BOTTOM -> height - rect.top
    BandEdge.LEFT -> rect.right
    BandEdge.RIGHT -> width - rect.left
}

/**
 * [rect] of a display [naturalWidth] by [naturalHeight] pixels upright, where it is with the
 * display turned to [rotation] (a Surface.ROTATION_* value), as Android turns the cutout itself.
 */
internal fun rotated(rect: IntRect, naturalWidth: Int, naturalHeight: Int, rotation: Int): IntRect = when (rotation) {
    Surface.ROTATION_90 -> IntRect(rect.top, naturalWidth - rect.right, rect.bottom, naturalWidth - rect.left)
    Surface.ROTATION_180 -> IntRect(naturalWidth - rect.right, naturalHeight - rect.bottom, naturalWidth - rect.left, naturalHeight - rect.top)
    Surface.ROTATION_270 -> IntRect(naturalHeight - rect.bottom, rect.left, naturalHeight - rect.top, rect.right)
    else -> rect
}

/**
 * The cameras `debug.satori.cover.band` asks a debug build to draw in, as the razr's are: [value]
 * is their width and height in pixels, "495x349" for the razr's, at the bottom right of the display
 * upright. Null when it is empty, can't be read, or isn't two positive numbers.
 */
internal fun simulatedCameras(value: String?): IntSize? {
    val parts = value?.trim()?.lowercase()?.split('x') ?: return null
    if (parts.size != 2) return null
    val width = parts[0].trim().toIntOrNull() ?: return null
    val height = parts[1].trim().toIntOrNull() ?: return null
    return if (width > 0 && height > 0) IntSize(width, height) else null
}

/**
 * The [band] when it holds a grille [grilleSize] across with its word under it, written at
 * [labelLineHeight] (the label style's line height at the font scale set), else null: a band on a
 * side holds none, and one the system bars leave less room in than the grille needs holds none
 * either. A screen whose band holds no grille keeps clear of the whole band ([clearOf]).
 */
@Composable
fun bandForGrille(band: CameraBand?, grilleSize: Dp, labelLineHeight: TextUnit): CameraBand? {
    if (band == null) return null
    val density = LocalDensity.current
    val label = if (labelLineHeight.isSp) with(density) { labelLineHeight.toDp() } else 0.dp
    return band.takeIf { holdsGrille(it, it.freeHeight(), grilleSize + GrilleSpace.s + label) }
}

/**
 * Whether [band] holds a grille whose block (the dots and the word under them) is [grilleBlock]
 * tall, when [freeDepth] of it is left beside the system bars: only a band along the top or the
 * bottom, and only when the block fits.
 */
internal fun holdsGrille(band: CameraBand, freeDepth: Dp, grilleBlock: Dp): Boolean =
    band.horizontal && freeDepth >= grilleBlock

/**
 * The [band]'s free part, beside the cameras, holding [content] in its centre, for a column that
 * starts (band at the top) or ends (at the bottom) with it. The window's root is padded for the
 * system bars, so the part of the band they cover is left out.
 *
 * What is in it never takes input focus (see [CameraBand]): a focused grille in the band would
 * have Android scroll the whole window to show it. The grille and its chip still work by touch
 * and by TalkBack, which needs no input focus.
 */
@Composable
fun CameraBandRow(band: CameraBand, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Row(modifier.fillMaxWidth().height(band.freeHeight())) {
        if (band.cameraAtStart) Spacer(Modifier.width(band.cameraLength))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .focusProperties { canFocus = false },
            contentAlignment = Alignment.Center,
            content = content
        )
        if (!band.cameraAtStart) Spacer(Modifier.width(band.cameraLength))
    }
}

/**
 * The depth of the band that is left once the window's root is padded for the system bars on the
 * band's side, which cover part of it (the navigation bar at the bottom): what a screen keeps free
 * for it beside those bars.
 */
@Composable
fun CameraBand.freeHeight(): Dp {
    val density = LocalDensity.current
    val bars = WindowInsets.systemBars
    val covered = with(density) {
        when (edge) {
            BandEdge.TOP -> bars.getTop(density)
            BandEdge.BOTTOM -> bars.getBottom(density)
            else -> 0
        }.toDp()
    }
    return (depth - covered).coerceAtLeast(0.dp)
}

/**
 * Keeps what it is applied to clear of the [band], for a screen that doesn't lay itself out
 * around the cameras: it is padded by the band or by the system bars on that side, whichever
 * reaches further, and both count as taken for what is inside, so its own padding for the system
 * bars, and for the keyboard, adds only what reaches past the band.
 */
@Composable
fun Modifier.clearOf(band: CameraBand?): Modifier {
    if (band == null) return this
    return windowInsetsPadding(clearance(band, WindowInsets.systemBars.only(band.sides)))
}

/**
 * What [clearOf] pads and takes, from [band] and the system bars on its side ([barsOnBandSide]):
 * whichever reaches further. Not the band without the bars, which left the razr's last 58 px, its
 * navigation bar, in the band at the bottom; and not both, as the bars lie inside the band.
 */
internal fun clearance(band: CameraBand, barsOnBandSide: WindowInsets): WindowInsets = band.insets().union(barsOnBandSide)

/**
 * A screen that keeps clear of the cover's camera band ([clearOf]): every screen but home and the
 * recorder, which lay themselves out around the cameras. Each screen of the navigation has its own,
 * so a screen going away during a change of screens stays where it was drawn.
 */
@Composable
fun ClearOfCameraBand(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().clearOf(cameraBand()), content = content)
}

/**
 * The log line for a window whose content Android draws [offset] px below the window's top, or
 * above it for less than 0: what Android does to show a focused control it thinks covered, such
 * as one in the cover's camera band (see [CameraBand]). Nothing in Satori moves the window itself.
 */
fun windowScrollLine(offset: Int): String = when {
    offset > 0 -> "Window drawn $offset px lower than its top: Android scrolled it to show the focused control"
    offset < 0 -> "Window drawn ${-offset} px higher than its top: Android scrolled it to show the focused control"
    else -> "Window drawn at its top again"
}

/** A cutout at least this share of the window across it is a band, not a punch hole. */
private const val MIN_BAND_SHARE = 0.15f

/** A band deeper than this share of the window across it would leave too little beside it. */
private const val MAX_DEPTH_SHARE = 0.5f

/** Cameras longer than this share of the band leave no room beside them worth using. */
private const val MAX_CAMERA_SHARE = 0.7f

/** Debug builds: the size of the cameras to draw in on a display without a band ([simulatedCameras]). */
private const val SIMULATED_BAND_PROPERTY = "debug.satori.cover.band"
