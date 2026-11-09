package com.satori.ui.screens.mindmap

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.satori.presentation.mindmap.BoxSize
import com.satori.presentation.mindmap.MapBox
import com.satori.presentation.mindmap.MapNode
import com.satori.presentation.mindmap.MapNodeKind
import com.satori.presentation.mindmap.MapScene
import com.satori.presentation.mindmap.MapTarget
import com.satori.presentation.mindmap.MindMapLayout
import com.satori.presentation.mindmap.MindMapRules
import com.satori.presentation.mindmap.OutlineMetrics
import com.satori.ui.design.Grille
import com.satori.ui.design.GrilleColors
import com.satori.ui.design.dotoNumberText
import com.satori.ui.design.rememberReducedMotion
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * A node of the drawing with its words: [label] whole (the drawing cuts it), what TalkBack says,
 * and for "You" the number of recordings shown in its circle.
 */
@Immutable
internal data class CanvasNode(
    val node: MapNode,
    val label: String,
    val description: String,
    val number: String? = null,
    val children: List<CanvasNode> = emptyList()
)

/**
 * The map: the focus in the centre and its children around it, drawn in the Grille style.
 * Hairlines of one pixel join them; a node's size says how many recordings it gathers and its
 * brightness how lately, never a colour (red only ever means the microphone is live). Labels are
 * Space Grotesk, cut at 19 characters; one that would cover a more important one waits until a
 * pinch spreads the nodes apart. Pinch zooms the spread, not the text, so labels stay readable at
 * any zoom; a drag pans.
 *
 * Themes, topics and recordings sit on rings around the focus ([MindMapLayout.rings]), each label
 * above or below its node, away from the line to it ([MindMapLayout.labelBox]). On a canvas wider
 * than tall (the cover screen) the rings are ellipses that use its width and keep within its
 * height, nodes and labels inside it ([MindMapLayout.ringRadii]). A recording's own map is a tree
 * from the top down instead ([MindMapLayout.outline]): its topics as pills in a row, each topic's
 * points listed under it.
 *
 * A tap on a node goes into it ([onOpen]). The children unfold from the centre once, the first
 * time the view is shown (not again on the way back, nor with animations off). Every node is also
 * a TalkBack button with its name, how many recordings and when, in order of importance.
 */
@Composable
internal fun MindMapCanvas(
    scene: MapScene,
    centre: CanvasNode,
    children: List<CanvasNode>,
    onOpen: (MapTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = Grille.colors
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val styles = DrawingStyles(
        centre = Grille.type.label,
        child = Grille.type.caption,
        number = Grille.type.listNumber
    )
    val reducedMotion = rememberReducedMotion()
    val currentOpen by rememberUpdatedState(onOpen)

    // Pinch and pan, kept per drawing: new content starts centred again.
    val contentKey = remember(scene) { contentKey(scene) }
    var scale by rememberSaveable(contentKey) { mutableFloatStateOf(1f) }
    var panX by rememberSaveable(contentKey) { mutableFloatStateOf(0f) }
    var panY by rememberSaveable(contentKey) { mutableFloatStateOf(0f) }
    val transform = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
        panX += panChange.x
        panY += panChange.y
    }

    // The children unfold from the centre, once per view.
    var unfolded by rememberSaveable { mutableStateOf(false) }
    val unfold = remember { Animatable(if (unfolded || reducedMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (unfold.value < 1f) unfold.animateTo(1f, tween(UNFOLD_MS, easing = FastOutSlowInEasing))
        unfolded = true
    }

    val hits = remember { MapHits() }

    BoxWithConstraints(
        modifier = modifier
            .clipToBounds()
            .semantics { isTraversalGroup = true }
    ) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val drawing = remember(scene, centre, children, width, height, styles, density) {
            if (scene.twoLevel) {
                outlineDrawing(measurer, centre, children, styles, density, width, height)
            } else {
                ringDrawing(measurer, scene, centre, children, styles, density, width, height)
            }
        }

        // Where an item is drawn now, at the zoom and the unfold.
        fun originOf(): Offset = Offset(width / 2 + panX, height / 2 + drawing.originShift + panY)
        fun anchorOf(origin: Offset, item: Item): Offset = origin + item.base * (scale * unfold.value)

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .transformable(transform)
                .pointerInput(Unit) {
                    detectTapGestures { position ->
                        hits.find(position, (TOUCH / 2).toPx())?.let { currentOpen(it) }
                    }
                }
        ) {
            val origin = originOf()
            val anchors = drawing.items.map { anchorOf(origin, it) }
            drawMap(drawing, origin, anchors, unfold.value, colors, hits)
        }

        // TalkBack's buttons, one per node, over the drawing and in order of importance. They take
        // no taps of their own: the canvas finds the node under a finger, label included.
        val touch = with(density) { TOUCH.roundToPx() }
        val all = remember(drawing) {
            listOf(drawing.centre.node to null) + drawing.readingOrder.map { drawing.items[it].node to drawing.items[it] }
        }
        all.forEachIndexed { order, (node, item) ->
            Box(
                Modifier
                    .offset {
                        val origin = originOf()
                        val at = if (item == null) origin else anchorOf(origin, item)
                        IntOffset(at.x.roundToInt() - touch / 2, at.y.roundToInt() - touch / 2)
                    }
                    .size(TOUCH)
                    .semantics {
                        contentDescription = node.description
                        traversalIndex = order.toFloat()
                        node.node.target?.let { target ->
                            role = Role.Button
                            onClick {
                                currentOpen(target)
                                true
                            }
                        }
                    }
            )
        }
    }
}

