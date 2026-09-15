package com.example.smartmarkerfrontend.device

import com.example.smartmarkerfrontend.data.ConnectionStatus
import com.example.smartmarkerfrontend.data.DeviceTelemetry
import com.example.smartmarkerfrontend.data.NodePacket
import com.example.smartmarkerfrontend.data.Pose
import com.example.smartmarkerfrontend.data.ProjectionMode
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streaming decoder for the tablet ← device hop in docs/protocol.md.
 *
 * Feed any number of USB (or ADB-tunnel) bytes; complete frames are emitted
 * as [DeviceTelemetry]. Pairing cmd [DeviceLinkConfig.PAIRING_CMD] is skipped
 * as a whole frame. Unknown `cmd` bytes are dropped one at a time so a
 * desynced stream can resynchronise. Same parser is used for the debug cable
 * and for the USB-C dongle.
 */
class SerialFrameParser(
    private val maxPayload: Int = DeviceLinkConfig.MAX_PAYLOAD
) {
    private val buffer = ArrayDeque<Byte>(256)

    var droppedBytes: Int = 0
        private set

    fun reset() {
        buffer.clear()
        droppedBytes = 0
    }

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size): List<DeviceTelemetry> {
        val end = (offset + length).coerceAtMost(data.size)
        for (i in offset until end) buffer.addLast(data[i])
        return drain()
    }

    private fun drain(): List<DeviceTelemetry> {
        val out = ArrayList<DeviceTelemetry>()
        while (true) {
            if (buffer.size < HEADER_SIZE) return out
            val cmd = buffer.first().toUInt8()
            val length = peekU16(1)
            if (length > maxPayload) {
                buffer.removeFirst()
                droppedBytes++
                continue
            }
            val need = HEADER_SIZE + length
            if (cmd == DeviceLinkConfig.PAIRING_CMD) {
                if (buffer.size < need) return out
                repeat(need) { buffer.removeFirst() }
                droppedBytes += need
                continue
            }
            if (cmd !in DEVICE_CMDS) {
                buffer.removeFirst()
                droppedBytes++
                continue
            }
            if (buffer.size < need) return out
            val payload = ByteArray(length)
            repeat(HEADER_SIZE) { buffer.removeFirst() }
            for (i in 0 until length) payload[i] = buffer.removeFirst()
            decode(cmd, payload)?.let(out::add)
        }
    }

    private fun peekU16(index: Int): Int {
        val lo = buffer.elementAt(index).toUInt8()
        val hi = buffer.elementAt(index + 1).toUInt8()
        return lo or (hi shl 8)
    }

    companion object {
        const val HEADER_SIZE = DeviceLinkConfig.HEADER_SIZE
        const val CMD_FIRMWARE_VERSION = 0x01
        const val CMD_TELEMETRY = 0x02
        const val CMD_SURVEY_POINT = 0x03
        const val CMD_PROJECTION_START = 0x04
        const val CMD_SET_LASER_POWER = 0x10
        const val CMD_SET_PROJECTION_MODE = 0x11
        const val CMD_BEGIN_TAG_SEARCH = 0x12
        const val CMD_BEGIN_SURVEY = 0x13
        const val CMD_BEGIN_PROJECTION = 0x14
        const val CMD_TAG_FOUND = 0x15
        const val CMD_NUDGE_BEAM_X = 0x16
        const val CMD_NUDGE_BEAM_Y = 0x17
        const val CMD_SET_ORIGIN = 0x18
        const val CMD_REGISTER_NODES = 0x19

        private val DEVICE_CMDS = setOf(
            CMD_FIRMWARE_VERSION,
            CMD_TELEMETRY,
            CMD_SURVEY_POINT,
            CMD_PROJECTION_START
        )

        fun decode(cmd: Int, payload: ByteArray): DeviceTelemetry? = try {
            when (cmd) {
                CMD_FIRMWARE_VERSION -> decodeFirmwareVersion(payload)
                CMD_TELEMETRY -> decodeTelemetry(payload)
                CMD_SURVEY_POINT -> decodeSurveyPoint(payload)
                CMD_PROJECTION_START -> decodeProjectionStart(payload)
                else -> null
            }
        } catch (_: Exception) {
            null
        }

        private fun decodeFirmwareVersion(payload: ByteArray): DeviceTelemetry.Handshake? {
            if (payload.isEmpty()) return null
            if (payload[0].toUInt8() != 1) return null
            val version = payload.decodeToString(1, payload.size)
            return DeviceTelemetry.Handshake(version)
        }

        private fun decodeTelemetry(payload: ByteArray): DeviceTelemetry.Regular? {
            if (payload.size != 40) return null
            val buf = payload.leBuffer()
            val healthOk = buf.bool()
            val moved = buf.bool()
            val originIsSet = buf.bool()
            val isSurveying = buf.bool()
            val surveyReady = buf.bool()
            val isProjecting = buf.bool()
            val laserOn = buf.bool()
            val connectionStatus = connectionStatus(buf.u8())
            val projectionMode = projectionMode(buf.u8())
            val laserPower = buf.u8().coerceIn(0, 100)
            val poseValid = buf.bool()
            buf.u8() // reserved
            val effectiveYaw = buf.float
            val pose = buf.pose()
            return DeviceTelemetry.Regular(
                healthOk = healthOk,
                effectiveYaw = effectiveYaw,
                moved = moved,
                lastKnownPose = if (poseValid) pose else null,
                connectionStatus = connectionStatus,
                originIsSet = originIsSet,
                isSurveying = isSurveying,
                surveyReady = surveyReady,
                isProjecting = isProjecting,
                projectionMode = projectionMode,
                laserOn = laserOn,
                laserPower = laserPower
            )
        }

        private fun decodeSurveyPoint(payload: ByteArray): DeviceTelemetry.SurveyPoint? {
            if (payload.size != 27) return null
            val buf = payload.leBuffer()
            val nodeId = NodeIds.fromWire(buf.u16())
            val success = buf.bool()
            val pose = buf.pose()
            return DeviceTelemetry.SurveyPoint(nodeId, success, pose)
        }

        private fun decodeProjectionStart(payload: ByteArray): DeviceTelemetry.ProjectionStart? {
            if (payload.size < 2) return null
            val buf = payload.leBuffer()
            val n = buf.u16()
            if (buf.remaining() != 18 * n) return null
            val nodes = List(n) { buf.node() }
            return DeviceTelemetry.ProjectionStart(nodes)
        }

        private fun connectionStatus(value: Int): ConnectionStatus =
            ConnectionStatus.entries.getOrNull(value) ?: ConnectionStatus.Lost

        private fun projectionMode(value: Int): ProjectionMode =
            ProjectionMode.entries.getOrNull(value) ?: ProjectionMode.Off
    }
}

object NodeIds {
    fun toWire(id: String): Int {
        val digits = id.filter { it.isDigit() }
        return (digits.toIntOrNull() ?: (id.hashCode() and 0xFFFF)) and 0xFFFF
    }

    fun fromWire(id: Int): String = "V%02d".format(id and 0xFFFF)
}

internal fun Byte.toUInt8(): Int = toInt() and 0xFF

internal fun ByteArray.leBuffer(): ByteBuffer =
    ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)

internal fun ByteBuffer.u8(): Int = get().toUInt8()

internal fun ByteBuffer.u16(): Int = short.toInt() and 0xFFFF

internal fun ByteBuffer.bool(): Boolean = u8() != 0

internal fun ByteBuffer.pose(): Pose = Pose(
    eastMm = float,
    northMm = float,
    upMm = float,
    rollRad = float,
    pitchRad = float,
    yawRad = float
)

internal fun ByteBuffer.node(): NodePacket = NodePacket(
    id = NodeIds.fromWire(u16()),
    eastMm = float,
    northMm = float,
    cornerInnerAngleRad = float,
    cornerOrientationRad = float
)
