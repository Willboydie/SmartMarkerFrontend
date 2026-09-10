package com.example.smartmarkerfrontend.data

/**
 * Tablet ↔ ESP32 NOW dongle contract from docs/data_packets.txt.
 * The UI currently simulates these exchanges; UsbDongleManager is the hook
 * for the USB-C serial link (peak ~10 KB/s).
 */
data class Pose(
    val eastMm: Float,
    val northMm: Float,
    val yawDeg: Float
)

enum class ConnectionStatus { Linked, Searching, Lost }

enum class ProjectionMode { Off, Survey, Mark }

data class NodePacket(
    val id: String,
    val eastMm: Float,
    val northMm: Float
)

sealed interface DeviceTelemetry {
    data class Handshake(val firmwareVersion: String) : DeviceTelemetry

    data class Regular(
        val healthOk: Boolean,
        val effectiveYaw: Float,
        val moved: Boolean,
        val lastKnownPose: Pose?,
        val connectionStatus: ConnectionStatus,
        val originIsSet: Boolean,
        val isSurveying: Boolean,
        val surveyReady: Boolean,
        val isProjecting: Boolean,
        val projectionMode: ProjectionMode,
        val laserOn: Boolean,
        val laserPower: Int
    ) : DeviceTelemetry

    data class SurveyComplete(
        val surveyedNodes: List<String>,
        val surveyedNodePositions: List<Pose>,
        val nodesOutwithFov: List<String>
    ) : DeviceTelemetry

    data class ProjectionStart(
        val projectedNodes: List<NodePacket>
    ) : DeviceTelemetry
}

sealed interface TabletCommand {
    data class SetLaserPower(val duty: Int) : TabletCommand
    data class SetProjectionMode(val mode: Int) : TabletCommand
    data object BeginTagSearch : TabletCommand
    data class BeginSurvey(val nodeIds: List<String>) : TabletCommand
    data class BeginProjection(val nodeIds: List<String>) : TabletCommand
    data class TagFound(val tagId: Int) : TabletCommand
    data class NudgeBeamX(val dx: Float) : TabletCommand
    data class NudgeBeamY(val dy: Float) : TabletCommand
    data class SetOrigin(val origin: Pose) : TabletCommand
    data class RegisterNodes(val planNodes: List<NodePacket>) : TabletCommand
}
