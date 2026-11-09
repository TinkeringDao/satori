package com.satori.presentation.mindmap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

class MindMapLayoutTest {

    private val eps = 1e-9

    private fun assertAngles(expected: List<Double>, spots: List<Spot>) {
        assertEquals(expected.size, spots.size)
        expected.zip(spots).forEach { (angle, spot) -> assertEquals(0.0, distance(angle, spot.angle), eps) }
    }

    /** The angular distance between two angles, the short way round. */
    private fun distance(a: Double, b: Double): Double {
        val d = abs(a - b) % (2 * PI)
        return if (d > PI) 2 * PI - d else d
    }

    // ─── Rings ───────────────────────────────────────────────────────────────

    /**
     * Rings 100 apart from 100 out, with neighbours at least 90 apart along a ring and 48 in a
     * straight line: 6 on ring 1, 13 on 2.
     */
    private val radii = RingRadii(first = 100.0, gap = 100.0)
    private val arc = 90.0
    private val chord = 48.0

    private fun rings(count: Int, lead: Int = 1, ringCount: Int = 3) = MindMapLayout.rings(count, lead, ringCount, radii, arc, chord)

    @Test
    fun `a ring holds as many as its length leaves room for`() {
        assertEquals(6, MindMapLayout.ringCapacity(100.0, 100.0, 90.0, chord))
        assertEquals(13, MindMapLayout.ringCapacity(200.0, 200.0, 90.0, chord))
        assertEquals(1, MindMapLayout.ringCapacity(5.0, 5.0, 90.0, chord))
    }

    @Test
    fun `an ellipse is as long as its two radii make it`() {
        assertEquals(2 * PI * 100.0, MindMapLayout.ringLength(100.0, 100.0), eps)
        // 2 by 1: 9.6884 times the short radius.
        assertEquals(968.84, MindMapLayout.ringLength(200.0, 100.0), 0.1)
        assertEquals(MindMapLayout.ringLength(200.0, 100.0), MindMapLayout.ringLength(100.0, 200.0), eps)
        // An ellipse holds more than the circle of its short radius, fewer than that of its long one.
        assertEquals(10, MindMapLayout.ringCapacity(200.0, 100.0, 90.0, chord))
    }

    @Test
    fun `as few rings as hold the children, the rings drawn closer as they get more`() {
        // One ring at 150 holds 10; two at 75 and 150 hold 5 and 10; three at 50, 100, 150 hold 3, 6, 10.
        val radiiFor = { rings: Int -> RingRadii(first = 150.0 / rings, gap = 150.0 / rings) }

        assertEquals(1, MindMapLayout.ringsNeeded(10, arc, chord, MindMapLayout.MAX_RINGS, radiiFor))
        assertEquals(2, MindMapLayout.ringsNeeded(11, arc, chord, MindMapLayout.MAX_RINGS, radiiFor))
        assertEquals(2, MindMapLayout.ringsNeeded(15, arc, chord, MindMapLayout.MAX_RINGS, radiiFor))
        assertEquals(3, MindMapLayout.ringsNeeded(16, arc, chord, MindMapLayout.MAX_RINGS, radiiFor))
        assertEquals(MindMapLayout.MAX_RINGS, MindMapLayout.ringsNeeded(400, arc, chord, MindMapLayout.MAX_RINGS, radiiFor))
    }

    @Test
    fun `no more rings than the canvas makes room for, the outermost taking the rest`() {
        val radiiFor = { rings: Int -> RingRadii(first = 150.0 / rings, gap = 150.0 / rings) }

        assertEquals(1, MindMapLayout.ringsNeeded(10, arc, chord, maxRings = 2, radiiFor))
        assertEquals(2, MindMapLayout.ringsNeeded(16, arc, chord, maxRings = 2, radiiFor))
        assertEquals(1, MindMapLayout.ringsNeeded(16, arc, chord, maxRings = 1, radiiFor))
        assertEquals(1, MindMapLayout.ringsNeeded(16, arc, chord, maxRings = 0, radiiFor))
        assertEquals(MindMapLayout.MAX_RINGS, MindMapLayout.ringsNeeded(400, arc, chord, maxRings = 9, radiiFor))
    }

