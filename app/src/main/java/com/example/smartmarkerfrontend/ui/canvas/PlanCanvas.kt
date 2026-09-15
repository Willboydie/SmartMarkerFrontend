package com.example.smartmarkerfrontend.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.example.smartmarkerfrontend.data.NodeMarkStyle
import com.example.smartmarkerfrontend.data.NodeStatus
import com.example.smartmarkerfrontend.data.SmartMarkerState
import com.example.smartmarkerfrontend.data.panForWorldAtScreen
import com.example.smartmarkerfrontend.data.screenToWorld
import com.example.smartmarkerfrontend.ui.theme.SmColors
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private class GestureHolder {
    var scale: Float = 1f
    var pan: Offset = Offset.Zero
    var locked: Boolean = false
}

private data class PinchStart(
    val dist: Float,
    val mid: Offset,
    val scale: Float,
    val pan: Offset
)

@Composable
fun PlanCanvas(
    state: SmartMarkerState,
    pulse: Float,
    shimmerR: Float,
    shimmerAlpha: Float,
    onPan: (Offset) -> Unit,
    onPinch: (Float, Offset) -> Unit,
    onTap: (Offset) -> Unit,
    onGestureStart: () -> Unit,
    modifier: Modifier = Modifier
) {
    val holder = remember { GestureHolder() }
    holder.scale = state.scale
    holder.pan = state.pan
    holder.locked = state.run != null || state.isSurveying

    val panCb by rememberUpdatedState(onPan)
    val pinchCb by rememberUpdatedState(onPinch)
    val tapCb by rememberUpdatedState(onTap)
    val startCb by rememberUpdatedState(onGestureStart)
    val density = LocalDensity.current
    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
    }
    labelPaint.textSize = with(density) { 11.5.sp.toPx() }

    Canvas(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val active = mutableMapOf<Long, Offset>()
                var pinch: PinchStart? = null
                var dragged = false
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        if (holder.locked) continue
                        var tapAt: Offset? = null
                        event.changes.forEach { change ->
                            val id = change.id.value
                            val down = change.pressed
                            val was = change.previousPressed
                            when {
                                down && !was -> {
                                    if (active.isEmpty()) {
                                        dragged = false
                                        startCb()
                                    }
                                    active[id] = change.position
                                    if (active.size == 2) {
                                        val pts = active.values.toList()
                                        pinch = PinchStart(
                                            dist = (pts[0] - pts[1]).getDistance().coerceAtLeast(1f),
                                            mid = Offset((pts[0].x + pts[1].x) / 2f, (pts[0].y + pts[1].y) / 2f),
                                            scale = holder.scale,
                                            pan = holder.pan
                                        )
                                    }
                                    change.consume()
                                }
                                down && was -> {
                                    val prev = active[id] ?: change.previousPosition
                                    val cur = change.position
                                    active[id] = cur
                                    if (active.size == 1) {
                                        val d = cur - prev
                                        if (d.getDistance() > 2.5f) dragged = true
                                        panCb(d)
                                    } else if (active.size >= 2) {
                                        val ps = pinch
                                        val pts = active.values.toList()
                                        if (ps != null) {
                                            val dist = (pts[0] - pts[1]).getDistance()
                                            val k = (ps.scale * (dist / ps.dist)).coerceIn(0.01f, 8f)
                                            val m = Offset((pts[0].x + pts[1].x) / 2f, (pts[0].y + pts[1].y) / 2f)
                                            val world = screenToWorld(ps.mid, ps.scale, ps.pan)
                                            dragged = true
                                            pinchCb(k, panForWorldAtScreen(world.x, world.y, k, m))
                                        }
                                    }
                                    change.consume()
                                }
                                !down && was -> {
                                    if (!dragged && active.size <= 1) tapAt = change.position
                                    active.remove(id)
                                    if (active.size < 2) pinch = null
                                    change.consume()
                                }
                            }
                        }
                        tapAt?.let(tapCb)
                    }
                }
            }
    ) {
        drawRect(SmColors.bg)
        if (state.grid) drawGrid(state)
        // Draw the plan in the same screen space as nodes/FOV (east right, north up).
        // DrawTransform.scale() defaults to the canvas centre as pivot, which made
        // the walls drift toward the bottom-right except near 1:1 world-to-pixel.
        drawPlan(state)
        if (state.fovOn && state.posed) drawFov(state)
        drawNodes(state, pulse, shimmerR, shimmerAlpha)
        if (state.labels) drawLabels(state, labelPaint)
    }
}

