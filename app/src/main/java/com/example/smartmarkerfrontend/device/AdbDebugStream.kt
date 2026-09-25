package com.example.smartmarkerfrontend.device

import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Raw protocol bytes over the USB-C debugging cable.
 *
 * The PC applet listens on [DeviceLinkConfig.ADB_PORT]; `adb reverse` forwards
 * the tablet's localhost socket across USB. No Wi-Fi, no ESP-NOW hop.
 */
class AdbDebugStream(
    private val socket: Socket,
    override val name: String
) : DeviceStream {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override fun read(buffer: ByteArray): Int {
        return try {
            val n = input.read(buffer)
            n
        } catch (_: SocketTimeoutException) {
            0
        }
    }

    override fun writeFully(bytes: ByteArray) {
        synchronized(output) {
            output.write(bytes)
            output.flush()
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        fun connect(
            host: String = DeviceLinkConfig.ADB_HOST,
            port: Int = DeviceLinkConfig.ADB_PORT,
            connectTimeoutMs: Int = 1500,
            streamName: String = "adb"
        ): AdbDebugStream {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.soTimeout = 400
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            return AdbDebugStream(socket, streamName)
        }
    }
}
