package com.example.smartmarkerfrontend.device

import com.example.smartmarkerfrontend.data.DeviceTelemetry

/**
 * Tablet side of the dongle USB hop.
 * [openingFrame] is host-local `DONGLE_RESET` and is not a CCU protocol frame.
 * [feed] consumes a preceding `DONGLE_STATUS` and returns only device frames.
 */
class HostDongleLink {
    private val parser = SerialFrameParser()

    fun openingFrame(): ByteArray = byteArrayOf(0xE4.toByte(), 0x00, 0x00)

    fun reset() {
        parser.reset()
    }

    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size): List<DeviceTelemetry> =
        parser.feed(data, offset, length)
}