private fun DrawScope.drawGrid(state: SmartMarkerState) {
    val gp = max(14f, 1000f * state.scale)
    val x0 = state.pan.x % gp
    val y0 = state.pan.y % gp
    val color = SmColors.ink.copy(alpha = 0.07f)
    var x = x0
    while (x < size.width) {
        drawLine(color, Offset(x, 0f), Offset(x, size.height), 1f)
        x += gp
    }
    var y = y0
    while (y < size.height) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), 1f)
        y += gp
    }
}

private fun DrawScope.drawPlan(state: SmartMarkerState) {
    val geom = state.geometry
    val hair = Stroke(width = 2.5f, cap = StrokeCap.Butt)
    val wall = Stroke(width = 1.5f, cap = StrokeCap.Butt)
    val open = Stroke(width = 6f, cap = StrokeCap.Butt)
    val dash = Stroke(
        width = 1f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))
    )
    geom.outlines.forEach { pl ->
        drawPath(pl.toScreenPath(state), SmColors.ink, style = hair)
    }
    geom.walls.forEach { pl ->
        drawPath(pl.toScreenPath(state), SmColors.ink.copy(alpha = 0.65f), style = wall)
    }
    geom.openings.forEach { pl ->
        drawPath(pl.toScreenPath(state), SmColors.bg, style = open)
    }
    geom.circles.forEach { c ->
        drawCircle(
            color = SmColors.ink.copy(alpha = 0.55f),
            radius = c.radius * state.scale,
            center = state.worldToScreen(c.center.x, c.center.y),
            style = wall
        )
    }
    geom.dashed.forEach { pl ->
        drawPath(pl.toScreenPath(state), SmColors.accent.copy(alpha = 0.28f), style = dash)
    }
}

private fun com.example.smartmarkerfrontend.data.Polyline.toScreenPath(state: SmartMarkerState): Path =
    Path().also { path ->
        val pts = points
        if (pts.isEmpty()) return@also
        val first = state.worldToScreen(pts.first().x, pts.first().y)
        path.moveTo(first.x, first.y)
        for (i in 1 until pts.size) {
            val p = state.worldToScreen(pts[i].x, pts[i].y)
            path.lineTo(p.x, p.y)
        }
        if (closed) path.close()
    }

private const val MARKER_TIP_PX = 64f
private const val MARKER_BASE_PX = 36f

private fun DrawScope.drawFov(state: SmartMarkerState) {
    val origin = state.worldToScreen(state.geometry.marker.x, state.geometry.marker.y)
    val fr = com.example.smartmarkerfrontend.data.DemoPlans.RANGE * state.scale
    val head = state.headingRad
    val half = com.example.smartmarkerfrontend.data.DemoPlans.HALF_FOV
    fun pt(from: Offset, a: Float, r: Float) = Offset(from.x + r * cos(a), from.y - r * sin(a))
    val tip = pt(origin, head, MARKER_TIP_PX)
    val radius = fr.coerceAtLeast(1f)
    val wedge = Path().apply {
        moveTo(tip.x, tip.y)
        val a = pt(tip, head - half, radius)
        lineTo(a.x, a.y)
        arcTo(
            Rect(tip.x - radius, tip.y - radius, tip.x + radius, tip.y + radius),
            Math.toDegrees((-(head - half)).toDouble()).toFloat(),
            Math.toDegrees((-2f * half).toDouble()).toFloat(),
            false
        )
        close()
    }
    val fade = Brush.radialGradient(
        0f to SmColors.accent.copy(alpha = 0.42f),
        0.45f to SmColors.accent.copy(alpha = 0.18f),
        1f to Color.Transparent,
        center = tip,
        radius = radius
    )
    drawPath(wedge, fade)
    val pointer = Path().apply {
        val spread = 0.45f
        val l = pt(origin, head + spread, MARKER_BASE_PX)
        val r = pt(origin, head - spread, MARKER_BASE_PX)
        moveTo(tip.x, tip.y)
        lineTo(l.x, l.y)
        lineTo(r.x, r.y)
        close()
    }
    drawPath(pointer, SmColors.accent)
    drawPath(pointer, SmColors.ink, style = Stroke(1.8f))
}