/** The text styles of a drawing. */
@Immutable
private data class DrawingStyles(val centre: TextStyle, val child: TextStyle, val number: TextStyle)

/** How an item is drawn. */
private enum class Shape {
    /** A filled dot sized by its recordings: a theme or a topic on a ring. */
    DOT,

    /** A ring: a recording. */
    RING,

    /** A small dot: a point. */
    POINT,

    /** A pill around the text: a topic in a recording's own map. */
    PILL
}

/**
 * One thing drawn around the centre.
 *
 * @property base Where it sits at zoom 1, fully unfolded, from the centre of the focus
 * @property parent The item its line comes from, or -1 for the focus
 * @property angle Its direction from the focus on a ring, which puts its label on the far side;
 *   null in a recording's own map, where a point's text sits right of its dot
 * @property pill The size of its pill, for [Shape.PILL]
 */
private class Item(
    val node: CanvasNode,
    val base: Offset,
    val parent: Int,
    val shape: Shape,
    val text: TextLayoutResult,
    val angle: Double?,
    val pill: Size? = null
)

/**
 * A view's drawing, measured and placed.
 *
 * @property items The children and points in order of importance, which is the order their labels
 *   claim room and TalkBack reads them
 * @property originShift How far below the canvas's middle the focus sits
 * @property pills How many of the first items are pills, always drawn whole
 * @property readingOrder The items in the order TalkBack reads them: a topic, then its points
 */
private class Drawing(
    val centre: CentreText,
    val items: List<Item>,
    val originShift: Float,
    val pills: Int,
    val readingOrder: List<Int> = items.indices.toList()
)

/** The focus's words, measured: its label in a pill, or "You" under the number in a circle. */
private class CentreText(
    val node: CanvasNode,
    val label: TextLayoutResult,
    val number: TextLayoutResult?,
    val width: Float,
    val height: Float
) {
    fun box(origin: Offset) =
        MapBox(origin.x - width / 2, origin.y - height / 2, origin.x + width / 2, origin.y + height / 2)
}

private fun measureCentre(measurer: TextMeasurer, centre: CanvasNode, styles: DrawingStyles, density: Density): CentreText =
    with(density) {
        val label = measurer.measure(
            text = AnnotatedString(MindMapRules.cutLabel(centre.label)),
            style = styles.centre.copy(textAlign = TextAlign.Center),
            overflow = TextOverflow.Ellipsis,
            maxLines = 2,
            constraints = Constraints(maxWidth = CENTRE_TEXT_WIDTH.roundToPx())
        )
        val number = centre.number?.let { measurer.measure(text = dotoNumberText(it), style = styles.number) }
        if (number != null) {
            val diameter = YOU_DIAMETER.toPx()
            CentreText(centre, label, number, diameter, diameter)
        } else {
            CentreText(
                node = centre,
                label = label,
                number = null,
                width = label.size.width + 2 * PILL_PADDING_H.toPx(),
                height = label.size.height + 2 * PILL_PADDING_V.toPx()
            )
        }
    }

