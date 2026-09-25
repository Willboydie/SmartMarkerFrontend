package com.example.smartmarkerfrontend.data

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DxfParserTest {
    @Test
    fun buildsGeometryFromPlanLinePointsAndNodes() {
        val extracted = DxfParser.fromParsed(
            planLinePoints = listOf(
                listOf(Offset(0f, 0f), Offset(5000f, 0f), Offset(5000f, 3000f), Offset(0f, 0f))
            ),
            nodes = listOf(MarkerNode("V01", 1000f, 2000f, NodeStatus.Open))
        )

        assertEquals(1, extracted.planLinePoints.size)
        assertEquals(4, extracted.planLinePoints[0].size)
        assertEquals(1f, extracted.nodes.single().x, 0.0001f)
        assertEquals(2f, extracted.nodes.single().y, 0.0001f)
        assertEquals("V01", extracted.nodes.single().id)
        assertEquals(NodeStatus.Open, extracted.nodes.single().status)
        assertTrue(extracted.geometry.outlines.isNotEmpty())
        assertTrue(extracted.geometry.outlines[0].closed)
        assertTrue(extracted.geometry.bounds.width > 0f)
        assertTrue(extracted.geometry.bounds.height > 0f)
        assertTrue(extracted.geometry.marker.y < extracted.geometry.bounds.center.y)
    }
}
