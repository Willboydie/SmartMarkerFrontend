package com.example.smartmarkerfrontend.data

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

enum class AppScreen { Plan, Plans, Pose, Settings }

enum class NodeStatus { Open, Surveyed, Projected, Failed }

enum class Units { Millimetres, Metres, Feet }

enum class MoveStep { Fine, Coarse }

enum class NodeMarkStyle { Dot, Crosshair, Chip }

enum class RunKind { Survey, Project }

data class MarkerNode(
    val id: String,
    val x: Float,
    val y: Float,
    val status: NodeStatus
)

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

data class SurveyTag(
    val n: Int,
    val locked: Boolean
)

data class RunProgress(
    val kind: RunKind,
    val ids: List<String>,
    val pct: Int,
    val index: Int
)

data class UserProfile(
    val name: String = "D. Whitfield",
    val initials: String = "DW",
    val role: String = "Site lead",
    val email: String = "dw@harrowbuild.co.uk"
)

data class SmartMarkerState(
    val screen: AppScreen = AppScreen.Plan,
    val jobName: String = "Riverside Plot 14",
    val nodes: List<MarkerNode> = emptyList(),
    val geometry: PlanGeometry = DemoPlans.riverside,
    val files: List<PlanFile> = emptyList(),
    val selectedIds: List<String> = emptyList(),
    val scale: Float = 0.05f,
    val pan: Offset = Offset.Zero,
    val canvasSize: Offset = Offset.Zero,
    val multiSelect: Boolean = false,
    val labels: Boolean = true,
    val grid: Boolean = true,
    val fovOn: Boolean = true,
    val posed: Boolean = true,
    val computing: Boolean = false,
    val hadPose: Boolean = true,
    val locking: Boolean = false,
    val tags: List<SurveyTag> = listOf(SurveyTag(1, true), SurveyTag(2, true)),
    val curTag: Int = 1,
    val step: MoveStep = MoveStep.Coarse,
    val run: RunProgress? = null,
    val projecting: List<String>? = null,
    val az: Float = 12.4f,
    val el: Float = -3.15f,
    val link: Boolean = true,
    val connOpen: Boolean = false,
    val userOpen: Boolean = false,
    val units: Units = Units.Millimetres,
    val beam: Int = 70,
    val autoReconnect: Boolean = true,
    val laser: Boolean = false,
    val firmwareVersion: String? = null,
    val importError: String? = null,
    val nodeStyle: NodeMarkStyle = NodeMarkStyle.Dot,
    val showGestureHints: Boolean = true,
    val user: UserProfile = UserProfile()
) {
    val selectedNodes: List<MarkerNode>
        get() = nodes.filter { it.id in selectedIds }

    val laserOn: Boolean
        get() = laser || projecting != null

    val headingRad: Float
        get() = ((-124f + (az - 12.4f)) * PI / 180.0).toFloat()

    fun worldToScreen(x: Float, y: Float): Offset = Offset(pan.x + x * scale, pan.y + y * scale)

    fun inView(node: MarkerNode): Boolean = inMarkerView(node, posed, headingRad, geometry.marker)
}

fun inMarkerView(
    node: MarkerNode,
    posed: Boolean,
    headingRad: Float,
    marker: Offset,
    range: Float = DemoPlans.RANGE,
    halfFov: Float = DemoPlans.HALF_FOV
): Boolean {
    if (!posed) return false
    if (hypot((node.x - marker.x).toDouble(), (node.y - marker.y).toDouble()) > range) return false
    var d = atan2(node.y - marker.y, node.x - marker.x) - headingRad
    while (d > PI) d -= (2.0 * PI).toFloat()
    while (d < -PI) d += (2.0 * PI).toFloat()
    return abs(d) <= halfFov
}

fun fitScale(canvasW: Float, canvasH: Float, geom: PlanGeometry): Pair<Float, Offset> {
    val s = minOf(
        (canvasW - 200f) / geom.width,
        (canvasH - 200f) / geom.height
    ).coerceIn(0.002f, 6f)
    val pan = Offset(
        (canvasW - geom.width * s) / 2f - geom.bounds.left * s,
        (canvasH - geom.height * s) / 2f - geom.bounds.top * s
    )
    return s to pan
}

fun formatCoord(mm: Float, units: Units): String = when (units) {
    Units.Millimetres -> "%,.0f mm".format(mm)
    Units.Metres -> "%.3f m".format(mm / 1000f)
    Units.Feet -> "%.3f ft".format(mm / 304.8f)
}

fun MoveStep.degrees(): Float = if (this == MoveStep.Fine) 0.05f else 0.5f
