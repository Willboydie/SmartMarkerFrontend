package com.example.smartmarkerfrontend.data

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

object DemoPlans {
    const val RANGE = 20f
    const val HALF_FOV = 0.40f

    private fun n(id: String, x: Float, y: Float, st: NodeStatus) = MarkerNode(id, x, y, st)

    val riversideGeometry = PlanGeometry(
        bounds = Rect(0f, 0f, 11.8f, 9.6f),
        outlines = listOf(
            Polyline(
                listOf(
                    Offset(0f, 0f),
                    Offset(11.8f, 0f),
                    Offset(11.8f, 7.4f),
                    Offset(7.2f, 7.4f),
                    Offset(7.2f, 9.6f),
                    Offset(0f, 9.6f)
                ),
                closed = true
            )
        ),
        walls = listOf(
            Polyline(listOf(Offset(4.2f, 0f), Offset(4.2f, 5.2f), Offset(11.8f, 5.2f))),
            Polyline(listOf(Offset(0f, 5.2f), Offset(4.2f, 5.2f))),
            Polyline(listOf(Offset(7.2f, 7.4f), Offset(7.2f, 5.2f)))
        ),
        openings = listOf(
            Polyline(listOf(Offset(0f, 0.9f), Offset(0f, 2.6f))),
            Polyline(listOf(Offset(0f, 6.2f), Offset(0f, 7.9f))),
            Polyline(listOf(Offset(4.2f, 1.8f), Offset(4.2f, 3.3f)))
        ),
        circles = listOf(
            CircleGeom(Offset(9.5f, 6.3f), 0.3f),
            CircleGeom(Offset(2.1f, 7.6f), 0.3f)
        ),
        dashed = listOf(
            Polyline(
                listOf(
                    Offset(0.6f, 0.6f),
                    Offset(3.6f, 0.6f),
                    Offset(3.6f, 4.6f),
                    Offset(0.6f, 4.6f)
                ),
                closed = true
            )
        ),
        marker = Offset(8.3f, 8.9f)
    )

    val riverside: PlanGeometry get() = riversideGeometry

    fun revCNodes(): List<MarkerNode> = listOf(
        n("V01", 0f, 0f, NodeStatus.Projected),
        n("V02", 11.8f, 0f, NodeStatus.Surveyed),
        n("V03", 11.8f, 7.4f, NodeStatus.Surveyed),
        n("V04", 7.2f, 7.4f, NodeStatus.Open),
        n("V05", 7.2f, 9.6f, NodeStatus.Open),
        n("V06", 0f, 9.6f, NodeStatus.Open),
        n("V07", 4.2f, 0f, NodeStatus.NotVisible),
        n("V08", 4.2f, 5.2f, NodeStatus.Open),
        n("V09", 11.8f, 5.2f, NodeStatus.Open),
        n("V10", 0f, 5.2f, NodeStatus.Projected),
        n("V11", 9.5f, 6.3f, NodeStatus.Open),
        n("V12", 2.1f, 7.6f, NodeStatus.Open)
    ).map { it.withPlanCorners(riversideGeometry) }
}