private fun measureLabel(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    align: TextAlign,
    maxWidth: Int
): TextLayoutResult = measurer.measure(
    text = AnnotatedString(MindMapRules.cutLabel(text)),
    style = style.copy(textAlign = align),
    overflow = TextOverflow.Ellipsis,
    maxLines = 2,
    constraints = Constraints(maxWidth = maxWidth.coerceAtLeast(1))
)

/**
 * Themes, topics and recordings on as few rings around the focus as hold them with room for their
 * labels, each label above or below its node.
 */
private fun ringDrawing(
    measurer: TextMeasurer,
    scene: MapScene,
    centre: CanvasNode,
    children: List<CanvasNode>,
    styles: DrawingStyles,
    density: Density,
    width: Float,
    height: Float
): Drawing = with(density) {
    val centreText = measureCentre(measurer, centre, styles, density)
    // Clear of the focus by half its width to the sides and half its height up and down: a theme's
    // wide pill needs more room beside it than above it.
    val clearanceX = (centreText.width / 2 + CENTRE_CLEARANCE.toPx()).toDouble()
    val clearanceY = (centreText.height / 2 + CENTRE_CLEARANCE.toPx()).toDouble()
    val labelMargin = LABEL_MARGIN.toPx().toDouble()
    val minGap = MIN_RING_GAP.toPx().toDouble()
    val radiiFor = { rings: Int ->
        MindMapLayout.ringRadii(
            widthPx = width.toDouble(),
            heightPx = height.toDouble(),
            rings = rings,
            clearanceXPx = clearanceX,
            clearanceYPx = clearanceY,
            labelMarginPx = labelMargin,
            minGapPx = minGap,
            maxFirstPx = MAX_FIRST_RING.toPx().toDouble()
        )
    }
    val minArc = MIN_ARC.toPx().toDouble()
    // Neighbours a finger apart, also where an ellipse bends most.
    val minChord = TOUCH.toPx().toDouble()
    val maxRings = MindMapLayout.ringsThatFit(width.toDouble(), height.toDouble(), clearanceX, clearanceY, labelMargin, minGap)
    val ringCount = MindMapLayout.ringsNeeded(children.size, minArc, minChord, maxRings, radiiFor)
    val radii = radiiFor(ringCount)
    val spots = MindMapLayout.rings(children.size, scene.leadCount, ringCount, radii, minArc, minChord)
    val items = children.mapIndexed { index, child ->
        val spot = spots[index]
        Item(
            node = child,
            base = Offset(spot.x(radii).toFloat(), spot.y(radii).toFloat()),
            parent = -1,
            shape = if (child.node.kind == MapNodeKind.RECORDING) Shape.RING else Shape.DOT,
            text = measureLabel(measurer, child.label, styles.child, TextAlign.Center, CHILD_TEXT_WIDTH.roundToPx()),
            angle = spot.angle
        )
    }
    Drawing(centreText, items, originShift = 0f, pills = 0)
}

/**
 * A recording's own map, top down: the recording's pill near the top, its topics' pills in a row
 * below it, and each topic's points listed under its pill. Centred in the canvas when it fits,
 * else from the top, to be panned.
 */
