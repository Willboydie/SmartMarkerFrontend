package com.example.smartmarkerfrontend.device

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * H2.0. Reads 16 raw bytes from ccu_h2_0 and writes them back.
 * Skips unless scripts/h2-0-bytes.ps1 passes -Pccu.h2_0.file.
 *
 * Payload: 10 01 02 03 00 FF 7F 80 E4 E5 E6 0A 0D 20 41 00
 */
class H2BytePipeTest {
    @Test(timeout = 60_000)
    fun ccuBytesArriveUnchanged() {
        val marker = System.getProperty("ccu.h2_0.file")
        assumeTrue("H2.0 runs from scripts/h2-0-bytes.ps1", !marker.isNullOrBlank())
        val exe = File(checkNotNull(marker)).readText(StandardCharsets.UTF_8).trim()
        assertTrue("missing $exe", File(exe).isFile)

        val proc = ProcessBuilder(exe).redirectErrorStream(true).start()
        try {
            val reader = proc.inputStream.bufferedReader(StandardCharsets.US_ASCII)
            val portLine = reader.readLine() ?: error("ccu_h2_0 produced no port line")
            assertTrue(portLine, portLine.startsWith("H2.0 PORT "))
            val port = portLine.removePrefix("H2.0 PORT ").trim().toInt()

            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5_000
                val got = readExact(socket.getInputStream(), PAYLOAD.size)
                assertArrayEquals(PAYLOAD, got)
                socket.getOutputStream().apply {
                    write(got)
                    flush()
                }
            }

            val rest = reader.readText()
            assertEquals(rest, 0, proc.waitFor())
            assertTrue(rest, rest.contains("H2.0 PASS"))
        } finally {
            proc.destroy()
        }
    }

    private fun readExact(input: InputStream, n: Int): ByteArray {
        val got = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(got, off, n - off)
            if (r < 0) error("stream ended after $off bytes")
            off += r
        }
        return got
    }

    companion object {
        private val PAYLOAD = byteArrayOf(
            0x10, 0x01, 0x02, 0x03, 0x00, 0xFF.toByte(), 0x7F, 0x80.toByte(),
            0xE4.toByte(), 0xE5.toByte(), 0xE6.toByte(), 0x0A, 0x0D, 0x20, 0x41, 0x00
        )
    }
}
