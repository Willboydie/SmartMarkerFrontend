"""PC host for the ESP-NOW USB CDC dongle (docs/WIFI_DONGLE_HOSTING.md).

Open the ESP32-C3 SuperMini as raw CDC: 921600 8N1, DTR/RTS idle so the chip
is not held in reset, binary frames only. Command byte 0xE5 is pairing and
must not be written by a host.
"""

from __future__ import annotations

import time

from protocol import CDC_BAUD

ESPRESSIF_VID = 0x303A
ESPRESSIF_PID = 0x1001


def open_cdc_serial(port: str, baud: int = CDC_BAUD, *, timeout: float = 0.05):
    """Open a USB CDC port without asserting RTS/DTR (ESP32 reset lines)."""
    import serial

    ser = serial.Serial()
    ser.port = port
    ser.baudrate = baud
    ser.bytesize = serial.EIGHTBITS
    ser.parity = serial.PARITY_NONE
    ser.stopbits = serial.STOPBITS_ONE
    ser.timeout = timeout
    ser.write_timeout = 1.0
    ser.dsrdtr = False
    ser.rtscts = False
    ser.dtr = False
    ser.rts = False
    ser.open()
    # pyserial / the OS CDC driver may pulse DTR/RTS during open.
    ser.dtr = False
    ser.rts = False
    time.sleep(0.05)
    return ser
