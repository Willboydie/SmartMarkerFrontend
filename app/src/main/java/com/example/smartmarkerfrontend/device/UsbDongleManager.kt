package com.example.smartmarkerfrontend.device

import android.app.Application
import android.content.Context
import android.hardware.usb.UsbManager
import com.example.smartmarkerfrontend.data.TabletCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * USB-C host hook for the ESP32 NOW dongle. Detection is best-effort; the
 * UI can still toggle a simulated link until serial framing is wired.
 */
class UsbDongleManager(app: Application) {
    private val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val _attached = MutableStateFlow(hasDongle())
    val attached: StateFlow<Boolean> = _attached.asStateFlow()

    fun refresh() {
        _attached.value = hasDongle()
    }

    fun send(command: TabletCommand) {
        // Serial write lands here once the dongle framing is defined.
        command.toString()
    }

    private fun hasDongle(): Boolean {
        val known = setOf(0x10C4, 0x1A86, 0x0403, 0x303A, 0x067B)
        return usb.deviceList.values.any { it.vendorId in known }
    }
}
