package com.example.smartmarkerfrontend.data

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

enum class AppScreen { Plan, Plans, Pose, Settings }

enum class NodeStatus { Open, Surveyed, Projected, NotVisible }

enum class Units { Millimetres, Metres, Feet }

enum class MoveStep { Fine, Coarse }

enum class NodeMarkStyle { Dot, Crosshair, Chip }

enum class RunKind { Survey, Project }

data class MarkerNode(
    val id: String,
    val x: Float,
    val y: Float,
    val status: NodeStatus,
    val cornerInnerAngleRad: Float = 0f,
    val cornerOrientationRad: Float = 0f
) {
    fun toPacket(): PlanNode = PlanNode(
        id = id,
        eastM = x,
        northM = y,
        cornerInnerAngleRad = cornerInnerAngleRad,
        cornerOrientationRad = cornerOrientationRad
    )
}

data class Polyline(
    val points: List<Offset>,
    val closed: Boolean = false
)

data class CircleGeom(
    val center: Offset,
    val radius: Float
)

data class PlanGeometry(
    val bounds: Rect,
    val outlines: List<Polyline>,
    val walls: List<Polyline> = emptyList(),
    val openings: List<Polyline> = emptyList(),
    val circles: List<CircleGeom> = emptyList(),
    val dashed: List<Polyline> = emptyList(),
    val marker: Offset
) {
    val width: Float get() = bounds.width.coerceAtLeast(1f)
    val height: Float get() = bounds.height.coerceAtLeast(1f)
}

data class PlanFile(
    val name: String,
    val uploaded: String,
    val open: Boolean,
    val nodes: List<MarkerNode>,
    val geometry: PlanGeometry
)

data class Tag(
    val id: Int,
    val aligned: Boolean
)

data class RunProgress(
    val kind: RunKind,
    val ids: List<String>,
    val reported: List<String> = emptyList()
) {
    val done: Int get() = reported.size
    val pct: Int get() = if (ids.isEmpty()) 100 else ((done * 100) / ids.size).coerceIn(0, 100)
    val currentId: String? get() = ids.firstOrNull { it !in reported }
}

val EmptyPlanGeometry = PlanGeometry(
    bounds = Rect(0f, 0f, 10f, 8f),
    outlines = emptyList(),
    marker = Offset(5f, 4f)
)

data class UserProfile(
    val name: String = "D. Whitfield",
    val initials: String = "DW",
    val role: String = "Site lead",
    val email: String = "dw@harrowbuild.co.uk"
)

data class SmartMarkerState(
    val screen: AppScreen = AppScreen.Plans,
    val jobName: String = "Riverside Plot 14",
    val nodes: List<MarkerNode> = emptyList(),
    val geometry: PlanGeometry = EmptyPlanGeometry,
    val files: List<PlanFile> = emptyList(),
    val selectedIds: List<String> = emptyList(),
    val scale: Float = 50f,
    val pan: Offset = Offset.Zero,
    val canvasSize: Offset = Offset.Zero,
    val multiSelect: Boolean = false,
    val labels: Boolean = true,
    val grid: Boolean = true,
    val fovOn: Boolean = true,
    val posed: Boolean = false,
    val computing: Boolean = false,
    val hadPose: Boolean = false,
    val locking: Boolean = false,
    val tags: List<Tag> = listOf(Tag(1, false), Tag(2, false)),
    val curTag: Int = 1,
    val step: MoveStep = MoveStep.Coarse,
    val run: RunProgress? = null,
    val projecting: List<String>? = null,
    val az: Float = 12.4f,
    val el: Float = -3.15f,
    val link: Boolean = true,
    val deviceName: String? = null,
    val sessionActive: Boolean = false,
    val seeingTelemetry: Boolean = false,
    val connOpen: Boolean = false,
    val userOpen: Boolean = false,
    val units: Units = Units.Millimetres,
    val beam: Int = 70,
    val autoReconnect: Boolean = true,
    val laser: Boolean = false,
    val firmwareVersion: String? = null,
    val deviceYawRad: Float? = null,
    val importError: String? = null,
    val importing: Boolean = false,
    val isSurveying: Boolean = false,
    val nodeStyle: NodeMarkStyle = NodeMarkStyle.Dot,
    val showGestureHints: Boolean = true,
    val user: UserProfile = UserProfile()
) {
    val selectedNodes: List<MarkerNode>
        get() = nodes.filter { it.id in selectedIds }

    val laserOn: Boolean
        get() = laser || projecting != null

    val headingRad: Float
        get() = deviceYawRad ?: ((-124f + (az - 12.4f)) * PI / 180.0).toFloat()

    /** Plan metres → screen. +east is right, +north is up. */
    fun worldToScreen(east: Float, north: Float): Offset =
        worldToScreen(east, north, scale, pan)

    fun screenToWorld(pos: Offset): Offset = screenToWorld(pos, scale, pan)

    fun inView(node: MarkerNode): Boolean = inMarkerView(node, posed, headingRad)

    /** Overlay stays up while a survey job is open and the device is still working, or until every requested point has a result. */
    val showSurveyLoading: Boolean
        get() {
            val job = run ?: return false
            if (job.kind != RunKind.Survey) return false
            if (isSurveying) return true
            return job.reported.isNotEmpty() && job.reported.size < job.ids.size
        }
}

