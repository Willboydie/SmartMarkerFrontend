package com.example.smartmarkerfrontend.device

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.smartmarkerfrontend.data.DeviceTelemetry
import com.example.smartmarkerfrontend.data.TabletCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the live byte pipe and [SerialFrameParser].
 *
 * The tablet joins the same phone hotspot as the Pi, finds it with
 * [WifiBeacon], and opens a TCP socket on [DeviceLinkConfig.WIFI_PORT].
 */
class UsbDongleManager(
    private val app: Application,
    private val scope: CoroutineScope
) {
    private val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val link = HostDongleLink()
    private val writeLock = Any()

    private val _attached = MutableStateFlow(hasDongle())
    val attached: StateFlow<Boolean> = _attached.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _transport = MutableStateFlow("none")
    val transport: StateFlow<String> = _transport.asStateFlow()

    private val _telemetry = MutableSharedFlow<DeviceTelemetry>(
        replay = 8,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val telemetry: SharedFlow<DeviceTelemetry> = _telemetry.asSharedFlow()

    var autoReconnect: Boolean = true

    @Volatile private var wantSession = false
    @Volatile private var stream: DeviceStream? = null
    @Volatile private var wifiHost: String? = null
    private var loopJob: Job? = null
    private var receiverRegistered = false

    fun start() {
        registerUsbReceiver()
        refresh()
        wantSession = true
        if (loopJob?.isActive == true) return
        loopJob = scope.launch(Dispatchers.IO) { connectLoop() }
    }

    fun stop() {
        wantSession = false
        runCatching { stream?.close() }
        loopJob?.cancel()
        loopJob = null
        _connected.value = false
        _transport.value = "none"
        stream = null
    }

    fun refresh() {
        _attached.value = hasDongle()
        val dongle = findDongle()
        if (dongle != null && !usb.hasPermission(dongle)) {
            requestPermission(dongle)
        }
        val current = stream
        if (current?.name == "adb" && dongle != null && usb.hasPermission(dongle)) {
            runCatching { current.close() }
        }
    }

    fun send(vararg commands: TabletCommand) {
        if (commands.isEmpty()) return
        val frames = commands.mapNotNull { command ->
            runCatching { TabletFrameEncoder.encode(command) }
                .onFailure { Log.w(TAG, "encode failed", it) }
                .getOrNull()
        }
        if (frames.isEmpty()) return
        val pipe = stream ?: return
        scope.launch(Dispatchers.IO) {
            synchronized(writeLock) {
                frames.forEach { bytes ->
                    runCatching { pipe.writeFully(bytes) }
                        .onFailure { Log.w(TAG, "write failed", it) }
                }
            }
        }
    }

    fun release() {
        stop()
        if (receiverRegistered) {
            runCatching { app.unregisterReceiver(usbReceiver) }
            receiverRegistered = false
        }
    }

    private suspend fun connectLoop() {
        while (scope.isActive && wantSession) {
            val pipe = openPreferredStream()
            if (pipe == null) {
                delay(500)
                continue
            }
            stream = pipe
            link.reset()
            _transport.value = pipe.name
            _connected.value = true
            Log.i(TAG, "session up (${pipe.name})")
            try {
                if (pipe.name != "wifi") pipe.writeFully(link.openingFrame())
                readLoop(pipe)
            } catch (t: Throwable) {
                Log.w(TAG, "session dropped (${pipe.name})", t)
            } finally {
                _connected.value = false
                _transport.value = "none"
                if (stream === pipe) stream = null
                runCatching { pipe.close() }
            }
            if (!wantSession) break
            if (!autoReconnect) {
                wantSession = false
                break
            }
            delay(400)
        }
    }

    private suspend fun readLoop(pipe: DeviceStream) = withContext(Dispatchers.IO) {
        val buf = ByteArray(512)
        while (scope.isActive && wantSession) {
            if (pipe.name == "adb" && usbDongleReady()) break
            val n = pipe.read(buf)
            if (n < 0) break
            if (n == 0) continue
            val frames = synchronized(link) { link.feed(buf, 0, n) }
            frames.forEach { _telemetry.tryEmit(it) }
        }
    }

    private fun openWifi(host: String): DeviceStream? {
        return runCatching {
            AdbDebugStream.connect(
                host = host,
                port = DeviceLinkConfig.WIFI_PORT,
                streamName = "wifi"
            )
        }.onSuccess { wifiHost = host }
            .onFailure {
                Log.i(TAG, "wifi $host not up")
                wifiHost = null
            }
            .getOrNull()
    }

    private fun openPreferredStream(): DeviceStream? {
        wifiHost?.let { cached ->
            openWifi(cached)?.let { return it }
        }
        val found = WifiBeacon.find()
        if (found == null) {
            Log.i(TAG, "wifi beacon: no marker")
            return null
        }
        return openWifi(found)
    }

    private fun usbDongleReady(): Boolean {
        val dongle = findDongle() ?: return false
        return usb.hasPermission(dongle)
    }

    private fun hasDongle(): Boolean = findDongle() != null

    private fun findDongle(): UsbDevice? {
        val devices = usb.deviceList.values
        return devices.firstOrNull {
            it.vendorId == DeviceLinkConfig.ESPRESSIF_VID &&
                it.productId == DeviceLinkConfig.ESPRESSIF_PID
        } ?: devices.firstOrNull { it.vendorId in UART_BRIDGE_VIDS }
            ?: devices.firstOrNull { it.vendorId == DeviceLinkConfig.ESPRESSIF_VID }
    }

    private fun requestPermission(device: UsbDevice) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(app.packageName)
        val pending = PendingIntent.getBroadcast(app, 0, intent, flags)
        usb.requestPermission(device, pending)
    }

    private fun registerUsbReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(app, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION,
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> refresh()
            }
            val device = intent.getParcelableExtraCompat<UsbDevice>(UsbManager.EXTRA_DEVICE)
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED && device != null) {
                if (stream?.name == "usb") runCatching { stream?.close() }
            }
        }
    }

    companion object {
        private const val TAG = "SmartMarkerUsb"
        const val ACTION_USB_PERMISSION =
            "com.example.smartmarkerfrontend.USB_PERMISSION"
        private val UART_BRIDGE_VIDS = setOf(0x10C4, 0x1A86, 0x0403, 0x067B)
    }
}

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(key: String): T? {
    return if (Build.VERSION.SDK_INT >= 33) {
        getParcelableExtra(key, T::class.java)
    } else {
        getParcelableExtra(key)
    }
}
