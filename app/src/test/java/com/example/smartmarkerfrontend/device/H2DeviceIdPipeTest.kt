package com.example.smartmarkerfrontend.device

import com.example.smartmarkerfrontend.data.DeviceTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * H2.1. Reads one idle DEVICE_ID from ccu_h2_1 and parses it.
 * Skips unless scripts/h2-1-device-id.ps1 passes -Pccu.h2_1.file.
 */
class H2DeviceIdPipeTest {
    @Test(timeout = 60_000)
    fun idleDeviceIdParsesAsSmartMarker() {
        val marker = System.getProperty("ccu.h2_1.file")
        assumeTrue("H2.1 runs from scripts/h2-1-device-id.ps1", !marker.isNullOrBlank())
        val exe = File(checkNotNull(marker)).readText(StandardCharsets.UTF_8).trim()
        assertTrue("missing $exe", File(exe).isFile)

        val proc = ProcessBuilder(exe).redirectErrorStream(true).start()
        try {
            val reader = proc.inputStream.bufferedReader(StandardCharsets.US_ASCII)
            val portLine = reader.readLine() ?: error("ccu_h2_1 produced no port line")
            assertTrue(portLine, portLine.startsWith("H2.1 PORT "))
            val port = portLine.removePrefix("H2.1 PORT ").trim().toInt()

            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5_000
                val input = socket.getInputStream()
                val parser = SerialFrameParser()
                val frames = ArrayList<DeviceTelemetry>()
                val raw = ArrayList<Byte>()
                val buf = ByteArray(32)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    for (i in 0 until n) raw.add(buf[i])
                    frames.addAll(parser.feed(buf, 0, n))
                }
                socket.getOutputStream().apply {
                    write(raw.toByteArray())
                    flush()
                }
                assertEquals(1, frames.size)
                val msg = frames.single() as DeviceTelemetry.DeviceId
                assertEquals("sm-ccu", msg.deviceId)
                assertEquals("Smart Marker", msg.displayName)
                assertFalse(msg.sessionActive)
                assertEquals("", msg.sessionId)
            }

            val rest = reader.readText()
            assertEquals(rest, 0, proc.waitFor())
            assertTrue(rest, rest.contains("H2.1 PASS"))
        } finally {
            proc.destroy()
        }
    }
}
