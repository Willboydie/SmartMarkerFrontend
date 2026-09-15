package com.example.smartmarkerfrontend.device

import com.example.smartmarkerfrontend.data.NodePacket
import com.example.smartmarkerfrontend.data.Pose
import com.example.smartmarkerfrontend.data.TabletCommand
import com.example.smartmarkerfrontend.device.SerialFrameParser.Companion.CMD_BEGIN_PROJECTION
import com.example.smartmarkerfrontend.device.SerialFrameParser.Companion.CMD_BEGIN_SURVEY
import com.example.smartmarkerfrontend.device.SerialFrameParser.Companion.CMD_BEGIN_TAG_SEARCH
import com.example.smartmarkerfrontend.device.SerialFrameParser.Companion.CMD_NUDGE_BEAM_X
import com.example.smartmarkerfrontend.device.SerialFrameParser.Companion.CMD_NUDGE_BEAM_Y
import com.example.smartmarkerfrontend.device.SerialFrameParser.Companion.CMD_REGISTER_NODES
import com.example.smartmarkerfrontend.device.SerialFrameParser.Companion.CMD_SET_LASER_POWER
import com.example.smartmarkerfrontend.device.SerialFrameParser.Companion.CMD_SET_ORIGIN
import com.example.smartmarkerfrontend.device.SerialFrameParser.Companion.CMD_SET_PROJECTION_MODE
import com.example.smartmarkerfrontend.device.SerialFrameParser.Companion.CMD_TAG_FOUND
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Packs tablet → device frames (docs/protocol.md §5). */
object TabletFrameEncoder {
    fun encode(command: TabletCommand): ByteArray = when (command) {
        is TabletCommand.SetLaserPower ->
            frame(CMD_SET_LASER_POWER, byteArrayOf(command.duty.coerceIn(0, 100).toByte()))
        is TabletCommand.SetProjectionMode ->
            frame(CMD_SET_PROJECTION_MODE, byteArrayOf(command.mode.coerceIn(0, 2).toByte()))
        TabletCommand.BeginTagSearch ->
            frame(CMD_BEGIN_TAG_SEARCH, byteArrayOf())
        is TabletCommand.BeginSurvey ->
            frame(CMD_BEGIN_SURVEY, idList(command.nodeIds))
        is TabletCommand.BeginProjection ->
            frame(CMD_BEGIN_PROJECTION, idList(command.nodeIds))
        is TabletCommand.TagFound ->
            frame(CMD_TAG_FOUND, u16(command.tagId))
        is TabletCommand.NudgeBeamX ->
            frame(CMD_NUDGE_BEAM_X, f32(command.dx))
        is TabletCommand.NudgeBeamY ->
            frame(CMD_NUDGE_BEAM_Y, f32(command.dy))
        is TabletCommand.SetOrigin ->
            frame(CMD_SET_ORIGIN, pose(command.origin))
        is TabletCommand.RegisterNodes ->
            frame(CMD_REGISTER_NODES, nodes(command.planNodes))
    }

    fun frame(cmd: Int, payload: ByteArray): ByteArray {
        val cmdByte = cmd and 0xFF
        require(cmdByte != DeviceLinkConfig.PAIRING_CMD) {
            "cmd 0xE5 is reserved for dongle pairing"
        }
        require(payload.size <= DeviceLinkConfig.MAX_PAYLOAD) {
            "frame ${DeviceLinkConfig.HEADER_SIZE + payload.size} exceeds ${DeviceLinkConfig.MAX_FRAME_BYTES} bytes"
        }
        val out = ByteArray(DeviceLinkConfig.HEADER_SIZE + payload.size)
        out[0] = cmdByte.toByte()
        out[1] = (payload.size and 0xFF).toByte()
        out[2] = ((payload.size ushr 8) and 0xFF).toByte()
        if (payload.isNotEmpty()) payload.copyInto(out, DeviceLinkConfig.HEADER_SIZE)
        return out
    }

    private fun idList(ids: List<String>): ByteArray {
        val buf = leBuffer(2 + 2 * ids.size)
        buf.putShort(ids.size.toShort())
        ids.forEach { buf.putShort(NodeIds.toWire(it).toShort()) }
        return buf.array()
    }

    private fun nodes(plan: List<NodePacket>): ByteArray {
        val buf = leBuffer(2 + 18 * plan.size)
        buf.putShort(plan.size.toShort())
        plan.forEach { node ->
            buf.putShort(NodeIds.toWire(node.id).toShort())
            buf.putFloat(node.eastMm)
            buf.putFloat(node.northMm)
            buf.putFloat(node.cornerInnerAngleRad)
            buf.putFloat(node.cornerOrientationRad)
        }
        return buf.array()
    }

    private fun pose(origin: Pose): ByteArray {
        val buf = leBuffer(24)
        buf.putFloat(origin.eastMm)
        buf.putFloat(origin.northMm)
        buf.putFloat(origin.upMm)
        buf.putFloat(origin.rollRad)
        buf.putFloat(origin.pitchRad)
        buf.putFloat(origin.yawRad)
        return buf.array()
    }

    private fun u16(value: Int): ByteArray {
        val buf = leBuffer(2)
        buf.putShort(value.toShort())
        return buf.array()
    }

    private fun f32(value: Float): ByteArray {
        val buf = leBuffer(4)
        buf.putFloat(value)
        return buf.array()
    }

    private fun leBuffer(size: Int): ByteBuffer =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
}
