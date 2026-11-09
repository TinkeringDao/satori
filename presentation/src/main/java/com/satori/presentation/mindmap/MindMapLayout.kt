package com.satori.presentation.mindmap

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A place on the map around the focus: on ring [ring] (1 is the innermost) at [angle] radians,
 * clockwise from 12 o'clock. Where that is in pixels depends on how far apart the rings are drawn
 * ([MindMapLayout.ringRadii]) and on the zoom. On an ellipse the angle is the ellipse's own, which
 * puts a spot at 3 o'clock at the ring's full width.
 */
data class Spot(val ring: Int, val angle: Double) {
    /** Offset to the right of the centre for a ring of [radius]. */
    fun x(radius: Double): Double = radius * sin(angle)

    /** Offset below the centre (screen y grows downwards) for a ring of [radius]. */
    fun y(radius: Double): Double = -radius * cos(angle)

    /** Offset to the right of the centre on its ring of [radii]. */
    fun x(radii: RingRadii): Double = x(radii.xOf(ring))

    /** Offset below the centre on its ring of [radii]. */
    fun y(radii: RingRadii): Double = y(radii.of(ring))
}

/** The size of something drawn, in pixels. */
data class BoxSize(val width: Float, val height: Float)

/**
 * The spacing of a recording's own map, in pixels ([MindMapLayout.outline]).
 *
 * @property levelGap From the bottom of the recording's pill to the top of the topics' pills
 * @property inset Between a column's edge and what is in it
 * @property spineX From the left of a topic's pill to the line its points hang from
 * @property dotGap From that line to the start of a point's text
 * @property firstGap From the bottom of a topic's pill to its first point
 * @property rowGap Between two points
 */
data class OutlineMetrics(
    val levelGap: Float,
    val inset: Float,
    val spineX: Float,
    val dotGap: Float,
    val firstGap: Float,
    val rowGap: Float
)

/**
 * A point of a recording's own map: its dot on the line under its topic, and its text beside it.
 */
data class OutlinePoint(val x: Float, val y: Float, val label: MapBox)

/**
 * Where a recording's own map goes, in pixels from the centre of the recording's pill.
 *
 * @property topics The topics' pills, in the order the topics were given
 * @property points The points of each topic, in order; points[i] are those of topics[i]
 * @property bottom How far down the lowest thing drawn reaches
 */
data class Outline(val topics: List<MapBox>, val points: List<List<OutlinePoint>>, val bottom: Float)

/**
 * How far from the centre the rings are drawn, in pixels: up and down ring 1 at [first] and each
 * next one [gap] further, to the sides at [firstX] and [gapX]. The two are the same, circles,
 * unless the canvas is wider than tall ([MindMapLayout.ringRadii]).
 */
data class RingRadii(val first: Double, val gap: Double, val firstX: Double = first, val gapX: Double = gap) {
    /** How far up and down ring [ring] (from 1) reaches; a circle's radius. */
    fun of(ring: Int): Double = first + (ring - 1) * gap

    /** How far to the sides ring [ring] (from 1) reaches. */
    fun xOf(ring: Int): Double = firstX + (ring - 1) * gapX
}

/** A box on screen, in pixels: a label's, a pill's, or the focus's. */
data class MapBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    /** Whether the two boxes share any area; boxes that only touch don't overlap. */
    fun overlaps(other: MapBox): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    /** Whether ([x], [y]) is inside the box, its edges included. */
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

/**
 * Where the nodes of one view of the mind map go: the focus in the centre and its children on
 * rings around it, deterministic and without physics, so a view looks the same on every visit
 * and the user remembers where things are. Pure math, unit-tested.
 *
 * Two layouts, written for a phone rather than ported:
 * - [rings] for a focus and its children: concentric rings starting at 12 o'clock, as in the
 *   radial layout of mind-map tools such as xmind, but a ring holds only as many nodes as its
 *   length leaves room for, and the rest move to the next ring instead of piling up. On a canvas
 *   wider than tall, the cover screen's, the rings are ellipses that use its width and stay within
 *   its height.
 * - [outline] for a recording's own map, MapifyAI's transcript, topics and points: a tree from the
 *   top down, as in xmind's tree layout, with each topic's points listed under it, because points
 *   are whole sentences that a ring around the topic has no room for on a phone held upright.
 */
object MindMapLayout {

    /** The most rings a view draws; past them the outermost takes the rest, closer together. */
    const val MAX_RINGS = 3

    private const val FULL = 2 * PI

