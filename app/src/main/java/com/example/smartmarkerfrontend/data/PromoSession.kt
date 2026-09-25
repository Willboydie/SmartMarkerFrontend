package com.example.smartmarkerfrontend.data

/**
 * Promo session commands. Pose comes only from telemetry `originIsSet`.
 * A `moved` bit does not clear it.
 */
class PromoSession {
    var posed: Boolean = false
        private set

    var sessionId: String = ""
        private set

    private var liveDeviceId: String = ""
    private var liveSessionId: String = ""
    private var lastTelemetryMs: Long = 0L

    fun setOrigin(): TabletCommand = TabletCommand.BeginOriginSet

    fun onTelemetry(originIsSet: Boolean, moved: Boolean) {
        posed = originIsSet
        if (moved) posed = originIsSet
    }

    /**
     * Adopt the first telemetry ids, then ignore a frame for a different session.
     * A matching frame refreshes the 5 s CONNECTED window.
     */
    fun acceptTelemetry(
        deviceId: String,
        sessionId: String,
        originIsSet: Boolean,
        moved: Boolean,
        nowMs: Long
    ): Boolean {
        if (liveSessionId.isEmpty()) {
            if (deviceId.isEmpty() || sessionId.isEmpty()) return false
            liveDeviceId = deviceId
            liveSessionId = sessionId
            this.sessionId = sessionId
        }
        if (deviceId != liveDeviceId || sessionId != liveSessionId) return false
        lastTelemetryMs = nowMs
        onTelemetry(originIsSet, moved)
        return true
    }

    fun connectedDue(nowMs: Long): Boolean =
        liveSessionId.isNotEmpty() && nowMs - lastTelemetryMs < 5_000L

    fun noteAdvertised(sessionId: String) {
        this.sessionId = sessionId
    }

    fun startSession(): TabletCommand = TabletCommand.BeginNewSession

    fun joinSession(): TabletCommand = TabletCommand.JoinSession(sessionId)

    fun endSession(): TabletCommand {
        val command = TabletCommand.EndSession(sessionId)
        posed = false
        sessionId = ""
        liveDeviceId = ""
        liveSessionId = ""
        lastTelemetryMs = 0L
        return command
    }
}

/** Missing origin stays on the plan. There is no device-moved banner. */
fun planBanner(posed: Boolean): String? =
    if (posed) null
    else "Origin not set — Set Origin on the Pose screen before surveying or projecting."