    @Test
    fun `no children take no place`() {
        assertEquals(emptyList<Spot>(), rings(0))
        assertEquals(emptyList<Spot>(), rings(-1))
    }

    @Test
    fun `the first child sits at twelve o'clock`() {
        val only = rings(1).single()

        assertEquals(1, only.ring)
        assertEquals(0.0, only.angle, eps)
        assertEquals(0.0, only.x(1.0), eps)
        assertEquals(-1.0, only.y(1.0), eps)
    }

    @Test
    fun `children follow clockwise, evenly spaced`() {
        val spots = rings(4)

        assertAngles(listOf(0.0, PI / 2, PI, 3 * PI / 2), spots)
        // The second is to the right of the centre, the third below it.
        assertEquals(1.0, spots[1].x(1.0), eps)
        assertEquals(1.0, spots[2].y(1.0), eps)
        assertTrue(spots.all { it.ring == 1 })
    }

    @Test
    fun `a full ring sends the rest to the next one`() {
        val spots = rings(9)

        assertEquals(List(6) { 1 } + List(3) { 2 }, spots.map { it.ring })
    }

    @Test
    fun `the outermost ring takes all that are left`() {
        val spots = rings(40, ringCount = 2)

        assertEquals(6, spots.count { it.ring == 1 })
        assertEquals(34, spots.count { it.ring == 2 })
    }

    @Test
    fun `an outer ring starts half a step on, so its nodes sit between those inside it`() {
        val outer = rings(8).filter { it.ring == 2 }

        assertAngles(listOf(PI / 2, 3 * PI / 2), outer)
    }

    @Test
    fun `a lead group sits centred on twelve o'clock`() {
        val spots = rings(6, lead = 2)
        val step = 2 * PI / 6

        assertEquals(0.0, distance(2 * PI - step / 2, spots[0].angle), eps)
        assertEquals(0.0, distance(step / 2, spots[1].angle), eps)
        // Mirror images: the same height, opposite sides.
        assertEquals(spots[0].y(1.0), spots[1].y(1.0), eps)
        assertEquals(-spots[0].x(1.0), spots[1].x(1.0), eps)
    }

    @Test
    fun `a lead group larger than the ring counts as the whole ring`() {
        assertEquals(rings(3, lead = 3), rings(3, lead = 12))
        assertEquals(rings(3), rings(3, lead = 0))
    }

    @Test
    fun `the same children always get the same places`() {
        assertEquals(rings(30, lead = 3), rings(30, lead = 3))
    }

    @Test
    fun `every angle is within one turn`() {
        rings(45, lead = 5).forEach { assertTrue(it.angle >= 0 && it.angle < 2 * PI) }
    }

    // ─── Outline ─────────────────────────────────────────────────────────────

    private val metrics = OutlineMetrics(levelGap = 40f, inset = 8f, spineX = 12f, dotGap = 10f, firstGap = 14f, rowGap = 10f)

    @Test
    fun `a recording without topics is only its own pill`() {
        val outline = MindMapLayout.outline(400f, BoxSize(120f, 40f), emptyList(), emptyList(), metrics)

        assertEquals(emptyList<MapBox>(), outline.topics)
        assertEquals(20f, outline.bottom)
    }

    @Test
    fun `topics sit in a row of equal columns below the recording`() {
        val outline = MindMapLayout.outline(
            width = 300f,
            centre = BoxSize(120f, 40f),
            topics = listOf(BoxSize(60f, 30f), BoxSize(80f, 30f), BoxSize(50f, 30f)),
            points = listOf(emptyList(), emptyList(), emptyList()),
            metrics = metrics
        )

        // Columns of 100 from -150; each pill at its column's left, past the inset.
        assertEquals(listOf(-142f, -42f, 58f), outline.topics.map { it.left })
        assertEquals(listOf(-82f, 38f, 108f), outline.topics.map { it.right })
        // All at one height: half the recording's pill and the gap below its centre.
        assertTrue(outline.topics.all { it.top == 60f && it.bottom == 90f })
        assertEquals(90f, outline.bottom)
    }

