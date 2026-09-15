package com.example.smartmarkerfrontend.data

/**
 * Tablet ↔ ESP32 NOW dongle contract from docs/protocol.md.
 * The UI currently simulates these exchanges; UsbDongleManager is the hook
 * for the USB-C serial link (peak ~10 KB/s).
 *
 * Angles on the wire are radians in [0, 2π).
 */
data class Pose(
    val eastMm: Float,
    val northMm: Float,
    val upMm: Float,
    val rollRad: Float,
    val pitchRad: Float,
    val yawRad: Float
)

enum class ConnectionStatus { Linked, Searching, Lost }

enum class ProjectionMode { Off, Survey, Project }

data class NodePacket(
    val id: String,
    val eastMm: Float,
    val northMm: Float,
    val cornerInnerAngleRad: Float,
    val cornerOrientationRad: Float
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

    /** One result for a requested survey vertex. Sent once per id in `BEGIN_SURVEY`. */
    data class SurveyPoint(
        val nodeId: String,
        val success: Boolean,
        val pose: Pose
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
