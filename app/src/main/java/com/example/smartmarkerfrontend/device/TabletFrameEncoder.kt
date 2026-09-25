package com.example.smartmarkerfrontend.device

import com.example.smartmarkerfrontend.data.DeviceCmd
import com.example.smartmarkerfrontend.data.PlanNode
import com.example.smartmarkerfrontend.data.TabletCommand
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Packs tablet → device frames. Returns null when the frame must be dropped. */
object TabletFrameEncoder {
    fun encode(command: TabletCommand): ByteArray? = when (command) {
        is TabletCommand.Connected -> stringFrame(DeviceCmd.CONNECTED, command.tabletId)
        TabletCommand.BeginNewSession -> frame(DeviceCmd.BEGIN_NEW_SESSION, byteArrayOf())
        is TabletCommand.JoinSession -> stringFrame(DeviceCmd.JOIN_SESSION, command.sessionId)
        is TabletCommand.EndSession -> stringFrame(DeviceCmd.END_SESSION, command.sessionId)
        TabletCommand.BeginOriginSet -> frame(DeviceCmd.BEGIN_ORIGIN_SET, byteArrayOf())
        TabletCommand.BeginTagFinding -> frame(DeviceCmd.BEGIN_TAG_FINDING, byteArrayOf())
        is TabletCommand.PerformSurvey -> planFrame(DeviceCmd.PERFORM_SURVEY, command.nodes)
        TabletCommand.AbortSurvey -> frame(DeviceCmd.ABORT_SURVEY, byteArrayOf())
        is TabletCommand.PerformProjection -> planFrame(DeviceCmd.PERFORM_PROJECTION, command.nodes)
        TabletCommand.ProjectorPause -> frame(DeviceCmd.PROJECTOR_PAUSE, byteArrayOf())
        TabletCommand.ProjectorPlay -> frame(DeviceCmd.PROJECTOR_PLAY, byteArrayOf())
        TabletCommand.ProjectionEnd -> frame(DeviceCmd.PROJECTION_END, byteArrayOf())
        is TabletCommand.SetLaserPower -> {
            if (command.duty !in DeviceCmd.LASER_DUTY_MIN..DeviceCmd.LASER_DUTY_MAX) null
            else frame(DeviceCmd.SET_LASER_POWER, byteArrayOf(command.duty.toByte()))
        }
        is TabletCommand.NudgeX -> nudge(DeviceCmd.NUDGE_X, command.dx)
        is TabletCommand.NudgeY -> nudge(DeviceCmd.NUDGE_Y, command.dy)
        is TabletCommand.TagAligned -> stringFrame(DeviceCmd.TAG_ALIGNED, command.tagId)
    }

    fun frame(cmd: Int, payload: ByteArray): ByteArray? {
        val cmdByte = cmd and 0xFF
        if (cmdByte in DeviceCmd.RESERVED || cmdByte !in DeviceCmd.KNOWN) return null
        if (payload.size > DeviceLinkConfig.MAX_PAYLOAD) return null
        val out = ByteArray(DeviceLinkConfig.HEADER_SIZE + payload.size)
        out[0] = cmdByte.toByte()
        out[1] = (payload.size and 0xFF).toByte()
        out[2] = ((payload.size ushr 8) and 0xFF).toByte()
        if (payload.isNotEmpty()) payload.copyInto(out, DeviceLinkConfig.HEADER_SIZE)
        return out
    }

    private fun stringFrame(cmd: Int, text: String): ByteArray? {
        val raw = text.encodeToByteArray()
        if (raw.size > DeviceCmd.STRING_MAX) return null
        val payload = ByteArray(1 + raw.size)
        payload[0] = raw.size.toByte()
        raw.copyInto(payload, 1)
        return frame(cmd, payload)
    }

    private fun planFrame(cmd: Int, nodes: List<PlanNode>): ByteArray? {
        if (nodes.size > 0xFFFF) return null
        val size = 2 + DeviceCmd.PLAN_NODE_BYTES * nodes.size
        if (size > 0xFFFF) return null
        val buf = leBuffer(size)
        buf.putShort(nodes.size.toShort())
        for (node in nodes) buf.putPlanNode(node)
        return frame(cmd, buf.array())
    }

    private fun nudge(cmd: Int, delta: Int): ByteArray? {
        if (delta !in Short.MIN_VALUE.toInt()..Short.MAX_VALUE.toInt()) return null
        val buf = leBuffer(2)
        buf.putShort(delta.toShort())
        return frame(cmd, buf.array())
    }

    private fun leBuffer(size: Int): ByteBuffer =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
}

private fun ByteBuffer.putPlanNode(node: PlanNode) {
    putShort(NodeIds.toWire(node.id).toShort())
    putFloat(node.eastM)
    putFloat(node.northM)
    putFloat(node.cornerInnerAngleRad)
    putFloat(node.cornerOrientationRad)
}
