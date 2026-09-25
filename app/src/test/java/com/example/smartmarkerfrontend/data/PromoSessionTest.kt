package com.example.smartmarkerfrontend.data

import com.example.smartmarkerfrontend.device.TabletFrameEncoder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromoSessionTest {
    @Test
    fun g15_setOrigin_isEmptyBegin_andPoseWaitsForTelemetry() {
        val session = PromoSession()
        val command = session.setOrigin()
        assertTrue(command is TabletCommand.BeginOriginSet)
        assertArrayEquals(byteArrayOf(0x23, 0x00, 0x00), TabletFrameEncoder.encode(command))
        assertFalse(session.posed)

        session.onTelemetry(originIsSet = false, moved = false)
        assertFalse(session.posed)
        session.onTelemetry(originIsSet = true, moved = false)
        assertTrue(session.posed)
    }

    @Test
    fun g16_movedBit_doesNotClearPose_orShowMovedBanner() {
        val session = PromoSession()
        session.onTelemetry(originIsSet = true, moved = false)
        session.onTelemetry(originIsSet = true, moved = true)
        assertTrue(session.posed)
        assertNull(planBanner(posed = true))
        val missing = planBanner(posed = false)
        assertTrue(missing != null && !missing.contains("moved", ignoreCase = true))
    }

    @Test
    fun g17_startJoinEnd_encodeSessionCommands_andEndClearsOrigin() {
        val session = PromoSession()
        assertArrayEquals(
            byteArrayOf(0x20, 0x00, 0x00),
            TabletFrameEncoder.encode(session.startSession())
        )

        session.noteAdvertised("abcd1234")
        assertArrayEquals(
            byteArrayOf(
                0x21, 0x09, 0x00, 0x08,
                'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 'd'.code.toByte(),
                '1'.code.toByte(), '2'.code.toByte(), '3'.code.toByte(), '4'.code.toByte()
            ),
            TabletFrameEncoder.encode(session.joinSession())
        )

        session.onTelemetry(originIsSet = true, moved = false)
        assertArrayEquals(
            byteArrayOf(
                0x22, 0x09, 0x00, 0x08,
                'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 'd'.code.toByte(),
                '1'.code.toByte(), '2'.code.toByte(), '3'.code.toByte(), '4'.code.toByte()
            ),
            TabletFrameEncoder.encode(session.endSession())
        )
        assertFalse(session.posed)
        assertEquals("", session.sessionId)
        assertFalse(session.connectedDue(10_000L))
    }

    @Test
    fun connectedKeepalive_matchesOneSessionForFiveSeconds() {
        val session = PromoSession()
        assertFalse(session.connectedDue(1_000L))
        assertTrue(session.acceptTelemetry("sm-ccu", "abcd1234", true, false, 1_000L))
        assertTrue(session.posed)
        assertEquals("abcd1234", session.sessionId)
        assertTrue(session.connectedDue(5_999L))
        assertFalse(session.connectedDue(6_000L))

        assertFalse(session.acceptTelemetry("other", "abcd1234", false, false, 2_000L))
        assertTrue(session.posed)
        assertTrue(session.connectedDue(5_999L))
        assertFalse(session.connectedDue(6_000L))
    }
}