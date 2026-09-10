package com.example.smartmarkerfrontend.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DxfParserTest {
    @Test
    fun extractsPointsAndPolylines() {
        val dxf = """
            0
            SECTION
            2
            ENTITIES
            0
            POINT
            8
            0
            10
            1000.0
            20
            2000.0
            0
            TEXT
            10
            1002.0
            20
            2001.0
            1
            V01
            0
            LINE
            10
            0.0
            20
            0.0
            11
            5000.0
            21
            0.0
            0
            CIRCLE
            10
            4200.0
            20
            800.0
            40
            50.0
            0
            ENDSEC
            0
            EOF
        """.trimIndent()

        val extracted = DxfParser.extract(dxf)
        assertTrue(extracted.nodes.isNotEmpty())
        assertTrue(extracted.nodes.any { it.id.contains("V01") || it.x in 990f..1010f })
        assertTrue(extracted.geometry.outlines.isNotEmpty() || extracted.geometry.circles.isNotEmpty())
        val v01 = extracted.nodes.first { it.id.startsWith("V") }
        assertEquals(NodeStatus.Open, v01.status)
    }
}