/**
 * Canvas heading for the wedge. Screen angle 0 is +east and positive is anticlockwise,
 * so yaw 0 (due north) is π/2 and the wedge points up.
 */
fun wedgeScreenAngle(yawRad: Float): Float = (PI.toFloat() / 2f) - yawRad

/** Device is the plan origin. `geometry.marker` is not used. Bearing is clockwise from north. */
fun inMarkerView(
    node: MarkerNode,
    posed: Boolean,
    yawRad: Float,
    range: Float = DemoPlans.RANGE,
    halfFov: Float = DemoPlans.HALF_FOV
): Boolean {
    if (!posed) return false
    if (hypot(node.x.toDouble(), node.y.toDouble()) > range) return false
    val bearing = atan2(node.x.toDouble(), node.y.toDouble()).toFloat()
    var d = wrapTwoPi(bearing - yawRad)
    if (d > PI) d -= TWO_PI
    return abs(d) <= halfFov
}

const val TWO_PI = (2.0 * PI).toFloat()

fun wrapTwoPi(rad: Float): Float {
    var a = rad % TWO_PI
    if (a < 0f) a += TWO_PI
    return a
}

/** Screen x = pan.x + east * scale; screen y = pan.y - north * scale. */
fun worldToScreen(east: Float, north: Float, scale: Float, pan: Offset): Offset =
    Offset(pan.x + east * scale, pan.y - north * scale)

fun screenToWorld(pos: Offset, scale: Float, pan: Offset): Offset =
    Offset((pos.x - pan.x) / scale, (pan.y - pos.y) / scale)

fun panForWorldAtScreen(east: Float, north: Float, scale: Float, screen: Offset): Offset =
    Offset(screen.x - east * scale, screen.y + north * scale)

fun fitScale(canvasW: Float, canvasH: Float, geom: PlanGeometry): Pair<Float, Offset> {
    val s = minOf(
        (canvasW - 200f) / geom.width,
        (canvasH - 200f) / geom.height
    ).coerceIn(2f, 6000f)
    val pan = Offset(
        (canvasW - geom.width * s) / 2f - geom.bounds.left * s,
        (canvasH - geom.height * s) / 2f + geom.bounds.bottom * s
    )
    return s to pan
}

private const val CORNER_VERTEX_M = 0.04f
private const val COLLINEAR_RAD = 0.08f

fun MarkerNode.withPlanCorners(geom: PlanGeometry): MarkerNode {
    val (inner, orient) = planCornerAngles(x, y, geom)
    return copy(cornerInnerAngleRad = inner, cornerOrientationRad = orient)
}

/**
 * Interior/acute angle at a plan vertex, and orientation of the first hand:
 * clockwise from north to the incident wall with the smaller bearing.
 */
