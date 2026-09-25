package com.example.smartmarkerfrontend.device

import com.example.smartmarkerfrontend.data.DeviceCmd
import com.example.smartmarkerfrontend.data.DeviceTelemetry
import com.example.smartmarkerfrontend.data.NodeError
import com.example.smartmarkerfrontend.data.PlanNode
import com.example.smartmarkerfrontend.data.Pose
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streaming decoder for device → tablet frames.
 * Reserved dongle cmds 0xE4, 0xE5, and 0xE6 are consumed whole and not emitted.
 * A payload that does not match the catalog layout is consumed and dropped.
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
            if (buffer.size < need) return out
            repeat(HEADER_SIZE) { buffer.removeFirst() }
            val payload = ByteArray(length)
            for (i in 0 until length) payload[i] = buffer.removeFirst()
            val accepted = cmd !in DeviceCmd.RESERVED && cmd in DEVICE_CMDS
            val decoded = if (accepted) decode(cmd, payload) else null
            if (decoded == null) droppedBytes += need
            else out.add(decoded)
        }
    }

    private fun peekU16(index: Int): Int {
        val lo = buffer.elementAt(index).toUInt8()
        val hi = buffer.elementAt(index + 1).toUInt8()
        return lo or (hi shl 8)
    }

    companion object {
        const val HEADER_SIZE = DeviceLinkConfig.HEADER_SIZE

        private val DEVICE_CMDS = setOf(
            DeviceCmd.DEVICE_ID,
            DeviceCmd.PAIRING_ACCEPTED,
            DeviceCmd.REGULAR_TELEMETRY,
            DeviceCmd.SURVEY_RESULT,
            DeviceCmd.PROJECTION_READY
        )

        fun decode(cmd: Int, payload: ByteArray): DeviceTelemetry? = try {
            when (cmd) {
                DeviceCmd.DEVICE_ID -> decodeDeviceId(payload)
                DeviceCmd.PAIRING_ACCEPTED -> decodePairingAccepted(payload)
                DeviceCmd.REGULAR_TELEMETRY -> decodeTelemetry(payload)
                DeviceCmd.SURVEY_RESULT -> decodeSurveyResult(payload)
                DeviceCmd.PROJECTION_READY -> decodeProjectionReady(payload)
                else -> null
            }
        } catch (_: Exception) {
            null
        }

        private fun decodeDeviceId(payload: ByteArray): DeviceTelemetry.DeviceId? {
            val buf = payload.leBuffer()
            val deviceId = buf.str() ?: return null
            val displayName = buf.str() ?: return null
            if (buf.remaining() < 1) return null
            val sessionActive = buf.bool()
            val sessionId = buf.str() ?: return null
            if (buf.remaining() != 0) return null
            return DeviceTelemetry.DeviceId(deviceId, displayName, sessionActive, sessionId)
        }

        private fun decodePairingAccepted(payload: ByteArray): DeviceTelemetry.PairingAccepted? {
            val buf = payload.leBuffer()
            val deviceId = buf.str() ?: return null
            if (buf.remaining() != 0) return null
            return DeviceTelemetry.PairingAccepted(deviceId)
        }

        private fun decodeTelemetry(payload: ByteArray): DeviceTelemetry.Regular? {
            val buf = payload.leBuffer()
            val deviceId = buf.str() ?: return null
            val sessionId = buf.str() ?: return null
            if (buf.remaining() < 1 + DeviceCmd.POSE_BYTES + 4) return null
            val originIsSet = buf.bool()
            val pose = buf.pose()
            val effectiveYaw = buf.float
            if (buf.remaining() < 8) return null
            return DeviceTelemetry.Regular(
                deviceId = deviceId,
                sessionId = sessionId,
                originIsSet = originIsSet,
                pose = pose,
                effectiveYaw = effectiveYaw,
                moved = buf.bool(),
                originSetStatus = buf.u8(),
                tagFindingStatus = buf.u8(),
                surveyStatus = buf.u8(),
                projectorStatus = buf.u8(),
                projectorRunning = buf.bool(),
                laserOn = buf.bool(),
                laserPower = buf.u8()
            ).takeIf { buf.remaining() == 0 }
        }

        private fun decodeSurveyResult(payload: ByteArray): DeviceTelemetry.SurveyResult? {
            if (payload.size != DeviceCmd.SURVEY_RESULT_BYTES) return null
            val buf = payload.leBuffer()
            return DeviceTelemetry.SurveyResult(
                id = buf.u16(),
                eastM = buf.float,
                northM = buf.float,
                upM = buf.float,
                cornerInnerAngleRad = buf.float,
                cornerOrientationRad = buf.float,
                surveyed = buf.bool(),
                success = buf.bool(),
                errorCode = buf.u8(),
                surveyFinished = buf.bool()
            ).takeIf { buf.remaining() == 0 }
        }

        private fun decodeProjectionReady(payload: ByteArray): DeviceTelemetry.ProjectionReady? {
            val buf = payload.leBuffer()
            if (buf.remaining() < 2) return null
            val count = buf.u16()
            val nodeBytes = DeviceCmd.PLAN_NODE_BYTES * count
            if (nodeBytes < 0 || buf.remaining() < nodeBytes + 1) return null
            val nodes = List(count) { buf.planNode() }
            val errorCount = buf.u8()
            if (buf.remaining() != 3 * errorCount) return null
            val errors = List(errorCount) { NodeError(buf.u16(), buf.u8()) }
            if (buf.remaining() != 0) return null
            return DeviceTelemetry.ProjectionReady(nodes, errors)
        }
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

internal fun ByteBuffer.str(): String? {
    if (remaining() < 1) return null
    val n = u8()
    if (n > DeviceCmd.STRING_MAX || remaining() < n) return null
    val raw = ByteArray(n)
    get(raw)
    return raw.decodeToString()
}

internal fun ByteBuffer.pose(): Pose = Pose(
    eastM = float,
    northM = float,
    upM = float,
    rollRad = float,
    pitchRad = float,
    yawRad = float
)

internal fun ByteBuffer.planNode(): PlanNode = PlanNode(
    id = NodeIds.fromWire(u16()),
    eastM = float,
    northM = float,
    cornerInnerAngleRad = float,
    cornerOrientationRad = float
)