    @Test
    fun `a topic's points hang under it in order, each dot centred on its text`() {
        val outline = MindMapLayout.outline(
            width = 300f,
            centre = BoxSize(120f, 40f),
            topics = listOf(BoxSize(60f, 30f), BoxSize(60f, 30f)),
            points = listOf(listOf(BoxSize(90f, 20f), BoxSize(70f, 36f)), listOf(BoxSize(40f, 20f))),
            metrics = metrics
        )

        val first = outline.points[0]
        // The line hangs 12 right of the pill's left; texts start 10 past it.
        assertEquals(listOf(-130f, -130f), first.map { it.x })
        assertEquals(listOf(-120f, -120f), first.map { it.label.left })
        // Under the pill (bottom 90) after the first gap, then one row gap between texts.
        assertEquals(104f, first[0].label.top)
        assertEquals(134f, first[1].label.top)
        assertEquals(114f, first[0].y)
        assertEquals(152f, first[1].y)
        assertEquals(20f, outline.points[1].single().x)
        assertEquals(170f, outline.bottom)
    }

    @Test
    fun `one topic has the whole width`() {
        assertEquals(384f, MindMapLayout.outlineColumnWidth(400f, 1, metrics))
        assertEquals(362f, MindMapLayout.outlinePointWidth(400f, 1, metrics))
        assertEquals(117.333f, MindMapLayout.outlineColumnWidth(400f, 3, metrics), 0.001f)
        assertEquals(0f, MindMapLayout.outlinePointWidth(30f, 3, metrics))
    }

    // ─── Radii ───────────────────────────────────────────────────────────────

    @Test
    fun `one ring goes as far out as the canvas allows`() {
        val radii = MindMapLayout.ringRadii(800.0, 1000.0, rings = 1, clearanceXPx = 100.0, clearanceYPx = 100.0, labelMarginPx = 50.0, minGapPx = 60.0, maxFirstPx = 500.0)

        assertEquals(350.0, radii.first, eps)
    }

    @Test
    fun `one ring stays near the centre on a large canvas and clear of it on a small one`() {
        val large = MindMapLayout.ringRadii(2000.0, 2000.0, 1, 100.0, 100.0, 50.0, 60.0, maxFirstPx = 400.0)
        val small = MindMapLayout.ringRadii(200.0, 200.0, 1, 100.0, 100.0, 50.0, 60.0, maxFirstPx = 400.0)

        assertEquals(400.0, large.first, eps)
        assertEquals(100.0, small.first, eps)
    }

    @Test
    fun `several rings share the room evenly`() {
        val radii = MindMapLayout.ringRadii(1000.0, 1000.0, rings = 3, clearanceXPx = 100.0, clearanceYPx = 100.0, labelMarginPx = 50.0, minGapPx = 60.0, maxFirstPx = 500.0)

        assertEquals(150.0, radii.first, eps)
        assertEquals(150.0, radii.gap, eps)
        assertEquals(450.0, radii.of(3), eps)
    }

    @Test
    fun `rings keep their least distances on a small canvas`() {
        val radii = MindMapLayout.ringRadii(300.0, 300.0, rings = 2, clearanceXPx = 120.0, clearanceYPx = 80.0, labelMarginPx = 50.0, minGapPx = 60.0, maxFirstPx = 500.0)

        // A circle clears the focus's larger side all round, and keeps its rings apart even past the room.
        assertEquals(120.0, radii.first, eps)
        assertEquals(60.0, radii.gap, eps)
        assertEquals(radii.of(2), radii.xOf(2), eps)
    }

    @Test
    fun `a square or tall canvas keeps circles`() {
        val square = MindMapLayout.ringRadii(1000.0, 1000.0, rings = 3, clearanceXPx = 100.0, clearanceYPx = 100.0, labelMarginPx = 50.0, minGapPx = 60.0, maxFirstPx = 500.0)
        val tall = MindMapLayout.ringRadii(800.0, 1000.0, rings = 1, clearanceXPx = 100.0, clearanceYPx = 100.0, labelMarginPx = 50.0, minGapPx = 60.0, maxFirstPx = 500.0)

        assertEquals(RingRadii(first = 150.0, gap = 150.0, firstX = 150.0, gapX = 150.0), square)
        assertEquals(tall.of(1), tall.xOf(1), eps)
        assertEquals(350.0, tall.first, eps)
    }

