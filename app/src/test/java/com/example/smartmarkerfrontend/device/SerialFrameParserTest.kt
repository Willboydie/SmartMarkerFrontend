package com.example.smartmarkerfrontend.device

import com.example.smartmarkerfrontend.data.DeviceTelemetry
import com.example.smartmarkerfrontend.data.SurveyError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SerialFrameParserTest {
    @Test
    fun deviceId_matchesG1Fixture() {
        assertEquals(33, PromoFrames.deviceId.size)
        val msg = SerialFrameParser().feed(PromoFrames.deviceId).single() as DeviceTelemetry.DeviceId
        assertEquals("sm-ccu", msg.deviceId)
        assertEquals("Smart Marker", msg.displayName)
        assertTrue(msg.sessionActive)
        assertEquals("abcd1234", msg.sessionId)
    }

    @Test
    fun deviceId_nonZeroBoolIsTrue() {
        val msg = SerialFrameParser().feed(PromoFrames.deviceIdLooseBool).single() as DeviceTelemetry.DeviceId
        assertTrue(msg.sessionActive)
        assertEquals("", msg.deviceId)
        assertEquals("", msg.sessionId)
    }

    @Test
    fun pairingAccepted_matchesG1Fixture() {
        val msg = SerialFrameParser().feed(PromoFrames.pairingAccepted).single() as DeviceTelemetry.PairingAccepted
        assertEquals("sm-ccu", msg.deviceId)
    }

    @Test
    fun telemetry_matchesG1Fixture() {
        assertEquals(56, PromoFrames.telemetry.size)
        val msg = SerialFrameParser().feed(PromoFrames.telemetry).single() as DeviceTelemetry.Regular
        assertEquals("sm-ccu", msg.deviceId)
        assertEquals("abcd1234", msg.sessionId)
        assertTrue(msg.originIsSet)
        assertEquals(1f, msg.pose.eastM, 0f)
        assertEquals(2f, msg.pose.northM, 0f)
        assertEquals(3f, msg.pose.upM, 0f)
        assertEquals(4f, msg.pose.rollRad, 0f)
        assertEquals(5f, msg.pose.pitchRad, 0f)
        assertEquals(6f, msg.pose.yawRad, 0f)
        assertEquals(0.5f, msg.effectiveYaw, 0f)
        assertTrue(msg.moved)
        assertEquals(0, msg.originSetStatus)
        assertEquals(0, msg.tagFindingStatus)
        assertEquals(1, msg.surveyStatus)
        assertEquals(2, msg.projectorStatus)
        assertTrue(msg.projectorRunning)
        assertFalse(msg.laserOn)
        assertEquals(30, msg.laserPower)
    }

    @Test
    fun telemetry_laserPowerIsNotRangeChecked() {
        val frame = PromoFrames.telemetry.copyOf()
        frame[frame.size - 1] = 100
        val msg = SerialFrameParser().feed(frame).single() as DeviceTelemetry.Regular
        assertEquals(100, msg.laserPower)
    }

    @Test
    fun surveyResult_matchesG1Fixtures() {
        assertEquals(3 + 26, PromoFrames.surveyOk.size)
        val ok = SerialFrameParser().feed(PromoFrames.surveyOk).single() as DeviceTelemetry.SurveyResult
        assertEquals(1, ok.id)
        assertEquals(3f, ok.upM, 0f)
        assertTrue(ok.surveyed && ok.success && ok.surveyFinished)
        assertEquals(SurveyError.NONE, ok.errorCode)

        val failed = SerialFrameParser().feed(PromoFrames.surveyFailed).single() as DeviceTelemetry.SurveyResult
        assertEquals(7, failed.id)
        assertEquals(0f, failed.upM, 0f)
        assertFalse(failed.surveyed || failed.success || failed.surveyFinished)
        assertEquals(SurveyError.OUT_OF_FOV, failed.errorCode)
    }

    @Test
    fun surveyResult_wrongLengthDropped() {
        val shortFrame = PromoFrames.surveyOk.copyOf(PromoFrames.surveyOk.size - 1)
        shortFrame[1] = (shortFrame.size - 3).toByte()
        val parser = SerialFrameParser()
        assertTrue(parser.feed(shortFrame).isEmpty())
        assertTrue(parser.droppedBytes > 0)

        val longFrame = PromoFrames.surveyOk + byteArrayOf(0)
        longFrame[1] = (26 + 1).toByte()
        assertTrue(SerialFrameParser().feed(longFrame).isEmpty())
    }

    @Test
    fun projectionReady_matchesG1Fixtures() {
        assertEquals(27, PromoFrames.projectionReady.size)
        val msg = SerialFrameParser().feed(PromoFrames.projectionReady).single() as DeviceTelemetry.ProjectionReady
        assertEquals(1, msg.nodes.size)
        assertEquals("V01", msg.nodes[0].id)
        assertEquals(1f, msg.nodes[0].eastM, 0f)
        assertEquals(2f, msg.nodes[0].northM, 0f)
        assertEquals(0.5f, msg.nodes[0].cornerInnerAngleRad, 0f)
        assertEquals(0f, msg.nodes[0].cornerOrientationRad, 0f)
        assertEquals(1, msg.errors.size)
        assertEquals(2, msg.errors[0].id)
        assertEquals(4, msg.errors[0].errorCode)

        val empty = SerialFrameParser().feed(PromoFrames.projectionReadyEmpty).single() as DeviceTelemetry.ProjectionReady
        assertTrue(empty.nodes.isEmpty() && empty.errors.isEmpty())
    }

    @Test
    fun overlongString_isDropped() {
        val payload = ByteArray(1 + 1 + 33 + 1 + 1)
        payload[0] = 0
        payload[1] = 33
        payload.fill('x'.code.toByte(), 2, 35)
        payload[35] = 1
        payload[36] = 0
        val frame = byteArrayOf(0x01, payload.size.toByte(), 0x00) + payload
        assertTrue(SerialFrameParser().feed(frame).isEmpty())
    }

    @Test
    fun stringPastPayload_andTrailingByte_areDropped() {
        val overrun = promoBytes(0x01, 0x04, 0x00, 0x0A, "abc")
        assertTrue(SerialFrameParser().feed(overrun).isEmpty())
        val trailing = PromoFrames.pairingAccepted + byteArrayOf(0)
        trailing[1] = (PromoFrames.pairingAccepted.size - 3 + 1).toByte()
        assertTrue(SerialFrameParser().feed(trailing).isEmpty())
    }

    @Test
    fun reservedAndUnknown_areConsumedWhole() {
        val pairing = promoBytes(0xE5, 0x04, 0x00, 0x01, 0x00, 0x00, 0x10)
        val parser = SerialFrameParser()
        val got = parser.feed(pairing + PromoFrames.deviceId)
        assertEquals(1, got.size)
        assertEquals("sm-ccu", (got[0] as DeviceTelemetry.DeviceId).deviceId)
        assertEquals(pairing.size, parser.droppedBytes)

        val unknown = SerialFrameParser()
        val afterUnknown = unknown.feed(promoBytes(0xE4, 0x00, 0x00, 0xE6, 0x00, 0x00, 0x06, 0x00, 0x00) + PromoFrames.pairingAccepted)
        assertEquals(1, afterUnknown.size)
        assertEquals(9, unknown.droppedBytes)
    }

    @Test
    fun splitReads_stillParse() {
        val frame = PromoFrames.deviceId
        val parser = SerialFrameParser()
        assertTrue(parser.feed(frame.copyOfRange(0, 2)).isEmpty())
        assertEquals(0, parser.droppedBytes)
        val got = parser.feed(frame.copyOfRange(2, frame.size))
        assertEquals(1, got.size)
        assertEquals("Smart Marker", (got[0] as DeviceTelemetry.DeviceId).displayName)
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
        assertEquals(1, DeviceLinkConfig.CDC_LINE_STATE_DTR)
        assertEquals(0xE5, DeviceLinkConfig.PAIRING_CMD)
        assertEquals(0x303A, DeviceLinkConfig.ESPRESSIF_VID)
        assertEquals(0x1001, DeviceLinkConfig.ESPRESSIF_PID)
    }
}
