package com.example.smartmarkerfrontend.data

/**
 * Promo tablet link. Frame layout matches the CCU codec in ccu/protocol.hpp
 * and docs/CMD_PROTOCOL.md: cmd u8, length u16 little-endian, then payload.
 */
data class Pose(
    val eastM: Float,
    val northM: Float,
    val upM: Float,
    val rollRad: Float,
    val pitchRad: Float,
    val yawRad: Float
)

data class PlanNode(
    val id: String,
    val eastM: Float,
    val northM: Float,
    val cornerInnerAngleRad: Float,
    val cornerOrientationRad: Float
)

data class NodeError(
    val id: Int,
    val errorCode: Int
)

object DeviceCmd {
    const val DEVICE_ID = 0x01
    const val PAIRING_ACCEPTED = 0x02
    const val REGULAR_TELEMETRY = 0x03
    const val SURVEY_RESULT = 0x04
    const val PROJECTION_READY = 0x05

    const val CONNECTED = 0x10

    const val BEGIN_NEW_SESSION = 0x20
    const val JOIN_SESSION = 0x21
    const val END_SESSION = 0x22
    const val BEGIN_ORIGIN_SET = 0x23
    const val BEGIN_TAG_FINDING = 0x24
    const val PERFORM_SURVEY = 0x25
    const val ABORT_SURVEY = 0x26
    const val PERFORM_PROJECTION = 0x27
    const val PROJECTOR_PAUSE = 0x28
    const val PROJECTOR_PLAY = 0x29
    const val PROJECTION_END = 0x2A
    const val SET_LASER_POWER = 0x2B
    const val NUDGE_X = 0x2C
    const val NUDGE_Y = 0x2D
    const val TAG_ALIGNED = 0x2E

    const val DONGLE_RESET = 0xE4
    const val DONGLE_PAIRING = 0xE5
    const val DONGLE_STATUS = 0xE6

    const val STRING_MAX = 32
    const val LASER_DUTY_MIN = 15
    const val LASER_DUTY_MAX = 99
    const val LASER_DUTY_DEFAULT = 30
    const val POSE_BYTES = 24
    const val PLAN_NODE_BYTES = 18
    const val SURVEY_RESULT_BYTES = 26

    val RESERVED = setOf(DONGLE_RESET, DONGLE_PAIRING, DONGLE_STATUS)

    val KNOWN = setOf(
        DEVICE_ID,
        PAIRING_ACCEPTED,
        REGULAR_TELEMETRY,
        SURVEY_RESULT,
        PROJECTION_READY,
        CONNECTED,
        BEGIN_NEW_SESSION,
        JOIN_SESSION,
        END_SESSION,
        BEGIN_ORIGIN_SET,
        BEGIN_TAG_FINDING,
        PERFORM_SURVEY,
        ABORT_SURVEY,
        PERFORM_PROJECTION,
        PROJECTOR_PAUSE,
        PROJECTOR_PLAY,
        PROJECTION_END,
        SET_LASER_POWER,
        NUDGE_X,
        NUDGE_Y,
        TAG_ALIGNED
    )
}

object SurveyStatus {
    const val IDLE = 0
    const val SCANNING = 1
}

object ProjectorStatus {
    const val IDLE = 0
    const val COMPUTING_PATH = 1
    const val PROJECTING = 2
}

object SurveyError {
    const val NONE = 0
    const val NOT_VISIBLE = 1
    const val OUT_OF_FOV = 2
    const val ABORTED = 3
}

object ProjectionError {
    const val OUT_OF_FOV = 2
    const val NOT_SURVEYED = 4
}

sealed interface DeviceTelemetry {
    data class DeviceId(
        val deviceId: String,
        val displayName: String,
        val sessionActive: Boolean,
        val sessionId: String
    ) : DeviceTelemetry

    data class PairingAccepted(val deviceId: String) : DeviceTelemetry

    data class Regular(
        val deviceId: String,
        val sessionId: String,
        val originIsSet: Boolean,
        val pose: Pose,
        val effectiveYaw: Float,
        val moved: Boolean,
        val originSetStatus: Int,
        val tagFindingStatus: Int,
        val surveyStatus: Int,
        val projectorStatus: Int,
        val projectorRunning: Boolean,
        val laserOn: Boolean,
        val laserPower: Int
    ) : DeviceTelemetry

    data class SurveyResult(
        val id: Int,
        val eastM: Float,
        val northM: Float,
        val upM: Float,
        val cornerInnerAngleRad: Float,
        val cornerOrientationRad: Float,
        val surveyed: Boolean,
        val success: Boolean,
        val errorCode: Int,
        val surveyFinished: Boolean
    ) : DeviceTelemetry

    data class ProjectionReady(
        val nodes: List<PlanNode>,
        val errors: List<NodeError>
    ) : DeviceTelemetry
}

sealed interface TabletCommand {
    data class Connected(val tabletId: String) : TabletCommand
    data object BeginNewSession : TabletCommand
    data class JoinSession(val sessionId: String) : TabletCommand
    data class EndSession(val sessionId: String) : TabletCommand
    data object BeginOriginSet : TabletCommand
    data object BeginTagFinding : TabletCommand
    data class PerformSurvey(val nodes: List<PlanNode>) : TabletCommand
    data object AbortSurvey : TabletCommand
    data class PerformProjection(val nodes: List<PlanNode>) : TabletCommand
    data object ProjectorPause : TabletCommand
    data object ProjectorPlay : TabletCommand
    data object ProjectionEnd : TabletCommand
    data class SetLaserPower(val duty: Int) : TabletCommand
    data class NudgeX(val dx: Int) : TabletCommand
    data class NudgeY(val dy: Int) : TabletCommand
    data class TagAligned(val tagId: String) : TabletCommand
}