    @Test
    fun `a canvas wider than tall draws ellipses that reach to its sides`() {
        val radii = MindMapLayout.ringRadii(1000.0, 600.0, rings = 2, clearanceXPx = 100.0, clearanceYPx = 100.0, labelMarginPx = 50.0, minGapPx = 60.0, maxFirstPx = 500.0)

        // Up and down as the height allows, to the sides as the width does.
        assertEquals(250.0, radii.of(2), eps)
        assertEquals(450.0, radii.xOf(2), eps)
        assertTrue(radii.xOf(1) > radii.of(1))
    }

    @Test
    fun `a lone ring on a wide canvas keeps the canvas's shape rather than its cap`() {
        val radii = MindMapLayout.ringRadii(480.0, 322.0, rings = 1, clearanceXPx = 64.0, clearanceYPx = 64.0, labelMarginPx = 60.0, minGapPx = 48.0, maxFirstPx = 150.0)

        assertEquals(101.0, radii.of(1), eps)
        assertEquals(180.0, radii.xOf(1), eps)
    }

    @Test
    fun `a spot on an ellipse is at its full width at three o'clock and its full height at twelve`() {
        val radii = RingRadii(first = 100.0, gap = 50.0, firstX = 180.0, gapX = 90.0)

        assertEquals(180.0, Spot(1, PI / 2).x(radii), eps)
        assertEquals(0.0, Spot(1, PI / 2).y(radii), eps)
        assertEquals(-100.0, Spot(1, 0.0).y(radii), eps)
        assertEquals(270.0, Spot(2, PI / 2).x(radii), eps)
        assertEquals(150.0, Spot(2, PI).y(radii), eps)
    }

    @Test
    fun `on a wide canvas ring 1 clears a wide focus by its width to the sides and its height up and down`() {
        // A theme's pill of 164 by 60 on the cover screen's canvas, cleared by 28.
        val radii = MindMapLayout.ringRadii(480.0, 322.0, rings = 2, clearanceXPx = 110.0, clearanceYPx = 58.0, labelMarginPx = 60.0, minGapPx = 48.0, maxFirstPx = 150.0)

        assertEquals(58.0, radii.of(1), eps)
        assertEquals(110.0, radii.xOf(1), eps)
    }

    @Test
    fun `on a wide canvas the outermost ring stays within the room, the rings closer together if they must`() {
        listOf(2, 3).forEach { rings ->
            val radii = MindMapLayout.ringRadii(480.0, 322.0, rings, clearanceXPx = 110.0, clearanceYPx = 58.0, labelMarginPx = 60.0, minGapPx = 48.0, maxFirstPx = 150.0)

            assertEquals("$rings rings", 101.0, radii.of(rings), eps)
            assertEquals("$rings rings", 180.0, radii.xOf(rings), eps)
            assertTrue("$rings rings", radii.gap < 48.0)
        }
    }

    @Test
    fun `a wide canvas makes room for the rings that fit closer together, a square or tall one for all`() {
        // On the cover 37 (beside "You") or 43 (beside a theme's pill) lie between ring 1 and the
        // room kept for the labels: room for a second ring, 36 or more further out.
        assertEquals(2, MindMapLayout.ringsThatFit(480.0, 322.0, clearanceXPx = 64.0, clearanceYPx = 64.0, labelMarginPx = 60.0, minGapPx = 48.0))
        assertEquals(2, MindMapLayout.ringsThatFit(480.0, 322.0, clearanceXPx = 110.0, clearanceYPx = 58.0, labelMarginPx = 60.0, minGapPx = 48.0))
        // Up and down there is room for a third, but the 70 beside a theme's pill hold no third one.
        assertEquals(2, MindMapLayout.ringsThatFit(480.0, 470.0, clearanceXPx = 110.0, clearanceYPx = 58.0, labelMarginPx = 60.0, minGapPx = 48.0))
        // No room between the focus and the labels: one ring.
        assertEquals(1, MindMapLayout.ringsThatFit(480.0, 200.0, clearanceXPx = 110.0, clearanceYPx = 58.0, labelMarginPx = 60.0, minGapPx = 48.0))
        // A large wide canvas: as many as a view draws.
        assertEquals(MindMapLayout.MAX_RINGS, MindMapLayout.ringsThatFit(2400.0, 1600.0, clearanceXPx = 64.0, clearanceYPx = 64.0, labelMarginPx = 60.0, minGapPx = 48.0))
        assertEquals(MindMapLayout.MAX_RINGS, MindMapLayout.ringsThatFit(400.0, 400.0, clearanceXPx = 110.0, clearanceYPx = 58.0, labelMarginPx = 60.0, minGapPx = 48.0))
        assertEquals(MindMapLayout.MAX_RINGS, MindMapLayout.ringsThatFit(400.0, 800.0, clearanceXPx = 110.0, clearanceYPx = 58.0, labelMarginPx = 60.0, minGapPx = 48.0))
    }