private fun outlineDrawing(
    measurer: TextMeasurer,
    centre: CanvasNode,
    children: List<CanvasNode>,
    styles: DrawingStyles,
    density: Density,
    width: Float,
    height: Float
): Drawing = with(density) {
    val centreText = measureCentre(measurer, centre, styles, density)
    val metrics = OutlineMetrics(
        levelGap = OUTLINE_LEVEL_GAP.toPx(),
        inset = OUTLINE_INSET.toPx(),
        spineX = OUTLINE_SPINE_X.toPx(),
        dotGap = OUTLINE_DOT_GAP.toPx(),
        firstGap = OUTLINE_FIRST_GAP.toPx(),
        rowGap = OUTLINE_ROW_GAP.toPx()
    )
    val pillTextWidth = (MindMapLayout.outlineColumnWidth(width, children.size, metrics) - 2 * TOPIC_PADDING_H.toPx()).roundToInt()
    val pointWidth = MindMapLayout.outlinePointWidth(width, children.size, metrics).roundToInt()
    val topicTexts = children.map { measureLabel(measurer, it.label, styles.child, TextAlign.Center, pillTextWidth) }
    val pointTexts = children.map { topic ->
        topic.children.map { point -> measureLabel(measurer, point.label, styles.child, TextAlign.Start, pointWidth) }
    }
    val pillSizes = topicTexts.map { text ->
        BoxSize(text.size.width + 2 * TOPIC_PADDING_H.toPx(), text.size.height + 2 * TOPIC_PADDING_V.toPx())
    }
    val outline = MindMapLayout.outline(
        width = width,
        centre = BoxSize(centreText.width, centreText.height),
        topics = pillSizes,
        points = pointTexts.map { texts -> texts.map { BoxSize(it.size.width.toFloat(), it.size.height.toFloat()) } },
        metrics = metrics
    )
    val items = ArrayList<Item>()
    outline.topics.forEachIndexed { index, pill ->
        items += Item(
            node = children[index],
            base = Offset((pill.left + pill.right) / 2, (pill.top + pill.bottom) / 2),
            parent = -1,
            shape = Shape.PILL,
            text = topicTexts[index],
            angle = null,
            pill = Size(pill.right - pill.left, pill.bottom - pill.top)
        )
    }
    outline.points.forEachIndexed { topic, points ->
        points.forEachIndexed { index, point ->
            items += Item(
                node = children[topic].children[index],
                base = Offset(point.x, point.y),
                parent = topic,
                shape = Shape.POINT,
                text = pointTexts[topic][index],
                angle = null
            )
        }
    }
    // The drawing runs from the top of the recording's pill to the lowest point.
    val top = OUTLINE_TOP.toPx()
    val tall = centreText.height / 2 + outline.bottom
    val originY = if (tall + 2 * top <= height) (height - tall) / 2 + centreText.height / 2 else top + centreText.height / 2
    val firstPoint = outline.topics.size
    val readingOrder = ArrayList<Int>(items.size)
    var next = firstPoint
    outline.points.forEachIndexed { topic, points ->
        readingOrder += topic
        repeat(points.size) { readingOrder += next++ }
    }
    Drawing(centreText, items, originShift = originY - height / 2, pills = outline.topics.size, readingOrder = readingOrder)
}

/** A key of what a drawing shows, so a new filter or search starts the pinch over. */
private fun contentKey(scene: MapScene): String =
    scene.centre.key + ":" + scene.children.joinToString(",") { it.key }

/**
 * Draws [drawing] with its focus at [origin] and its items at [anchors]: the lines first, then the
 * focus, then the clearings under the labels that are drawn, the nodes, and the texts last, so
 * no line runs through a word and no clearing hides a node.
 */
private fun DrawScope.drawMap(
    drawing: Drawing,
    origin: Offset,
    anchors: List<Offset>,
    fade: Float,
    colors: GrilleColors,
    hits: MapHits
) {
    val items = drawing.items
    items.forEachIndexed { index, item ->
        val to = anchors[index]
        val from = when {
            item.parent < 0 -> origin
            // A point hangs from the line under its topic's pill.
            else -> Offset(to.x, anchors[item.parent].y + (items[item.parent].pill?.height ?: 0f) / 2)
        }
        drawLine(colors.unlit, from, to, strokeWidth = Stroke.HairlineWidth, alpha = fade)
    }
    drawCentre(drawing.centre, origin, colors)

    // Room is claimed in this order: the focus, every node (a label must not cover one), the
    // pills, then the labels by importance. All but the labels are always drawn.
    val centreBox = drawing.centre.box(origin)
    val boxes = ArrayList<MapBox>(2 * items.size + 1)
    boxes += centreBox
    items.forEachIndexed { index, item -> boxes += nodeBox(item, anchors[index]) }
    val firstLabel = boxes.size
    items.forEachIndexed { index, item -> boxes += boxOf(item, anchors[index]) }
    val visible = MindMapLayout.visibleLabels(boxes, keep = firstLabel + drawing.pills)

    hits.clear()
    hits.add(drawing.centre.node.node.target, origin, centreBox)
    items.forEachIndexed { index, item ->
        if (visible[firstLabel + index] && item.shape != Shape.PILL) {
            val box = boxes[firstLabel + index]
            drawRect(colors.void, Offset(box.left, box.top), Size(box.right - box.left, box.bottom - box.top), alpha = fade)
        }
    }
    items.forEachIndexed { index, item ->
        val at = anchors[index]
        val tint = lerp(colors.ash, colors.ink, item.node.node.recency)
        drawItem(item, at, tint, colors, fade)
        val shown = visible[firstLabel + index]
        val box = boxes[firstLabel + index]
        if (shown) {
            val text = item.text
            val left = if (item.shape == Shape.PILL) at.x - text.size.width / 2f else box.left
            val top = if (item.shape == Shape.PILL) at.y - text.size.height / 2f else box.top
            drawText(text, color = if (item.shape == Shape.PILL) colors.ink else tint, topLeft = Offset(left, top), alpha = fade)
        }
        hits.add(item.node.node.target, at, box.takeIf { shown })
    }
}

