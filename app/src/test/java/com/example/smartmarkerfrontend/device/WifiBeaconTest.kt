package com.example.smartmarkerfrontend.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class WifiBeaconTest {
    @Test
    fun hereReplyReturnsSender() {
        val data = "SMHERE".toByteArray(Charsets.US_ASCII)
        val host = WifiBeacon.replyHost(data, data.size, InetAddress.getByName("192.168.43.20"))
        assertEquals("192.168.43.20", host)
    }

    @Test
    fun otherPayloadIsIgnored() {
        val data = "SMFIND".toByteArray(Charsets.US_ASCII)
        assertNull(WifiBeacon.replyHost(data, data.size, InetAddress.getByName("192.168.43.20")))
    }
}
