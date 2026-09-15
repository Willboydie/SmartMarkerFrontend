package com.example.smartmarkerfrontend.data

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

object DemoPlans {
    const val RANGE = 13000f
    val HALF_FOV = Math.toRadians(35.0).toFloat()

    private fun n(id: String, x: Float, y: Float, st: NodeStatus) = MarkerNode(id, x, y, st)

    val riversideGeometry = PlanGeometry(
        bounds = Rect(0f, 0f, 11800f, 9600f),
        outlines = listOf(
            Polyline(
                listOf(
                    Offset(0f, 0f),
                    Offset(11800f, 0f),
                    Offset(11800f, 7400f),
                    Offset(7200f, 7400f),
                    Offset(7200f, 9600f),
                    Offset(0f, 9600f)
                ),
                closed = true
            )
        ),
        walls = listOf(
            Polyline(listOf(Offset(4200f, 0f), Offset(4200f, 5200f), Offset(11800f, 5200f))),
            Polyline(listOf(Offset(0f, 5200f), Offset(4200f, 5200f))),
            Polyline(listOf(Offset(7200f, 7400f), Offset(7200f, 5200f)))
        ),
        openings = listOf(
            Polyline(listOf(Offset(0f, 900f), Offset(0f, 2600f))),
            Polyline(listOf(Offset(0f, 6200f), Offset(0f, 7900f))),
            Polyline(listOf(Offset(4200f, 1800f), Offset(4200f, 3300f)))
        ),
        circles = listOf(
            CircleGeom(Offset(9500f, 6300f), 300f),
            CircleGeom(Offset(2100f, 7600f), 300f)
        ),
        dashed = listOf(
            Polyline(
                listOf(
                    Offset(600f, 600f),
                    Offset(3600f, 600f),
                    Offset(3600f, 4600f),
                    Offset(600f, 4600f)
                ),
                closed = true
            )
        ),
        marker = Offset(8300f, 8900f)
    )

    val riverside: PlanGeometry get() = riversideGeometry

    fun revCNodes(): List<MarkerNode> = listOf(
        n("V01", 0f, 0f, NodeStatus.Projected),
        n("V02", 11800f, 0f, NodeStatus.Surveyed),
        n("V03", 11800f, 7400f, NodeStatus.Surveyed),
        n("V04", 7200f, 7400f, NodeStatus.Open),
        n("V05", 7200f, 9600f, NodeStatus.Open),
        n("V06", 0f, 9600f, NodeStatus.Open),
        n("V07", 4200f, 0f, NodeStatus.NotVisible),
        n("V08", 4200f, 5200f, NodeStatus.Open),
        n("V09", 11800f, 5200f, NodeStatus.Open),
        n("V10", 0f, 5200f, NodeStatus.Projected),
        n("V11", 9500f, 6300f, NodeStatus.Open),
        n("V12", 2100f, 7600f, NodeStatus.Open)
    ).map { it.withPlanCorners(riversideGeometry) }
}
