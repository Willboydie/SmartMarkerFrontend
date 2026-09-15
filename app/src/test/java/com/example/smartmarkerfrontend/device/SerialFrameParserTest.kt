package com.example.smartmarkerfrontend.device

import com.example.smartmarkerfrontend.data.Pose
import com.example.smartmarkerfrontend.data.TabletCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SerialFrameParserTest {
    @Test
    fun firmwareVersion_matchesProtocolExample() {
        val payload = byteArrayOf(0x01) + "1.4.2".encodeToByteArray()
        val frame = byteArrayOf(0x01, 0x06, 0x00) + payload
        val parsed = SerialFrameParser().feed(frame)
        assertEquals(1, parsed.size)
        val hs = parsed[0] as com.example.smartmarkerfrontend.data.DeviceTelemetry.Handshake
        assertEquals("1.4.2", hs.firmwareVersion)
    }

    @Test
    fun telemetry_40BytePayload() {
        val payload = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(1) // health
            put(0) // moved
            put(1) // origin
            put(0); put(1); put(0); put(0) // surveying, surveyReady, projecting, laser
            put(0) // Linked
            put(0) // Off
            put(80) // duty
            put(1) // poseValid
            put(0) // reserved
            putFloat(1.5f)
            putFloat(100f); putFloat(200f); putFloat(30f)
            putFloat(0.1f); putFloat(0.2f); putFloat(1.5f)
        }.array()
        val frame = TabletFrameEncoder.frame(SerialFrameParser.CMD_TELEMETRY, payload)
        val parsed = SerialFrameParser().feed(frame)
        val telem = parsed[0] as com.example.smartmarkerfrontend.data.DeviceTelemetry.Regular
        assertTrue(telem.healthOk)
        assertTrue(telem.originIsSet)
        assertTrue(telem.surveyReady)
        assertEquals(80, telem.laserPower)
        assertEquals(100f, telem.lastKnownPose?.eastMm)
        assertEquals(1.5f, telem.effectiveYaw)
    }

    @Test
    fun splitReads_stillParse() {
        val frame = TabletFrameEncoder.encode(TabletCommand.SetLaserPower(40))
        // Parser only emits device→tablet frames; encode a handshake and split it.
        val hs = byteArrayOf(0x01, 0x06, 0x00, 0x01) + "1.4.2".encodeToByteArray()
        val parser = SerialFrameParser()
        assertTrue(parser.feed(hs.copyOfRange(0, 2)).isEmpty())
        assertTrue(parser.feed(hs.copyOfRange(2, 5)).isEmpty())
        val got = parser.feed(hs.copyOfRange(5, hs.size))
        assertEquals(1, got.size)
        assertEquals("1.4.2", (got[0] as com.example.smartmarkerfrontend.data.DeviceTelemetry.Handshake).firmwareVersion)
        assertEquals(0x10.toByte(), frame[0])
        assertEquals(40, frame[3].toInt() and 0xFF)
    }

    @Test
    fun unknownCmd_isDroppedUntilNextDeviceFrame() {
        val hs = byteArrayOf(0x01, 0x03, 0x00, 0x01) + "ok".encodeToByteArray()
        val junk = byteArrayOf(0x99.toByte(), 0x00, 0x00) + hs
        val parser = SerialFrameParser()
        val got = parser.feed(junk)
        assertEquals(1, got.size)
        assertTrue(parser.droppedBytes > 0)
    }

    @Test
    fun setOrigin_encodes24BytePose() {
        val pose = Pose(0f, 0f, 0f, 0f, 0f, 1.2f)
        val frame = TabletFrameEncoder.encode(TabletCommand.SetOrigin(pose))
        assertEquals(SerialFrameParser.CMD_SET_ORIGIN.toByte(), frame[0])
        assertEquals(24, frame[1].toInt() and 0xFF)
        val yaw = ByteBuffer.wrap(frame, 3 + 20, 4).order(ByteOrder.LITTLE_ENDIAN).float
        assertEquals(1.2f, yaw, 1e-5f)
    }

    @Test
    fun registerNodes_mapsVLabelsToU16() {
        assertEquals(1, NodeIds.toWire("V01"))
        assertEquals(12, NodeIds.toWire("V12"))
        assertEquals("V07", NodeIds.fromWire(7))
        val node = com.example.smartmarkerfrontend.data.NodePacket("V03", 10f, 20f, 1.5f, 0.2f)
        val frame = TabletFrameEncoder.encode(TabletCommand.RegisterNodes(listOf(node)))
        assertEquals(SerialFrameParser.CMD_REGISTER_NODES.toByte(), frame[0])
        val id = ByteBuffer.wrap(frame, 5, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        assertEquals(3, id)
    }

    @Test
    fun surveyPoint_27BytePayload() {
        val payload = ByteBuffer.allocate(27).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(3)
            put(1) // success
            putFloat(10f); putFloat(20f); putFloat(0f)
            putFloat(0f); putFloat(0f); putFloat(1.2f)
        }.array()
        val frame = TabletFrameEncoder.frame(SerialFrameParser.CMD_SURVEY_POINT, payload)
        val parsed = SerialFrameParser().feed(frame)
        val point = parsed[0] as com.example.smartmarkerfrontend.data.DeviceTelemetry.SurveyPoint
        assertEquals("V03", point.nodeId)
        assertTrue(point.success)
        assertEquals(10f, point.pose.eastMm)
        assertEquals(20f, point.pose.northMm)
    }

    @Test
    fun beginSurvey_idList() {
        val frame = TabletFrameEncoder.encode(TabletCommand.BeginSurvey(listOf("V01", "V02")))
        assertEquals(SerialFrameParser.CMD_BEGIN_SURVEY.toByte(), frame[0])
        val buf = ByteBuffer.wrap(frame, 3, frame.size - 3).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(2, buf.short.toInt())
        assertEquals(1, buf.short.toInt())
        assertEquals(2, buf.short.toInt())
    }

    @Test
    fun wrongTelemetryLength_isDropped() {
        val frame = TabletFrameEncoder.frame(SerialFrameParser.CMD_TELEMETRY, byteArrayOf(1, 2, 3))
        val parsed = SerialFrameParser().feed(frame)
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun protocolVersionNot1_isDropped() {
        val frame = byteArrayOf(0x01, 0x02, 0x00, 0x02, 'x'.code.toByte())
        assertTrue(SerialFrameParser().feed(frame).isEmpty())
    }

    @Test
    fun nudgeEncodesLittleEndianFloat() {
        val frame = TabletFrameEncoder.encode(TabletCommand.NudgeBeamX(0.5f))
        assertEquals(SerialFrameParser.CMD_NUDGE_BEAM_X.toByte(), frame[0])
        val dx = ByteBuffer.wrap(frame, 3, 4).order(ByteOrder.LITTLE_ENDIAN).float
        assertEquals(0.5f, dx, 1e-6f)
        assertNull(SerialFrameParser.decode(frame[0].toInt() and 0xFF, frame.copyOfRange(3, frame.size)))
    }

    @Test
    fun pairingFrame_isSkippedWhole_soPayloadDoesNotLookLikeCommands() {
        val handshake = byteArrayOf(0x01, 0x03, 0x00, 0x01) + "ok".encodeToByteArray()
        // 0x10 would be SET_LASER_POWER if the pairing payload were parsed as cmds.
        val pairing = byteArrayOf(
            DeviceLinkConfig.PAIRING_CMD.toByte(),
            0x04, 0x00,
            0x10, 0x01, 0x02, 0x03
        )
        val parser = SerialFrameParser()
        val got = parser.feed(pairing + handshake)
        assertEquals(1, got.size)
        val hs = got[0] as com.example.smartmarkerfrontend.data.DeviceTelemetry.Handshake
        assertEquals("ok", hs.firmwareVersion)
        assertEquals(pairing.size, parser.droppedBytes)
    }

    @Test
    fun zeroLengthPayload_isConsumed() {
        val frame = byteArrayOf(0x02, 0x00, 0x00) // TELEMETRY, len=0 → decode drops
        val parser = SerialFrameParser()
        assertTrue(parser.feed(frame).isEmpty())
        assertEquals(0, parser.droppedBytes)
        val hs = byteArrayOf(0x01, 0x03, 0x00, 0x01) + "ok".encodeToByteArray()
        assertEquals(1, parser.feed(hs).size)
    }

    @Test
    fun encoderRejectsPairingCmdAndOversize() {
        try {
            TabletFrameEncoder.frame(DeviceLinkConfig.PAIRING_CMD, byteArrayOf())
            throw AssertionError("expected pairing cmd to be rejected")
        } catch (_: IllegalArgumentException) {
        }
        val huge = ByteArray(DeviceLinkConfig.MAX_PAYLOAD + 1)
        try {
            TabletFrameEncoder.frame(SerialFrameParser.CMD_TELEMETRY, huge)
            throw AssertionError("expected oversize frame to be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun cdcLineCoding_is921600_8N1() {
        val coding = DeviceLinkConfig.cdcLineCoding()
        assertEquals(7, coding.size)
        val baud = (coding[0].toInt() and 0xFF) or
            ((coding[1].toInt() and 0xFF) shl 8) or
            ((coding[2].toInt() and 0xFF) shl 16) or
            ((coding[3].toInt() and 0xFF) shl 24)
        assertEquals(921600, baud)
        assertEquals(0, coding[4].toInt())
        assertEquals(0, coding[5].toInt())
        assertEquals(8, coding[6].toInt())
        assertEquals(0, DeviceLinkConfig.CDC_LINE_STATE_IDLE)
        assertEquals(0xE5, DeviceLinkConfig.PAIRING_CMD)
        assertEquals(0x303A, DeviceLinkConfig.ESPRESSIF_VID)
        assertEquals(0x1001, DeviceLinkConfig.ESPRESSIF_PID)
    }
}
