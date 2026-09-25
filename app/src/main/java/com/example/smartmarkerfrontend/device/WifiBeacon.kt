package com.example.smartmarkerfrontend.device

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Finds the CCU on the phone hotspot.
 *
 * The tablet broadcasts [DeviceLinkConfig.WIFI_FIND] to UDP
 * [DeviceLinkConfig.WIFI_BEACON_PORT]. The CCU answers
 * [DeviceLinkConfig.WIFI_HERE] from its own address.
 */
object WifiBeacon {
    fun replyHost(data: ByteArray, length: Int, from: InetAddress): String? {
        if (from !is Inet4Address) return null
        if (length != DeviceLinkConfig.WIFI_HERE.length) return null
        val text = String(data, 0, length, Charsets.US_ASCII)
        if (text != DeviceLinkConfig.WIFI_HERE) return null
        return from.hostAddress
    }

    fun find(timeoutMs: Int = 800): String? {
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.broadcast = true
            socket.soTimeout = 200
            socket.bind(InetSocketAddress(0))
            val payload = DeviceLinkConfig.WIFI_FIND.toByteArray(Charsets.US_ASCII)
            val targets = broadcastTargets()
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            val buf = ByteArray(32)
            while (System.nanoTime() < deadline) {
                for (target in targets) {
                    val packet = DatagramPacket(
                        payload,
                        payload.size,
                        target,
                        DeviceLinkConfig.WIFI_BEACON_PORT
                    )
                    runCatching { socket.send(packet) }
                }
                val wait = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(wait)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                replyHost(wait.data, wait.length, wait.address)?.let { return it }
            }
        }
        return null
    }

    private fun broadcastTargets(): List<InetAddress> {
        val found = LinkedHashSet<String>()
        val out = ArrayList<InetAddress>()
        fun add(addr: InetAddress?) {
            if (addr !is Inet4Address) return
            val host = addr.hostAddress ?: return
            if (found.add(host)) out += addr
        }
        add(InetAddress.getByName("255.255.255.255"))
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return out
        for (nif in ifaces) {
            if (!nif.isUp || nif.isLoopback) continue
            for (iface in nif.interfaceAddresses) add(iface.broadcast)
        }
        return out
    }
}