    /**
     * The length of a ring that reaches [radiusX] to the sides and [radiusY] up and down: a
     * circle's circumference, or an ellipse's by Ramanujan's first approximation, which is within
     * half a percent for any ellipse and exact for a circle.
     */
    fun ringLength(radiusX: Double, radiusY: Double): Double =
        if (radiusX == radiusY) {
            FULL * radiusX
        } else {
            PI * (3 * (radiusX + radiusY) - sqrt((3 * radiusX + radiusY) * (radiusX + 3 * radiusY)))
        }

    /**
     * How many nodes a ring of [radiusX] by [radiusY] holds, at least one: neighbours [minArc] apart
     * along it, and nowhere closer than [minChord] in a straight line. On an ellipse the nodes are
     * evenly spaced by its angle. That spreads them out along its long sides (12 and 6 o'clock on a
     * wide canvas), where the labels above and below them need the width, and brings them closer
     * together at the ends of its long axis (3 and 9 o'clock), where the labels are stacked: two
     * neighbours either side of such an end are 2 b sin(pi / n) apart, b the short radius, the
     * least of any two. [minChord] keeps that a finger's width; on a circle [minArc] is the
     * stricter of the two.
     */
    fun ringCapacity(radiusX: Double, radiusY: Double, minArc: Double, minChord: Double): Int {
        val byLength = floor(ringLength(radiusX, radiusY) / minArc).toInt()
        return max(1, min(byLength, chordCapacity(min(radiusX, radiusY), minChord)))
    }

    /**
     * The most nodes, evenly spaced by angle, that a ring with the short radius [short] holds with
     * no two closer than [minChord]: two sit 2 [short] apart at least, one has no neighbour.
     */
    private fun chordCapacity(short: Double, minChord: Double): Int {
        if (minChord <= 0.0) return Int.MAX_VALUE
        val half = minChord / (2 * short)
        if (half > 1.0) return 1
        return floor(PI / asin(half) + ROUNDING).toInt()
    }

    /** How many nodes ring [ring] of [radii] holds, as [ringCapacity] counts them. */
    private fun capacityOf(radii: RingRadii, ring: Int, minArc: Double, minChord: Double): Int =
        ringCapacity(radii.xOf(ring), radii.of(ring), minArc, minChord)

    /**
     * The fewest rings, up to [maxRings] and never more than [MAX_RINGS], that hold [count]
     * children as [ringCapacity] counts them, where [radiiFor] tells how far out the rings are
     * drawn when there are so many of them (more rings sit closer together). With more children
     * than that many rings hold, all of them: the outermost then takes the rest.
     */
    fun ringsNeeded(count: Int, minArc: Double, minChord: Double, maxRings: Int, radiiFor: (rings: Int) -> RingRadii): Int {
        val most = maxRings.coerceIn(1, MAX_RINGS)
        for (rings in 1 until most) {
            val radii = radiiFor(rings)
            if ((1..rings).sumOf { capacityOf(radii, it, minArc, minChord) } >= count) return rings
        }
        return most
    }

    /**
     * Places [count] children around the focus on [ringCount] rings at [radii], the most important
     * first: ring 1 fills first, as many as it holds ([ringCapacity] with [minArc] and [minChord]),
     * then the next ring, and the outermost takes all that are left. Each ring is evenly spaced and
     * goes clockwise.
     *
     * @param leadCount How many of the first children belong together and sit centred on 12
     *   o'clock, such as a theme's topics, with the rest (its recordings) going on round the ring
     *   to the bottom. With 1 the first child is at 12 o'clock.
     */
    fun rings(count: Int, leadCount: Int, ringCount: Int, radii: RingRadii, minArc: Double, minChord: Double): List<Spot> {
        if (count <= 0) return emptyList()
        val spots = ArrayList<Spot>(count)
        var placed = 0
        var ring = 1
        while (placed < count) {
            val left = count - placed
            val onRing = if (ring >= ringCount) left else min(left, capacityOf(radii, ring, minArc, minChord))
            val step = FULL / onRing
            val start = if (ring == 1) {
                val lead = leadCount.coerceIn(1, onRing)
                -(lead - 1) * step / 2
            } else {
                // Half a step on, so a node isn't hidden behind the one on the ring inside it.
                step / 2
            }
            repeat(onRing) { index -> spots += Spot(ring, normalize(start + index * step)) }
            placed += onRing
            ring++
        }
        return spots
    }

    /**
     * The widest a topic's pill may be in a recording's own map, its text included, so that
     * [topicCount] topics side by side fill [width].
     */
    fun outlineColumnWidth(width: Float, topicCount: Int, metrics: OutlineMetrics): Float =
        width / topicCount.coerceAtLeast(1) - 2 * metrics.inset

    /** The widest a point's text may be in a recording's own map, beside its line. */
    fun outlinePointWidth(width: Float, topicCount: Int, metrics: OutlineMetrics): Float =
        (outlineColumnWidth(width, topicCount, metrics) - metrics.spineX - metrics.dotGap).coerceAtLeast(0f)

