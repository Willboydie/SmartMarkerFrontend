package com.example.smartmarkerfrontend.device

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import java.io.IOException

/**
 * USB host CDC (or vendor UART) pipe for the ESP-NOW / Wi-Fi dongle.
 *
 * Hosting rules from docs/WIFI_DONGLE_HOSTING.md: 921600 8N1, DTR set, RTS clear.
 * raw binary frames. Same [DeviceStream] contract as [AdbDebugStream].
 */
class UsbHostStream(
    private val connection: UsbDeviceConnection,
    private val claimed: List<UsbInterface>,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint
) : DeviceStream {
    @Volatile private var closed = false

    override val name: String = "usb"

    override fun read(buffer: ByteArray): Int {
        if (closed) return -1
        val n = connection.bulkTransfer(inEp, buffer, buffer.size, 200)
        if (closed) return -1
        if (n < 0) return 0
        return n
    }

    override fun writeFully(bytes: ByteArray) {
        if (closed) throw IOException("USB stream closed")
        var offset = 0
        while (offset < bytes.size) {
            val n = connection.bulkTransfer(
                outEp,
                bytes,
                offset,
                bytes.size - offset,
                1000
            )
            if (n <= 0) throw IOException("USB bulk write failed ($n)")
            offset += n
        }
        val mps = outEp.maxPacketSize
        if (mps > 0 && bytes.isNotEmpty() && bytes.size % mps == 0) {
            connection.bulkTransfer(outEp, ByteArray(0), 0, 0, 200)
        }
    }

    override fun close() {
        closed = true
        claimed.forEach { runCatching { connection.releaseInterface(it) } }
        runCatching { connection.close() }
    }

    companion object {
        private const val TAG = "SmartMarkerUsb"
        private const val CDC_SET_LINE_CODING = 0x20
        private const val CDC_SET_CONTROL_LINE_STATE = 0x22
        private const val CDC_REQTYPE_HOST_TO_DEVICE = 0x21

        fun open(usb: UsbManager, device: UsbDevice): UsbHostStream {
            val pipe = findSerialPipe(device)
                ?: throw IOException("No bulk IN/OUT pair on USB device")
            val connection = usb.openDevice(device)
                ?: throw IOException("Could not open USB device")
            val claimed = ArrayList<UsbInterface>()
            try {
                claim(connection, pipe.commIntf, claimed)
                claim(connection, pipe.dataIntf, claimed)
                if (pipe.dataIntf.alternateSetting != 0) {
                    if (!connection.setInterface(pipe.dataIntf)) {
                        throw IOException("Could not select USB data alternate setting")
                    }
                }
                armCdc(connection, pipe.commIntf ?: pipe.dataIntf)
                Log.i(
                    TAG,
                    "CDC open vid=0x${device.vendorId.toString(16)} " +
                        "pid=0x${device.productId.toString(16)} " +
                        "dataIf=${pipe.dataIntf.id} baud=${DeviceLinkConfig.CDC_BAUD} DTR=1 RTS=0"
                )
                return UsbHostStream(connection, claimed, pipe.inEp, pipe.outEp)
            } catch (t: Throwable) {
                claimed.forEach { runCatching { connection.releaseInterface(it) } }
                runCatching { connection.close() }
                throw t
            }
        }

        private fun claim(
            connection: UsbDeviceConnection,
            intf: UsbInterface?,
            claimed: MutableList<UsbInterface>
        ) {
            if (intf == null) return
            if (claimed.any { it.id == intf.id && it.alternateSetting == intf.alternateSetting }) {
                return
            }
            if (!connection.claimInterface(intf, true)) {
                throw IOException("Could not claim USB interface ${intf.id}")
            }
            claimed += intf
        }

        private fun findSerialPipe(device: UsbDevice): SerialPipe? {
            val interfaces = (0 until device.interfaceCount).map { device.getInterface(it) }
            val cdcData = interfaces.firstOrNull { intf ->
                intf.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA &&
                    bulkPair(intf) != null
            }
            if (cdcData != null) {
                val pair = bulkPair(cdcData) ?: return null
                val comm = interfaces.firstOrNull {
                    it.interfaceClass == UsbConstants.USB_CLASS_COMM
                }
                return SerialPipe(cdcData, comm, pair.first, pair.second)
            }
            for (intf in interfaces) {
                val pair = bulkPair(intf) ?: continue
                return SerialPipe(intf, null, pair.first, pair.second)
            }
            return null
        }

        private fun bulkPair(intf: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
            var inp: UsbEndpoint? = null
            var out: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) inp = ep
                else out = ep
            }
            return if (inp != null && out != null) inp to out else null
        }

        private fun armCdc(connection: UsbDeviceConnection, comm: UsbInterface) {
            val index = comm.id
            val lineCoding = DeviceLinkConfig.cdcLineCoding()
            connection.controlTransfer(
                CDC_REQTYPE_HOST_TO_DEVICE,
                CDC_SET_LINE_CODING,
                0,
                index,
                lineCoding,
                lineCoding.size,
                500
            )
            connection.controlTransfer(
                CDC_REQTYPE_HOST_TO_DEVICE,
                CDC_SET_CONTROL_LINE_STATE,
                DeviceLinkConfig.CDC_LINE_STATE_DTR,
                index,
                null,
                0,
                500
            )
        }
    }
}

private data class SerialPipe(
    val dataIntf: UsbInterface,
    val commIntf: UsbInterface?,
    val inEp: UsbEndpoint,
    val outEp: UsbEndpoint
)