    @Test
    fun `a flat ellipse holds only as many as stay a finger apart where it bends most`() {
        // The inner screen turned sideways: by its length it would hold 20, 32 apart at 3 o'clock.
        assertEquals(20, MindMapLayout.ringCapacity(442.5, 101.5, 92.0, minChord = 0.0))
        assertEquals(13, MindMapLayout.ringCapacity(442.5, 101.5, 92.0, minChord = 48.0))
        // Upright or sideways, the same.
        assertEquals(13, MindMapLayout.ringCapacity(101.5, 442.5, 92.0, minChord = 48.0))
        // The cover's ring and a circle keep what their length gives.
        assertEquals(9, MindMapLayout.ringCapacity(180.0, 101.0, 92.0, minChord = 48.0))
        assertEquals(6, MindMapLayout.ringCapacity(100.0, 100.0, 90.0, minChord = 48.0))
        // Too small a ring for two a finger apart holds one.
        assertEquals(1, MindMapLayout.ringCapacity(300.0, 20.0, 10.0, minChord = 48.0))
        assertEquals(2, MindMapLayout.ringCapacity(300.0, 24.0, 10.0, minChord = 48.0))
    }

    /** What a drawing places, at a density of 1 so pixels are dp. */
    private class Drawn(val spots: List<Spot>, val radii: RingRadii, val nodes: List<MapBox>, val labels: List<MapBox>) {
        val points: List<Pair<Double, Double>> = spots.map { it.x(radii) to it.y(radii) }
    }

    /** "You", the top level's focus, 72 dp across. */
    private val you = BoxSize(72f, 72f)

    /** A theme's pill around a two-line name: 136 dp of text and 14 on each side, 2 lines of 20 and 10 above and below. */
    private val themePill = BoxSize(164f, 60f)

    /**
     * A view's drawing with MindMapCanvas's own sizes: the focus [centre] cleared by 28 dp, 60 dp
     * kept outside the outermost ring for the labels, rings 48 dp apart where there is room,
     * neighbours 92 dp along a ring and 48 dp in a straight line, labels of up to 112 by 36 dp,
     * dots of up to 12 dp and a pin's 3 dp more, 6 dp from a dot to its label.
     */
    private fun drawing(width: Double, height: Double, centre: BoxSize, children: Int, lead: Int = 1): Drawn {
        val clearanceX = centre.width / 2 + 28.0
        val clearanceY = centre.height / 2 + 28.0
        val radiiFor = { rings: Int ->
            MindMapLayout.ringRadii(width, height, rings, clearanceX, clearanceY, labelMarginPx = 60.0, minGapPx = 48.0, maxFirstPx = 150.0)
        }
        val most = MindMapLayout.ringsThatFit(width, height, clearanceX, clearanceY, labelMarginPx = 60.0, minGapPx = 48.0)
        val ringCount = MindMapLayout.ringsNeeded(children, minArc = 92.0, minChord = 48.0, maxRings = most, radiiFor = radiiFor)
        val radii = radiiFor(ringCount)
        val spots = MindMapLayout.rings(children, lead, ringCount, radii, minArc = 92.0, minChord = 48.0)
        val dot = 15f
        val nodes = spots.map { spot ->
            val x = spot.x(radii).toFloat()
            val y = spot.y(radii).toFloat()
            MapBox(x - dot, y - dot, x + dot, y + dot)
        }
        val labels = spots.map { spot ->
            MindMapLayout.labelBox(spot.angle, spot.x(radii).toFloat(), spot.y(radii).toFloat(), gap = 18f, width = 112f, height = 36f)
        }
        return Drawn(spots, radii, nodes, labels)
    }

