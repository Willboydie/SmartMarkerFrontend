package com.example.smartmarkerfrontend.device

import com.example.smartmarkerfrontend.data.DeviceCmd
import com.example.smartmarkerfrontend.data.PlanNode
import com.example.smartmarkerfrontend.data.TabletCommand
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TabletFrameEncoderTest {
    @Test
    fun connected_matchesG1Fixture() {
        val frame = TabletFrameEncoder.encode(TabletCommand.Connected("pad"))
        assertArrayEquals(PromoFrames.connected, frame)
    }

    @Test
    fun emptyCommands_matchG1Fixtures() {
        val cases = listOf(
            DeviceCmd.BEGIN_NEW_SESSION to TabletCommand.BeginNewSession,
            DeviceCmd.BEGIN_ORIGIN_SET to TabletCommand.BeginOriginSet,
            DeviceCmd.BEGIN_TAG_FINDING to TabletCommand.BeginTagFinding,
            DeviceCmd.ABORT_SURVEY to TabletCommand.AbortSurvey,
            DeviceCmd.PROJECTOR_PAUSE to TabletCommand.ProjectorPause,
            DeviceCmd.PROJECTOR_PLAY to TabletCommand.ProjectorPlay,
            DeviceCmd.PROJECTION_END to TabletCommand.ProjectionEnd
        )
        for ((cmd, command) in cases) {
            assertArrayEquals(byteArrayOf(cmd.toByte(), 0, 0), TabletFrameEncoder.encode(command))
        }
    }

    @Test
    fun sessionIds_matchG1Fixtures() {
        assertArrayEquals(
            PromoFrames.joinSession,
            TabletFrameEncoder.encode(TabletCommand.JoinSession("abcd1234"))
        )
        assertArrayEquals(
            PromoFrames.endSession,
            TabletFrameEncoder.encode(TabletCommand.EndSession("abcd1234"))
        )
    }

    @Test
    fun planCommands_matchG1Fixtures() {
        val node = PlanNode("V01", 1f, 2f, 0.5f, 0f)
        assertArrayEquals(
            PromoFrames.performSurvey,
            TabletFrameEncoder.encode(TabletCommand.PerformSurvey(listOf(node)))
        )
        val projection = PromoFrames.performSurvey.copyOf()
        projection[0] = DeviceCmd.PERFORM_PROJECTION.toByte()
        assertArrayEquals(
            projection,
            TabletFrameEncoder.encode(TabletCommand.PerformProjection(listOf(node)))
        )
        assertArrayEquals(
            PromoFrames.performSurveyEmpty,
            TabletFrameEncoder.encode(TabletCommand.PerformSurvey(emptyList()))
        )
    }

    @Test
    fun laserPower_matchesG1Range() {
        assertArrayEquals(PromoFrames.setLaserPower30, TabletFrameEncoder.encode(TabletCommand.SetLaserPower(30)))
        assertArrayEquals(PromoFrames.setLaserPower15, TabletFrameEncoder.encode(TabletCommand.SetLaserPower(15)))
        assertArrayEquals(PromoFrames.setLaserPower99, TabletFrameEncoder.encode(TabletCommand.SetLaserPower(99)))
        assertNull(TabletFrameEncoder.encode(TabletCommand.SetLaserPower(14)))
        assertNull(TabletFrameEncoder.encode(TabletCommand.SetLaserPower(100)))
        assertNull(TabletFrameEncoder.encode(TabletCommand.SetLaserPower(0)))
    }

    @Test
    fun nudgesAndTag_matchG1Fixtures() {
        assertArrayEquals(PromoFrames.nudgeX, TabletFrameEncoder.encode(TabletCommand.NudgeX(-2)))
        assertArrayEquals(PromoFrames.nudgeY, TabletFrameEncoder.encode(TabletCommand.NudgeY(5)))
        assertArrayEquals(PromoFrames.nudgeXMin, TabletFrameEncoder.encode(TabletCommand.NudgeX(-32768)))
        assertArrayEquals(PromoFrames.nudgeXMax, TabletFrameEncoder.encode(TabletCommand.NudgeX(32767)))
        assertArrayEquals(PromoFrames.tagAligned, TabletFrameEncoder.encode(TabletCommand.TagAligned("t1")))
    }

    @Test
    fun stringLength_dropsAbove32() {
        val limit = TabletFrameEncoder.encode(TabletCommand.Connected("a".repeat(32)))
        assertNotNull(limit)
        assertEquals(32, limit!![3].toInt() and 0xFF)
        assertNull(TabletFrameEncoder.encode(TabletCommand.Connected("a".repeat(33))))
        assertNull(TabletFrameEncoder.encode(TabletCommand.JoinSession("b".repeat(33))))
    }

    @Test
    fun reservedCmds_areRefused() {
        assertNull(TabletFrameEncoder.frame(DeviceCmd.DONGLE_RESET, byteArrayOf()))
        assertNull(TabletFrameEncoder.frame(DeviceCmd.DONGLE_PAIRING, byteArrayOf()))
        assertNull(TabletFrameEncoder.frame(DeviceCmd.DONGLE_STATUS, byteArrayOf()))
        assertNull(TabletFrameEncoder.frame(0x06, byteArrayOf()))
        assertNull(TabletFrameEncoder.frame(DeviceCmd.CONNECTED, ByteArray(DeviceLinkConfig.MAX_PAYLOAD + 1)))
    }
}
