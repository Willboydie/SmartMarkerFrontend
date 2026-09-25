package com.example.smartmarkerfrontend.data

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class PlanCoordinatesTest {
    @Test
    fun worldToScreen_eastRight_northUp() {
        val pan = Offset(100f, 200f)
        val scale = 0.5f
        val origin = worldToScreen(0f, 0f, scale, pan)
        val east = worldToScreen(20f, 0f, scale, pan)
        val north = worldToScreen(0f, 20f, scale, pan)
        assertEquals(100f, origin.x, 0.01f)
        assertEquals(200f, origin.y, 0.01f)
        assertTrue(east.x > origin.x)
        assertEquals(origin.y, east.y, 0.01f)
        assertEquals(origin.x, north.x, 0.01f)
        assertTrue(north.y < origin.y)
    }

    @Test
    fun screenToWorld_roundTrip() {
        val pan = Offset(40f, 80f)
        val scale = 0.25f
        val screen = worldToScreen(1200f, 3400f, scale, pan)
        val world = screenToWorld(screen, scale, pan)
        assertEquals(1200f, world.x, 0.05f)
        assertEquals(3400f, world.y, 0.05f)
    }

    @Test
    fun wrapTwoPi_staysInRange() {
        assertEquals(0f, wrapTwoPi(0f), 1e-5f)
        assertTrue(wrapTwoPi(-0.1f) > 0f)
        assertTrue(wrapTwoPi((2.0 * PI + 0.2).toFloat()) < TWO_PI)
    }

    @Test
    fun v01_isRightAngle_orientedFromNorth() {
        val v01 = DemoPlans.revCNodes().first { it.id == "V01" }
        assertEquals(PI.toFloat() / 2f, v01.cornerInnerAngleRad, 0.05f)
        assertEquals(0f, v01.cornerOrientationRad, 0.05f)
    }

    @Test
    fun fov_deviceIsOrigin_yawTurnsClockwise() {
        val north = MarkerNode("N", 0f, 2f, NodeStatus.Open)
        val east = MarkerNode("E", 2f, 0f, NodeStatus.Open)
        val eastOfMarker = MarkerNode("M", 7f, 4f, NodeStatus.Open)
        assertTrue(EmptyPlanGeometry.marker != Offset(0f, 0f))

        fun posed(yaw: Float) = SmartMarkerState(
            geometry = EmptyPlanGeometry,
            posed = true,
            deviceYawRad = yaw
        )

        assertEquals(20f, DemoPlans.RANGE, 0f)
        assertEquals(0.40f, DemoPlans.HALF_FOV, 0f)

        val facingNorth = posed(0f)
        assertTrue(facingNorth.inView(north))
        assertFalse(facingNorth.inView(east))
        assertFalse(facingNorth.inView(eastOfMarker))
        val onMarker = MarkerNode(
            "OnMarker",
            EmptyPlanGeometry.marker.x,
            EmptyPlanGeometry.marker.y,
            NodeStatus.Open
        )
        assertFalse(facingNorth.inView(onMarker))

        val justOutside = 0.42f
        val outsideWedge = MarkerNode(
            "Out",
            sin(justOutside),
            cos(justOutside),
            NodeStatus.Open
        )
        assertFalse(facingNorth.inView(outsideWedge))

        val facingEast = posed((PI / 2.0).toFloat())
        assertFalse(facingEast.inView(north))
        assertTrue(facingEast.inView(east))

        assertEquals((PI / 2.0).toFloat(), wedgeScreenAngle(0f), 1e-5f)
        assertEquals(0f, wedgeScreenAngle((PI / 2.0).toFloat()), 1e-5f)

        val atRange = MarkerNode("Near", 0f, 20f, NodeStatus.Open)
        val tooFar = MarkerNode("Far", 0f, 20.05f, NodeStatus.Open)
        assertTrue(facingNorth.inView(atRange))
        assertFalse(facingNorth.inView(tooFar))
    }

    @Test
    fun fitScale_putsMaxNorthAboveMinNorth() {
        val geom = DemoPlans.riversideGeometry
        val (s, pan) = fitScale(1200f, 800f, geom)
        val south = worldToScreen(geom.bounds.left, geom.bounds.top, s, pan)
        val north = worldToScreen(geom.bounds.left, geom.bounds.bottom, s, pan)
        assertTrue(north.y < south.y)
        assertTrue(abs(south.x - north.x) < 0.5f)
    }

    @Test
    fun formatCoord_convertsStoredMetresForDisplay() {
        assertEquals("1.276 m", formatCoord(1.276f, Units.Metres))
        assertEquals("4.186 ft", formatCoord(1.276f, Units.Feet))
        val mm = formatCoord(1.276f, Units.Millimetres)
        assertTrue(mm.endsWith(" mm"))
        assertEquals("1276", mm.filter { it.isDigit() })
    }
}