    private fun assertInside(drawn: Drawn, width: Double, height: Double, what: String) {
        (drawn.nodes + drawn.labels).forEach { box ->
            assertTrue("$what: $box", box.left >= -width / 2 && box.right <= width / 2)
            assertTrue("$what: $box", box.top >= -height / 2 && box.bottom <= height / 2)
        }
    }

    /** The cover screen's canvas under the top bar, and above the camera band without the bar. */
    private val covers = listOf(480.0 to 322.0, 480.0 to 386.0)

    @Test
    fun `on the cover every node and label of the top level lies inside the canvas, however many themes`() {
        covers.forEach { (width, height) ->
            (1..MindMapRules.CANVAS_NODES).forEach { themes ->
                assertInside(drawing(width, height, you, themes), width, height, "$themes themes in $width x $height")
            }
        }
    }

    @Test
    fun `on the cover every node and label of a theme lies inside the canvas, however many recordings`() {
        covers.forEach { (width, height) ->
            (1..MindMapRules.CANVAS_NODES).forEach { children ->
                val topics = minOf(3, children)
                assertInside(drawing(width, height, themePill, children, lead = topics), width, height, "$children children in $width x $height")
            }
        }
    }

    @Test
    fun `on the cover the rings keep three quarters of their gap apart, however many children`() {
        covers.forEach { (width, height) ->
            (1..MindMapRules.CANVAS_NODES).forEach { children ->
                listOf(drawing(width, height, you, children), drawing(width, height, themePill, children, lead = minOf(3, children)))
                    .filter { drawn -> drawn.spots.maxOf { it.ring } > 1 }
                    .forEach { drawn ->
                        assertTrue("$children in $width x $height: ${drawn.radii}", drawn.radii.gap >= 36.0 - 1e-6)
                        assertTrue("$children in $width x $height: ${drawn.radii}", drawn.radii.gapX >= 36.0 - 1e-6)
                    }
            }
        }
    }

    @Test
    fun `on the inner screen turned sideways every node and label lies inside the canvas`() {
        (1..MindMapRules.CANVAS_NODES).forEach { children ->
            assertInside(drawing(1005.0, 323.0, you, children), 1005.0, 323.0, "$children themes")
            assertInside(drawing(1005.0, 323.0, themePill, children, lead = minOf(3, children)), 1005.0, 323.0, "$children children")
        }
    }

    /**
     * The least distance between two neighbours on the same ring, the outermost included: while
     * the rings hold every child, that is the least of [ringCapacity]'s two.
     */
    private fun closestNeighbours(drawn: Drawn): Double =
        drawn.spots.indices.groupBy { drawn.spots[it].ring }.values.minOf { onRing ->
            val around = onRing.sortedBy { drawn.spots[it].angle }
            if (around.size < 2) {
                Double.MAX_VALUE
            } else {
                around.indices.minOf { index ->
                    val (x1, y1) = drawn.points[around[index]]
                    val (x2, y2) = drawn.points[around[(index + 1) % around.size]]
                    hypot(x2 - x1, y2 - y1)
                }
            }
        }

    /** Whether the rings of [drawn] hold its children without the outermost taking more than it holds. */
    private fun roomFor(drawn: Drawn): Boolean {
        val rings = drawn.spots.maxOf { it.ring }
        return (1..rings).sumOf { MindMapLayout.ringCapacity(drawn.radii.xOf(it), drawn.radii.of(it), 92.0, 48.0) } >= drawn.spots.size
    }

    @Test
    fun `neighbours on a ring stay a finger apart while the rings have room for them, on the cover and sideways`() {
        val canvases = covers + (1005.0 to 323.0)
        canvases.forEach { (width, height) ->
            (2..MindMapRules.CANVAS_NODES).forEach { children ->
                listOf(drawing(width, height, you, children), drawing(width, height, themePill, children, lead = minOf(3, children)))
                    .filter(::roomFor)
                    .forEach { drawn -> assertTrue("$children in $width x $height", closestNeighbours(drawn) >= 48.0 - 1e-6) }
            }
        }
        // Sideways the twenty themes of a full top level now take two rings, not one with neighbours 38 apart.
        assertEquals(2, drawing(1005.0, 323.0, you, 20).spots.maxOf { it.ring })
    }