/** The room [item]'s node takes at [at]: its dot with its pin ring, or its pill. */
private fun DrawScope.nodeBox(item: Item, at: Offset): MapBox {
    item.pill?.let { pill ->
        return MapBox(at.x - pill.width / 2, at.y - pill.height / 2, at.x + pill.width / 2, at.y + pill.height / 2)
    }
    val radius = dotRadius(item.node.node) + if (item.node.node.pinned) PIN_GAP.toPx() else 0f
    return MapBox(at.x - radius, at.y - radius, at.x + radius, at.y + radius)
}

/** Where [item]'s label, or its pill, goes when the item is at [at]. */
private fun DrawScope.boxOf(item: Item, at: Offset): MapBox {
    val text = item.text
    item.pill?.let { pill ->
        return MapBox(at.x - pill.width / 2, at.y - pill.height / 2, at.x + pill.width / 2, at.y + pill.height / 2)
    }
    val angle = item.angle
    if (angle != null) {
        return MindMapLayout.labelBox(
            angle = angle,
            x = at.x,
            y = at.y,
            gap = dotRadius(item.node.node) + LABEL_GAP.toPx(),
            width = text.size.width.toFloat(),
            height = text.size.height.toFloat()
        )
    }
    // A point of a recording's own map: its text right of its dot, centred on it.
    val left = at.x + OUTLINE_DOT_GAP.toPx()
    val top = at.y - text.size.height / 2f
    return MapBox(left, top, left + text.size.width, top + text.size.height)
}

/** The radius of a node's dot: themes and topics grow with their recordings. */
private fun DrawScope.dotRadius(node: MapNode): Float = when (node.kind) {
    MapNodeKind.THEME, MapNodeKind.TOPIC -> (DOT_MIN + (DOT_MAX - DOT_MIN) * node.weight).toPx()
    MapNodeKind.RECORDING -> RECORDING_RADIUS.toPx()
    MapNodeKind.POINT -> POINT_RADIUS.toPx()
    MapNodeKind.YOU -> 0f
}

/**
 * An item: a filled dot for a theme or topic, a ring for a recording, a small dot for a point, an
 * outlined pill for a topic of a recording's own map. A pinned theme has a second ring around it.
 */
private fun DrawScope.drawItem(item: Item, at: Offset, tint: Color, colors: GrilleColors, alpha: Float) {
    val node = item.node.node
    when (item.shape) {
        Shape.PILL -> {
            val pill = item.pill ?: return
            val topLeft = Offset(at.x - pill.width / 2, at.y - pill.height / 2)
            val corner = CornerRadius(pill.height / 2)
            // Void inside, so the lines under the pill don't show through it.
            drawRoundRect(colors.void, topLeft, pill, corner, alpha = alpha)
            drawRoundRect(tint, topLeft, pill, corner, alpha = alpha, style = Stroke(Stroke.HairlineWidth))
        }
        Shape.RING -> {
            val radius = dotRadius(node)
            drawCircle(colors.void, radius, at, alpha = alpha)
            drawCircle(tint, radius, at, alpha = alpha, style = Stroke(RING_STROKE.toPx()))
        }
        Shape.DOT, Shape.POINT -> drawCircle(tint, dotRadius(node), at, alpha = alpha)
    }
    if (node.pinned) {
        drawCircle(tint, dotRadius(node) + PIN_GAP.toPx(), at, alpha = alpha, style = Stroke(Stroke.HairlineWidth))
    }
}