    /**
     * A recording's own map, top down: the recording's pill at (0, 0), its topics in a row of
     * columns below it across [width], each topic's pill at the top left of its column, and its
     * points listed under it, each a dot on a line hanging from the pill with its text beside the
     * dot. Sizes are measured by the caller (the pills and texts as they will be drawn).
     *
     * @param centre The recording's pill
     * @param topics The topics' pills, in the order the AI named the topics
     * @param points The texts of each topic's points, in order
     */
    fun outline(
        width: Float,
        centre: BoxSize,
        topics: List<BoxSize>,
        points: List<List<BoxSize>>,
        metrics: OutlineMetrics
    ): Outline {
        if (topics.isEmpty()) return Outline(emptyList(), emptyList(), centre.height / 2)
        val column = width / topics.size
        val top = centre.height / 2 + metrics.levelGap
        var bottom = top
        val pills = ArrayList<MapBox>(topics.size)
        val placed = ArrayList<List<OutlinePoint>>(topics.size)
        topics.forEachIndexed { index, size ->
            val left = -width / 2 + index * column + metrics.inset
            val pill = MapBox(left, top, left + size.width, top + size.height)
            pills += pill
            val spine = left + metrics.spineX
            var cursor = pill.bottom + metrics.firstGap
            placed += points.getOrElse(index) { emptyList() }.map { text ->
                val label = MapBox(spine + metrics.dotGap, cursor, spine + metrics.dotGap + text.width, cursor + text.height)
                cursor = label.bottom + metrics.rowGap
                OutlinePoint(spine, (label.top + label.bottom) / 2, label)
            }
            bottom = max(bottom, placed.last().lastOrNull()?.label?.bottom ?: pill.bottom)
        }
        return Outline(pills, placed, bottom)
    }

    /**
     * How many rings, at most [MAX_RINGS], a canvas of [widthPx] by [heightPx] makes room for. A
     * square or tall canvas takes them all: its circles keep [minGapPx] apart, and a map with many
     * rings reaches past its edges, to be panned. A canvas wider than tall, the cover screen's, is
     * too low for that: of the 160 dp from its centre to its top, the focus and the labels at 12
     * o'clock take some 120. Its ellipses keep within the canvas ([ringRadii]) and may come closer
     * together than [minGapPx], to [CLOSE_GAP] of it, since a ring's nodes sit half a step round
     * from those of the ring inside and so are seldom right above them. As many rings as fit that
     * way on both axes, and at least one: past them the outermost holds more, closer together.
     *
     * @param clearanceXPx The least distance of ring 1 from the centre to the sides
     * @param clearanceYPx The least distance of ring 1 from the centre up and down
     * @param labelMarginPx Room kept free outside the outermost ring for its labels
     */
    fun ringsThatFit(
        widthPx: Double,
        heightPx: Double,
        clearanceXPx: Double,
        clearanceYPx: Double,
        labelMarginPx: Double,
        minGapPx: Double
    ): Int {
        if (widthPx <= heightPx || minGapPx <= 0.0) return MAX_RINGS
        val closest = minGapPx * CLOSE_GAP
        fun fit(available: Double, clearance: Double): Int =
            if (available <= clearance) 1 else 1 + floor((available - clearance) / closest + ROUNDING).toInt().coerceAtMost(MAX_RINGS)
        return min(fit(heightPx / 2 - labelMarginPx, clearanceYPx), fit(widthPx / 2 - labelMarginPx, clearanceXPx))
            .coerceIn(1, MAX_RINGS)
    }