fun planCornerAngles(east: Float, north: Float, geom: PlanGeometry): Pair<Float, Float> {
    val dirs = incidentUnitDirections(east, north, geom)
    if (dirs.size < 2) return 0f to 0f
    var bestInner = Float.POSITIVE_INFINITY
    var bestPair: Pair<Offset, Offset>? = null
    for (i in dirs.indices) {
        for (j in i + 1 until dirs.size) {
            val inner = acuteAngleBetween(dirs[i], dirs[j])
            if (inner < COLLINEAR_RAD) continue
            if (inner < bestInner) {
                bestInner = inner
                bestPair = dirs[i] to dirs[j]
            }
        }
    }
    val pair = bestPair ?: return 0f to 0f
    val firstHand = minOf(bearingFromNorthCw(pair.first), bearingFromNorthCw(pair.second))
    return wrapTwoPi(bestInner) to wrapTwoPi(firstHand)
}

private fun incidentUnitDirections(east: Float, north: Float, geom: PlanGeometry): List<Offset> {
    val vertex = Offset(east, north)
    val raw = mutableListOf<Offset>()
    for (pl in geom.outlines + geom.walls) {
        val pts = pl.points
        if (pts.size < 2) continue
        val n = pts.size
        val count = if (pl.closed) n else n - 1
        for (i in 0 until count) {
            addIncidentFromSegment(vertex, pts[i], pts[(i + 1) % n], raw)
        }
    }
    return uniqueDirections(raw)
}

private fun addIncidentFromSegment(v: Offset, a: Offset, b: Offset, out: MutableList<Offset>) {
    val av = hypot(v.x - a.x, v.y - a.y)
    val bv = hypot(v.x - b.x, v.y - b.y)
    val ab = hypot(b.x - a.x, b.y - a.y).coerceAtLeast(0.001f)
    when {
        av < CORNER_VERTEX_M -> out += unitOffset(Offset(b.x - a.x, b.y - a.y))
        bv < CORNER_VERTEX_M -> out += unitOffset(Offset(a.x - b.x, a.y - b.y))
        else -> {
            val t = ((v.x - a.x) * (b.x - a.x) + (v.y - a.y) * (b.y - a.y)) / (ab * ab)
            if (t <= 0f || t >= 1f) return
            val px = a.x + t * (b.x - a.x)
            val py = a.y + t * (b.y - a.y)
            if (hypot(v.x - px, v.y - py) < CORNER_VERTEX_M) {
                val along = unitOffset(Offset(b.x - a.x, b.y - a.y))
                out += along
                out += Offset(-along.x, -along.y)
            }
        }
    }
}

private fun uniqueDirections(raw: List<Offset>): List<Offset> {
    val kept = mutableListOf<Offset>()
    for (d in raw) {
        if (hypot(d.x, d.y) < 1e-6f) continue
        if (kept.none { it.x * d.x + it.y * d.y > 0.98f }) kept += d
    }
    return kept
}

private fun unitOffset(v: Offset): Offset {
    val n = hypot(v.x, v.y).coerceAtLeast(1e-6f)
    return Offset(v.x / n, v.y / n)
}

private fun acuteAngleBetween(a: Offset, b: Offset): Float {
    val na = hypot(a.x, a.y)
    val nb = hypot(b.x, b.y)
    if (na < 1e-6f || nb < 1e-6f) return 0f
    val cos = ((a.x * b.x + a.y * b.y) / (na * nb)).coerceIn(-1f, 1f)
    val theta = acos(cos)
    return min(theta, PI.toFloat() - theta)
}

/** Bearing clockwise from +north to this east/north direction, [0, 2π). */
fun bearingFromNorthCw(dir: Offset): Float = wrapTwoPi(atan2(dir.x, dir.y))

/** Display conversion. `metres` is the stored plan coordinate. */
fun formatCoord(metres: Float, units: Units): String = when (units) {
    Units.Millimetres -> "%,.0f mm".format(metres * 1000f)
    Units.Metres -> "%.3f m".format(metres)
    Units.Feet -> "%.3f ft".format(metres / 0.3048f)
}

fun MoveStep.degrees(): Float = if (this == MoveStep.Fine) 0.05f else 0.5f