/** The focus: a pill around its label, or for "You" a circle with the number and the word. */
private fun DrawScope.drawCentre(text: CentreText, origin: Offset, colors: GrilleColors) {
    val outline = Stroke(CENTRE_STROKE.toPx())
    val number = text.number
    if (number != null) {
        val radius = text.width / 2
        drawCircle(colors.void, radius, origin)
        drawCircle(colors.ink, radius, origin, style = outline)
        var top = origin.y - (number.size.height + text.label.size.height) / 2f
        drawText(number, color = colors.ink, topLeft = Offset(origin.x - number.size.width / 2f, top))
        top += number.size.height
        drawText(text.label, color = colors.ash, topLeft = Offset(origin.x - text.label.size.width / 2f, top))
    } else {
        val tint = lerp(colors.ash, colors.ink, text.node.node.recency)
        val topLeft = Offset(origin.x - text.width / 2, origin.y - text.height / 2)
        val size = Size(text.width, text.height)
        val corner = CornerRadius(text.height / 2)
        drawRoundRect(colors.void, topLeft, size, corner)
        drawRoundRect(tint, topLeft, size, corner, style = outline)
        drawText(
            text.label,
            color = colors.ink,
            topLeft = Offset(origin.x - text.label.size.width / 2f, origin.y - text.label.size.height / 2f)
        )
    }
}

/**
 * Where the nodes were last drawn, so a tap can find the one under the finger: the nearest dot
 * within reach, else the drawn label or pill it falls on. Written while drawing, read on a tap; it
 * holds no state the drawing depends on.
 */
private class MapHits {
    private val targets = ArrayList<MapTarget?>()
    private val dots = ArrayList<Offset>()
    private val boxes = ArrayList<MapBox?>()

    fun clear() {
        targets.clear()
        dots.clear()
        boxes.clear()
    }

    fun add(target: MapTarget?, dot: Offset, box: MapBox?) {
        targets += target
        dots += dot
        boxes += box
    }

    fun find(position: Offset, reach: Float): MapTarget? {
        var best = -1
        var bestDistance = reach
        dots.forEachIndexed { index, dot ->
            val distance = hypot(dot.x - position.x, dot.y - position.y)
            if (distance <= bestDistance) {
                best = index
                bestDistance = distance
            }
        }
        if (best >= 0) return targets[best]
        // The last drawn lies on top.
        for (index in boxes.indices.reversed()) {
            if (boxes[index]?.contains(position.x, position.y) == true) return targets[index]
        }
        return null
    }
}

/** How far the map zooms in and out. */
private const val MIN_ZOOM = 0.5f
private const val MAX_ZOOM = 4f

private const val UNFOLD_MS = 320

private val TOUCH = 48.dp
private val DOT_MIN = 5.dp
private val DOT_MAX = 12.dp
private val RECORDING_RADIUS = 6.dp
private val POINT_RADIUS = 3.5.dp
private val RING_STROKE = 1.5.dp
private val CENTRE_STROKE = 1.dp
private val PIN_GAP = 3.dp
private val LABEL_GAP = 6.dp
private val CENTRE_TEXT_WIDTH = 136.dp
private val CHILD_TEXT_WIDTH = 112.dp
private val PILL_PADDING_H = 14.dp
private val PILL_PADDING_V = 10.dp
private val YOU_DIAMETER = 72.dp

/** Ring 1 is at least this far past the focus's shape: a dot and its label's gap. */
private val CENTRE_CLEARANCE = 28.dp

/**
 * Room outside the outermost ring for its labels: half a label's width beside a node at 3 or 9
 * o'clock, a dot and a label's height above or below one at 12 or 6.
 */
private val LABEL_MARGIN = 60.dp
private val MIN_RING_GAP = 48.dp
private val MAX_FIRST_RING = 150.dp

/** Neighbours on a ring are at least this far apart: about a label's width, so labels rarely meet. */
private val MIN_ARC = 92.dp

// A recording's own map
private val OUTLINE_TOP = 16.dp
private val OUTLINE_LEVEL_GAP = 40.dp
private val OUTLINE_INSET = 8.dp
private val OUTLINE_SPINE_X = 12.dp
private val OUTLINE_DOT_GAP = 10.dp
private val OUTLINE_FIRST_GAP = 14.dp
private val OUTLINE_ROW_GAP = 10.dp
private val TOPIC_PADDING_H = 10.dp
private val TOPIC_PADDING_V = 6.dp
