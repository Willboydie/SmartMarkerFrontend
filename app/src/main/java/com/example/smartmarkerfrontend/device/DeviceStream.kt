package com.example.smartmarkerfrontend.device

import java.io.Closeable

/**
 * Bidirectional byte pipe used by [SerialFrameParser].
 *
 * Today the USB-C debug cable is this stream over ADB TCP. The ESP-NOW /
 * Wi-Fi dongle uses the same interface once it enumerates as USB CDC
 * (docs/WIFI_DONGLE_HOSTING.md).
 */
interface DeviceStream : Closeable {
    val name: String

    /** Blocking read. `0` = timeout / no data; `-1` = stream closed. */
    fun read(buffer: ByteArray): Int

    fun writeFully(bytes: ByteArray)
}

/** USB CDC / frame limits for the ESP32-C3 SuperMini ESP-NOW bridge. */
object DeviceLinkConfig {
    const val ADB_HOST = "127.0.0.1"
    const val ADB_PORT = 8765

    /** Phone hotspot. The tablet broadcasts [WIFI_FIND] and opens TCP to the reply. */
    const val WIFI_PORT = 8765
    const val WIFI_BEACON_PORT = 8766
    const val WIFI_FIND = "SMFIND"
    const val WIFI_HERE = "SMHERE"

    const val HEADER_SIZE = 3
    const val MAX_FRAME_BYTES = 1024
    const val MAX_PAYLOAD = MAX_FRAME_BYTES - HEADER_SIZE

    /** Chip-to-chip pairing; not forwarded and must not be sent by a host. */
    const val PAIRING_CMD = 0xE5

    const val CDC_BAUD = 921_600
    const val ESPRESSIF_VID = 0x303A
    const val ESPRESSIF_PID = 0x1001

    /**
     * CDC ACM SET_CONTROL_LINE_STATE: DTR=1, RTS=0.
     * The dongle's USB CDC drops host-bound bytes while DTR is clear.
     * RTS stays clear so the line-state sequence does not enter the bootloader.
     */
    const val CDC_LINE_STATE_DTR = 1

    fun cdcLineCoding(baud: Int = CDC_BAUD): ByteArray = byteArrayOf(
        (baud and 0xFF).toByte(),
        ((baud ushr 8) and 0xFF).toByte(),
        ((baud ushr 16) and 0xFF).toByte(),
        ((baud ushr 24) and 0xFF).toByte(),
        0x00, // 1 stop bit
        0x00, // no parity
        0x08  // 8 data bits
    )
}