    @Test
    fun `on the cover the labels of eight themes all show, even at their widest`() {
        covers.forEach { (width, height) ->
            val drawn = drawing(width, height, you, 8)
            val centre = MapBox(-36f, -36f, 36f, 36f)
            val boxes = listOf(centre) + drawn.nodes + drawn.labels

            val visible = MindMapLayout.visibleLabels(boxes, keep = 1 + drawn.nodes.size)

            assertEquals("$width x $height", 8, visible.drop(1 + drawn.nodes.size).count { it })
        }
    }

    // ─── Labels ──────────────────────────────────────────────────────────────

    @Test
    fun `a label sits above a node in the upper half and below one in the lower half, centred`() {
        val top = MindMapLayout.labelBox(0.0, 100f, 100f, gap = 10f, width = 40f, height = 20f)
        val upperRight = MindMapLayout.labelBox(PI / 4, 100f, 100f, gap = 10f, width = 40f, height = 20f)
        val level = MindMapLayout.labelBox(PI / 2, 100f, 100f, gap = 10f, width = 40f, height = 20f)
        val bottom = MindMapLayout.labelBox(PI, 100f, 100f, gap = 10f, width = 40f, height = 20f)
        val upperLeft = MindMapLayout.labelBox(7 * PI / 4, 100f, 100f, gap = 10f, width = 40f, height = 20f)

        assertBox(MapBox(80f, 70f, 120f, 90f), top)
        assertBox(MapBox(80f, 70f, 120f, 90f), upperRight)
        assertBox(MapBox(80f, 110f, 120f, 130f), level)
        assertBox(MapBox(80f, 110f, 120f, 130f), bottom)
        assertBox(MapBox(80f, 70f, 120f, 90f), upperLeft)
        // Level on the left too.
        assertBox(MapBox(80f, 110f, 120f, 130f), MindMapLayout.labelBox(3 * PI / 2, 100f, 100f, 10f, 40f, 20f))
    }

    private fun assertBox(expected: MapBox, actual: MapBox) {
        assertEquals(expected.left, actual.left, 1e-3f)
        assertEquals(expected.top, actual.top, 1e-3f)
        assertEquals(expected.right, actual.right, 1e-3f)
        assertEquals(expected.bottom, actual.bottom, 1e-3f)
    }

    @Test
    fun `labels that don't overlap are all drawn`() {
        val boxes = listOf(MapBox(0f, 0f, 10f, 10f), MapBox(20f, 0f, 30f, 10f), MapBox(0f, 20f, 10f, 30f))

        assertArrayEquals(booleanArrayOf(true, true, true), MindMapLayout.visibleLabels(boxes))
    }

    @Test
    fun `a label over a more important one is left out`() {
        val boxes = listOf(MapBox(0f, 0f, 10f, 10f), MapBox(5f, 5f, 15f, 15f))

        assertArrayEquals(booleanArrayOf(true, false), MindMapLayout.visibleLabels(boxes))
    }

    @Test
    fun `labels that only touch are both drawn`() {
        val boxes = listOf(MapBox(0f, 0f, 10f, 10f), MapBox(10f, 0f, 20f, 10f))

        assertArrayEquals(booleanArrayOf(true, true), MindMapLayout.visibleLabels(boxes))
    }

    @Test
    fun `a label left out doesn't hide the next one`() {
        val boxes = listOf(MapBox(0f, 0f, 10f, 10f), MapBox(8f, 0f, 18f, 10f), MapBox(12f, 0f, 22f, 10f))

        assertArrayEquals(booleanArrayOf(true, false, true), MindMapLayout.visibleLabels(boxes))
    }

    @Test
    fun `the labels to keep are drawn whatever they cover`() {
        val boxes = listOf(MapBox(0f, 0f, 10f, 10f), MapBox(5f, 5f, 15f, 15f))

        assertArrayEquals(booleanArrayOf(true, true), MindMapLayout.visibleLabels(boxes, keep = 2))
    }

    @Test
    fun `a box contains its edges`() {
        val box = MapBox(0f, 0f, 10f, 10f)

        assertTrue(box.contains(10f, 0f))
        assertTrue(!box.contains(10.5f, 5f))
    }
}