    /**
     * How far apart the rings are drawn so that [rings] rings and their labels fit a canvas of
     * [widthPx] by [heightPx]: ring 1 just clear of the focus, the others evenly spaced up to the
     * edge, less the room the outermost labels need.
     *
     * A square or tall canvas draws circles, which pass the focus at 3 o'clock as at 12: ring 1
     * clears the larger of [clearanceXPx] and [clearanceYPx], and the rings keep [minGapPx] apart,
     * also when that takes the outermost past the room.
     *
     * A canvas wider than tall draws ellipses, which reach to the sides as far as its width allows
     * and up and down as far as its height does: on the cover screen's canvas, about 480 by 320 dp,
     * circles as tall as the canvas left a third of its width empty and hid four of the eight theme
     * labels behind each other until a pinch. Ring 1 clears the focus by [clearanceXPx] to the sides
     * and [clearanceYPx] up and down, so a theme's wide pill doesn't push the rings past the canvas's
     * low height, and the outermost ring stays within the room on both axes, so its labels stay
     * inside: the rings share what lies between, closer together than [minGapPx] if they must.
     * [ringsThatFit] says how many rings to ask for, so they don't come too close.
     *
     * @param clearanceXPx The least distance of ring 1 from the centre to the sides, past the
     *   focus's own shape
     * @param clearanceYPx The least distance of ring 1 from the centre up and down, past the focus's
     *   own shape
     * @param labelMarginPx Room kept free outside the outermost ring for its labels
     * @param minGapPx The least distance between two circles, also on a small canvas
     * @param maxFirstPx The farthest up and down ring 1 is drawn when it is the only one, so a few
     *   children don't drift to the edges of a large canvas; to the sides of a wide one it may
     *   reach as much further as the canvas is wider than tall
     */
    fun ringRadii(
        widthPx: Double,
        heightPx: Double,
        rings: Int,
        clearanceXPx: Double,
        clearanceYPx: Double,
        labelMarginPx: Double,
        minGapPx: Double,
        maxFirstPx: Double
    ): RingRadii {
        if (widthPx <= heightPx) {
            val circle = axis(widthPx / 2 - labelMarginPx, rings, max(clearanceXPx, clearanceYPx), minGapPx, maxFirstPx, withinRoom = false)
            return RingRadii(circle.first, circle.gap)
        }
        val vertical = axis(heightPx / 2 - labelMarginPx, rings, clearanceYPx, minGapPx, maxFirstPx, withinRoom = true)
        val horizontal = axis(widthPx / 2 - labelMarginPx, rings, clearanceXPx, minGapPx, maxFirstPx * widthPx / heightPx, withinRoom = true)
        return RingRadii(vertical.first, vertical.gap, horizontal.first, horizontal.gap)
    }

    /** Ring 1 and the gap between rings along one axis. */
    private data class Axis(val first: Double, val gap: Double)

    /**
     * Ring 1 and the gap along an axis with [available] pixels from the centre to the outermost
     * ring: [rings] rings share it evenly, a lone ring goes no further than [maxFirstPx], and ring 1
     * comes no closer than [clearancePx]. The gap is at least [minGapPx], even past the room, unless
     * the rings stay [withinRoom]: then the outermost reaches no further than [available].
     */
    private fun axis(
        available: Double,
        rings: Int,
        clearancePx: Double,
        minGapPx: Double,
        maxFirstPx: Double,
        withinRoom: Boolean
    ): Axis {
        if (rings <= 1) return Axis(available.coerceAtMost(maxFirstPx).coerceAtLeast(clearancePx), minGapPx)
        val first = max(clearancePx, available / rings)
        val even = (available - first) / (rings - 1)
        return Axis(first, if (withinRoom) max(0.0, even) else max(minGapPx, even))
    }

    /**
     * The box of a label of [width] by [height] for a node at ([x], [y]) on a line from the centre
     * at [angle], centred on the node and [gap] away from it: above a node in the upper half of the
     * ring, below one in the lower half or level with the centre. So the label is on the far side
     * from the line that leads to the node, and it never reaches sideways past its node by more
     * than half its width: on a phone held upright there is room above and below, not to the sides.
     */
    fun labelBox(angle: Double, x: Float, y: Float, gap: Float, width: Float, height: Float): MapBox {
        val above = cos(angle) > LEVEL
        val left = x - width / 2
        val top = if (above) y - gap - height else y + gap
        return MapBox(left, top, left + width, top + height)
    }

    /** Below this a node counts as level with the centre: cos(90 degrees) is not quite 0 in doubles. */
    private const val LEVEL = 1e-9

    /** Room for the rounding of doubles, so a ring count that comes out whole isn't floored one short. */
    private const val ROUNDING = 1e-9

    /** On a canvas wider than tall the rings may come this close, as a share of the least gap ([ringsThatFit]). */
    private const val CLOSE_GAP = 0.75

    /**
     * Which labels to draw so that none covers another: in [boxes]' order, which is the order of
     * importance, each label is drawn unless it overlaps one already drawn. The first [keep] are
     * always drawn (the focus). The labels left out appear as the user zooms in and the nodes move
     * apart; their nodes are drawn either way and keep their content descriptions.
     */
    fun visibleLabels(boxes: List<MapBox>, keep: Int = 0): BooleanArray {
        val visible = BooleanArray(boxes.size)
        val drawn = ArrayList<MapBox>(boxes.size)
        boxes.forEachIndexed { index, box ->
            if (index < keep || drawn.none { it.overlaps(box) }) {
                visible[index] = true
                drawn += box
            }
        }
        return visible
    }

    /** [angle] within 0 (inclusive) and a full turn (exclusive). */
    private fun normalize(angle: Double): Double {
        val turned = angle % FULL
        return if (turned < 0) turned + FULL else turned
    }
}