private data class NodePaint(val fill: Color, val stroke: Color)

private fun paintFor(status: NodeStatus): NodePaint = when (status) {
    NodeStatus.Open -> NodePaint(SmColors.bg, SmColors.ink)
    NodeStatus.Surveyed -> NodePaint(SmColors.accent400, SmColors.accent700)
    NodeStatus.Projected -> NodePaint(SmColors.accent, SmColors.accent900)
    NodeStatus.NotVisible -> NodePaint(SmColors.bg, SmColors.bad)
}

private fun DrawScope.drawNodes(
    state: SmartMarkerState,
    pulse: Float,
    shimmerR: Float,
    shimmerAlpha: Float
) {
    val style = state.nodeStyle
    val activeId = state.run?.currentId
    state.nodes.forEach { n ->
        val p = state.worldToScreen(n.x, n.y)
        val vis = state.inView(n)
        val sel = n.id in state.selectedIds
        val paint = paintFor(n.status)
        val alpha = if (vis) 1f else 0.38f
        if (sel) {
            drawRect(
                color = SmColors.accent,
                topLeft = Offset(p.x - 19.5f, p.y - 19.5f),
                size = Size(39f, 39f),
                style = Stroke(
                    width = 1.4f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 3f))
                ),
                alpha = alpha
            )
        }
        if (style == NodeMarkStyle.Chip) {
            drawRect(
                color = paint.fill,
                topLeft = Offset(p.x - 25.5f, p.y - 13.5f),
                size = Size(51f, 27f),
                alpha = alpha
            )
            drawRect(
                color = paint.stroke,
                topLeft = Offset(p.x - 25.5f, p.y - 13.5f),
                size = Size(51f, 27f),
                style = Stroke(1.4f),
                alpha = alpha
            )
        }
        if (style != NodeMarkStyle.Chip) {
            val r = if (style == NodeMarkStyle.Dot) {
                if (sel) 13.5f else 10.5f
            } else 9.75f
            drawCircle(paint.fill, r, p, alpha = alpha)
            drawCircle(paint.stroke, r, p, alpha = alpha, style = Stroke(1.8f))
        }
        if (style == NodeMarkStyle.Crosshair) {
            drawLine(paint.stroke, Offset(p.x - 19.5f, p.y), Offset(p.x - 9f, p.y), 1.4f, alpha = alpha)
            drawLine(paint.stroke, Offset(p.x + 9f, p.y), Offset(p.x + 19.5f, p.y), 1.4f, alpha = alpha)
            drawLine(paint.stroke, Offset(p.x, p.y - 19.5f), Offset(p.x, p.y - 9f), 1.4f, alpha = alpha)
            drawLine(paint.stroke, Offset(p.x, p.y + 9f), Offset(p.x, p.y + 19.5f), 1.4f, alpha = alpha)
        }
        if (n.status == NodeStatus.NotVisible) {
            drawLine(SmColors.bad, Offset(p.x - 9f, p.y - 9f), Offset(p.x + 9f, p.y + 9f), 2f, alpha = alpha)
        }
        if (state.projecting != null && n.id in state.projecting) {
            drawCircle(SmColors.accent.copy(alpha = shimmerAlpha), shimmerR, p, style = Stroke(2f))
            drawCircle(SmColors.accent.copy(alpha = shimmerAlpha * 0.7f), 21f, p, style = Stroke(1f))
        }
        if (n.id == activeId) {
            drawCircle(
                SmColors.accent.copy(alpha = 0.55f * (1f - pulse)),
                18f * (1f + pulse * 1.1f),
                p,
                style = Stroke(2f)
            )
        }
    }
}

private fun DrawScope.drawLabels(state: SmartMarkerState, paint: Paint) {
    val native = drawContext.canvas.nativeCanvas
    state.nodes.forEach { n ->
        val p = state.worldToScreen(n.x, n.y)
        val sel = n.id in state.selectedIds
        paint.color = if (sel) android.graphics.Color.parseColor("#10652F") else android.graphics.Color.parseColor("#5F6360")
        native.drawText(n.id, p.x + 16f, p.y - 12f, paint)
    }
}
