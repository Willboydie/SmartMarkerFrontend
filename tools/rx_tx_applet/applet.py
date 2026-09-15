"""PC test applet: device-side RX/TX for the Smart Marker tablet.

Default path is the USB-C *debugging* cable: the tablet app connects to
localhost:8765, and `adb reverse` carries those bytes over USB. No UART
adapter and no ESP-NOW hop.

    adb reverse tcp:8765 tcp:8765
    python tools/rx_tx_applet/applet.py --adb

When the Wi-Fi / ESP-NOW dongle is plugged in, USB enumerates as CDC
(Espressif 303A:1001). Hosts must open 921600 8N1 with DTR=RTS=0 and send
raw binary frames (never cmd 0xE5). See docs/WIFI_DONGLE_HOSTING.md.

    python tools/rx_tx_applet/applet.py --port COM5 --baud 921600

Usage:
    python tools/rx_tx_applet/applet.py --adb
    python tools/rx_tx_applet/applet.py --adb --cli
    python tools/rx_tx_applet/applet.py --port COM5
    python tools/rx_tx_applet/test_protocol.py
"""

from __future__ import annotations

import argparse
import math
import queue
import socket
import sys
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from firmware import TELEMETRY_HZ, DeviceFirmware, Mode  # noqa: E402
from dongle_host import open_cdc_serial  # noqa: E402
from protocol import (  # noqa: E402
    TABLET_CMDS,
    BeginProjection,
    BeginSurvey,
    BeginTagSearch,
    Cmd,
    ConnectionStatus,
    DecodedFrame,
    FrameDecoder,
    NudgeBeamX,
    NudgeBeamY,
    Node,
    Pose,
    ProjectionMode,
    RegisterNodes,
    SetLaserPower,
    SetOrigin,
    SetProjectionMode,
    TagFound,
    decode_frame,
)

OFFLINE_PORT = "OFFLINE"
ADB_PORT_NAME = "ADB:8765"
DEFAULT_BAUD = 921600
DEFAULT_TCP_PORT = 8765
ONE_SHOT_TX = {Cmd.FIRMWARE_VERSION, Cmd.SURVEY_POINT, Cmd.PROJECTION_START}


def tcp_port_of(name: str) -> int | None:
    if name.startswith("ADB:") or name.startswith("tcp:") or name.startswith("TCP:"):
        return int(name.split(":")[-1])
    return None


def list_serial_ports() -> list[str]:
    try:
        from serial.tools import list_ports
    except ImportError:
        return []
    return [p.device for p in list_ports.comports()]


def sample_commands() -> list[tuple[str, bytes]]:
    nodes = (
        Node(1, 0.0, 0.0, math.pi / 2, 0.0),
        Node(2, 4000.0, 0.0, math.pi / 2, math.pi / 2),
        Node(3, 4000.0, 3000.0, math.pi / 2, math.pi),
        Node(7, 1200.0, 800.0, math.pi / 2, 0.4),
    )
    yaw = math.radians(37.0)
    return [
        ("SET_LASER_POWER 80", SetLaserPower(80).pack()),
        ("REGISTER_NODES (4 verts)", RegisterNodes(nodes).pack()),
        ("BEGIN_TAG_SEARCH", BeginTagSearch().pack()),
        ("NUDGE_BEAM_X +0.5°", NudgeBeamX(0.5).pack()),
        ("NUDGE_BEAM_Y -0.05°", NudgeBeamY(-0.05).pack()),
        ("TAG_FOUND 1", TagFound(1).pack()),
        ("TAG_FOUND 2", TagFound(2).pack()),
        (
            "Tag finding (2 tags)",
            BeginTagSearch().pack()
            + TagFound(1).pack()
            + BeginTagSearch().pack()
            + TagFound(2).pack(),
        ),
        ("SET_ORIGIN yaw=37° (optional)", SetOrigin(Pose(yaw_rad=yaw)).pack()),
        ("BEGIN_SURVEY [1,2,3,7]", BeginSurvey((1, 2, 3, 7)).pack()),
        ("BEGIN_PROJECTION [1,2,3]", BeginProjection((1, 2, 3)).pack()),
        ("SET_PROJECTION_MODE Off", SetProjectionMode(ProjectionMode.Off).pack()),
        ("REGISTER_NODES chunked", _chunked_register(nodes)),
    ]


def _chunked_register(nodes: tuple[Node, ...]) -> bytes:
    first = RegisterNodes((nodes[0],), flags=0x01).pack()
    last = RegisterNodes(nodes[1:], flags=0x02).pack()
    return first + last


class _SocketClient:
    def __init__(self, sock: socket.socket) -> None:
        sock.settimeout(0.05)
        self.sock = sock

    def read(self, n: int) -> bytes | None:
        try:
            data = self.sock.recv(n)
        except socket.timeout:
            return None
        return data

    def write(self, data: bytes) -> None:
        self.sock.sendall(data)

    def close(self) -> None:
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except Exception:
            pass
        try:
            self.sock.close()
        except Exception:
            pass


class Link:
    """Owns the firmware sim, optional serial/TCP pipe, and worker threads."""

    def __init__(self, events: queue.Queue[tuple]) -> None:
        self.events = events
        self.fw = DeviceFirmware()
        self.decoder = FrameDecoder(accept=TABLET_CMDS)
        self._lock = threading.Lock()
        self._client = None
        self._server: socket.socket | None = None
        self._ser = None
        self._stop = threading.Event()
        self._threads: list[threading.Thread] = []
        self.port = ADB_PORT_NAME
        self.baud = DEFAULT_BAUD
        self.rx_frames = 0
        self.tx_bytes = 0
        self.rx_bytes = 0
        self.last_hz = 0.0
        self._tick_times: list[float] = []
        self.peer = ""

    @property
    def connected(self) -> bool:
        return not self._stop.is_set() and bool(self._threads)

    @property
    def client_up(self) -> bool:
        return self._client is not None or self._ser is not None

    def start(self, port: str, baud: int) -> None:
        tcp = tcp_port_of(port)
        if tcp is not None:
            self._start_tcp(port, tcp)
            return
        self._start_serial(port, baud)

    def _start_serial(self, port: str, baud: int) -> None:
        self.stop()
        self.port = port
        self.baud = baud
        self.decoder.reset()
        self.rx_frames = 0
        self.tx_bytes = 0
        self.rx_bytes = 0
        self._tick_times.clear()
        self._stop.clear()
        self.peer = ""
        if port != OFFLINE_PORT:
            self._ser = open_cdc_serial(port, baud)
            self.peer = port
        else:
            self._ser = None
        now = time.monotonic()
        with self._lock:
            frames = self.fw.on_link_up(now)
        self._write_frames(frames)
        self._threads = [
            threading.Thread(target=self._tick_loop, name="sm-tick", daemon=True),
        ]
        if self._ser is not None:
            self._threads.append(
                threading.Thread(target=self._serial_rx_loop, name="sm-rx", daemon=True)
            )
        for t in self._threads:
            t.start()
        self._emit(
            "sys",
            f"session up on {port}"
            + (f" @ {baud} baud" if port != OFFLINE_PORT else " (no USB)"),
        )

    def _start_tcp(self, label: str, port: int) -> None:
        self.stop()
        self.port = label
        self.decoder.reset()
        self.rx_frames = 0
        self.tx_bytes = 0
        self.rx_bytes = 0
        self._tick_times.clear()
        self._stop.clear()
        self.peer = ""
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(("127.0.0.1", port))
        server.listen(1)
        server.settimeout(0.2)
        self._server = server
        self._threads = [
            threading.Thread(target=self._tick_loop, name="sm-tick", daemon=True),
            threading.Thread(target=self._accept_loop, name="sm-accept", daemon=True),
        ]
        for t in self._threads:
            t.start()
        self._emit(
            "sys",
            f"listening on 127.0.0.1:{port} — run: adb reverse tcp:{port} tcp:{port}",
        )

    def stop(self) -> None:
        self._stop.set()
        ser = self._ser
        self._ser = None
        if ser is not None:
            try:
                ser.close()
            except Exception:
                pass
        client = self._client
        self._client = None
        if client is not None:
            try:
                client.close()
            except Exception:
                pass
        server = self._server
        self._server = None
        if server is not None:
            try:
                server.close()
            except Exception:
                pass
        for t in self._threads:
            t.join(timeout=0.8)
        self._threads = []
        with self._lock:
            if self.fw.mode != Mode.UNLINKED:
                self.fw.on_link_down()
        self._stop = threading.Event()
        self.peer = ""

    def reset_device(self) -> None:
        port, baud = self.port, self.baud
        running = self.connected
        self.stop()
        self.fw = DeviceFirmware()
        self.decoder.reset()
        self._emit("sys", "firmware state reset")
        if running:
            self.start(port, baud)

    def inject(self, data: bytes) -> None:
        self._on_rx(data, source="inject")

    def snapshot(self):
        """Thread-safe copy of firmware flags for the UI."""
        with self._lock:
            return self.fw.snapshot()

    def operator(self, fn) -> None:
        now = time.monotonic()
        with self._lock:
            frames = fn(self.fw, now) or []
        self._write_frames(frames)

    def _tick_loop(self) -> None:
        while not self._stop.is_set():
            now = time.monotonic()
            with self._lock:
                frames = self.fw.tick(now)
            if frames:
                self._write_frames(frames)
                self._tick_times.append(now)
                cutoff = now - 1.0
                self._tick_times = [t for t in self._tick_times if t >= cutoff]
                self.last_hz = float(len(self._tick_times))
            time.sleep(0.01)

    def _accept_loop(self) -> None:
        while not self._stop.is_set():
            server = self._server
            if server is None:
                break
            try:
                conn, addr = server.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            self._serve_client(conn, addr)

    def _serve_client(self, conn: socket.socket, addr) -> None:
        client = _SocketClient(conn)
        self._client = client
        self.peer = f"{addr[0]}:{addr[1]}"
        self.decoder.reset()
        now = time.monotonic()
        with self._lock:
            frames = self.fw.on_link_up(now)
        self._write_frames(frames)
        self._emit("sys", f"tablet connected from {self.peer}")
        try:
            while not self._stop.is_set():
                chunk = client.read(512)
                if chunk is None:
                    continue
                if chunk == b"":
                    break
                self._on_rx(chunk, source="adb")
        except Exception as exc:
            self._emit("err", f"adb read failed: {exc}")
        finally:
            self._client = None
            self.peer = ""
            try:
                client.close()
            except Exception:
                pass
            with self._lock:
                if self.fw.mode != Mode.UNLINKED:
                    self.fw.on_link_down()
            self._emit("sys", "tablet disconnected — still listening")

    def _serial_rx_loop(self) -> None:
        ser = self._ser
        while not self._stop.is_set() and ser is not None:
            try:
                chunk = ser.read(512)
            except Exception as exc:
                self._emit("err", f"serial read failed: {exc}")
                break
            if chunk:
                self._on_rx(chunk, source="usb")

    def _on_rx(self, data: bytes, *, source: str) -> None:
        self.rx_bytes += len(data)
        with self._lock:
            frames = list(self.decoder.feed(data))
        now = time.monotonic()
        for frame in frames:
            self.rx_frames += 1
            self._emit("rx", frame.format(), frame=frame, source=source)
            if frame.message is None:
                continue
            with self._lock:
                replies = self.fw.handle_message(frame.message, now)
            self._write_frames(replies)

    def _write_frames(self, frames: list[bytes]) -> None:
        client = self._client
        ser = self._ser
        for raw in frames:
            self.tx_bytes += len(raw)
            try:
                if client is not None:
                    client.write(raw)
                elif ser is not None:
                    ser.write(raw)
                    ser.flush()
            except Exception as exc:
                self._emit("err", f"write failed: {exc}")
                return
            decoded = decode_frame(raw)
            if decoded.cmd in ONE_SHOT_TX:
                if client is not None:
                    where = "adb"
                elif ser is not None:
                    where = "usb"
                else:
                    where = "offline"
                self._emit("tx", decoded.format(), frame=decoded, source=where)

    def _emit(
        self,
        kind: str,
        text: str,
        frame: DecodedFrame | None = None,
        source: str = "",
    ) -> None:
        self.events.put(("log", kind, text, frame, source, time.time()))


def _require_serial() -> None:
    try:
        import serial  # noqa: F401
    except ImportError:
        sys.stderr.write(
            "pyserial is required for USB. Install with:\n"
            "  pip install pyserial\n"
        )
        raise SystemExit(2)


class Applet:
    def __init__(self, port: str | None, baud: int, auto_connect: bool = False) -> None:
        import tkinter as tk
        from tkinter import ttk
        from tkinter.scrolledtext import ScrolledText

        self.tk = tk
        self.events: queue.Queue[tuple] = queue.Queue()
        self.link = Link(self.events)
        self._samples = sample_commands()

        root = tk.Tk()
        self.root = root
        root.title("Smart Marker — device RX/TX applet")
        root.minsize(980, 620)
        root.geometry("1180x720")

        main = ttk.Frame(root, padding=8)
        main.grid(row=0, column=0, sticky="nsew")
        root.columnconfigure(0, weight=1)
        root.rowconfigure(0, weight=1)
        main.columnconfigure(1, weight=1)
        main.rowconfigure(1, weight=1)

        bar = ttk.Frame(main)
        bar.grid(row=0, column=0, columnspan=2, sticky="ew", pady=(0, 8))
        ttk.Label(bar, text="Port").pack(side="left")
        self.port_var = tk.StringVar(value=port or ADB_PORT_NAME)
        self.port_combo = ttk.Combobox(
            bar, textvariable=self.port_var, width=18, state="readonly"
        )
        self.port_combo.pack(side="left", padx=4)
        ttk.Button(bar, text="Refresh", command=self._refresh_ports).pack(side="left")
        ttk.Label(bar, text="Baud").pack(side="left", padx=(12, 0))
        self.baud_var = tk.StringVar(value=str(baud))
        ttk.Combobox(
            bar,
            textvariable=self.baud_var,
            width=8,
            values=("9600", "57600", "115200", "230400", "460800", "921600"),
            state="readonly",
        ).pack(side="left", padx=4)
        self.connect_btn = ttk.Button(bar, text="Connect", command=self._toggle)
        self.connect_btn.pack(side="left", padx=(12, 0))
        ttk.Button(bar, text="Reset device", command=self._reset).pack(
            side="left", padx=4
        )
        ttk.Button(bar, text="Copy log", command=self._copy_log).pack(side="left")
        ttk.Button(bar, text="Clear log", command=self._clear_log).pack(side="left")

        left = ttk.Frame(main)
        left.grid(row=1, column=0, sticky="nsw", padx=(0, 8))
        left.columnconfigure(0, weight=1)

        state = ttk.LabelFrame(left, text="Device state (echoed in TELEMETRY)", padding=8)
        state.grid(row=0, column=0, sticky="ew")
        self.state_var = tk.StringVar(value="UNLINKED — press Connect")
        ttk.Label(state, textvariable=self.state_var, justify="left").grid(
            row=0, column=0, sticky="w"
        )

        sim = ttk.LabelFrame(left, text="Simulate", padding=8)
        sim.grid(row=1, column=0, sticky="ew", pady=8)
        ttk.Button(sim, text="Toggle moved (relocation)", command=self._toggle_moved).grid(
            row=0, column=0, sticky="ew", pady=2
        )
        ttk.Button(sim, text="Toggle healthOK", command=self._toggle_health).grid(
            row=1, column=0, sticky="ew", pady=2
        )
        ttk.Button(sim, text="Swivel yaw +5°", command=lambda: self._swivel(5)).grid(
            row=2, column=0, sticky="ew", pady=2
        )
        ttk.Button(sim, text="Swivel yaw −5°", command=lambda: self._swivel(-5)).grid(
            row=3, column=0, sticky="ew", pady=2
        )
        drift = ttk.Frame(sim)
        drift.grid(row=4, column=0, sticky="ew", pady=(6, 0))
        self.drift_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(
            drift,
            text="Idle yaw drift (FOV wedge)",
            variable=self.drift_var,
            command=self._toggle_drift,
        ).pack(side="left")
        link_row = ttk.Frame(sim)
        link_row.grid(row=5, column=0, sticky="ew", pady=(6, 0))
        ttk.Label(link_row, text="NOW status").pack(side="left")
        self.now_var = tk.StringVar(value=ConnectionStatus.Linked.name)
        ttk.Combobox(
            link_row,
            textvariable=self.now_var,
            values=tuple(s.name for s in ConnectionStatus),
            width=12,
            state="readonly",
        ).pack(side="left", padx=4)
        self.now_var.trace_add("write", lambda *_: self._set_now_status())

        inj = ttk.LabelFrame(left, text="Inject tablet command (parser test)", padding=8)
        inj.grid(row=2, column=0, sticky="ew")
        self.sample_var = tk.StringVar(value=self._samples[0][0])
        ttk.Combobox(
            inj,
            textvariable=self.sample_var,
            values=[name for name, _ in self._samples],
            width=32,
            state="readonly",
        ).grid(row=0, column=0, sticky="ew")
        ttk.Button(inj, text="Inject", command=self._inject_sample).grid(
            row=1, column=0, sticky="ew", pady=4
        )
        ttk.Label(
            inj,
            text="Inject feeds the same decoder as USB RX.\n"
            "Walk tag finding → survey → project.\n"
            "BEGIN_TAG_SEARCH does not need SET_ORIGIN.\n"
            "Two TAG_FOUND ids set poseValid + originIsSet.\n"
            "SET_ORIGIN is optional and identifies pose immediately.",
            justify="left",
        ).grid(row=2, column=0, sticky="w")

        log_frame = ttk.LabelFrame(main, text="Incoming tablet commands  /  one-shot TX", padding=4)
        log_frame.grid(row=1, column=1, sticky="nsew")
        log_frame.columnconfigure(0, weight=1)
        log_frame.rowconfigure(0, weight=1)
        self.log = ScrolledText(
            log_frame, wrap="word", font=("Consolas", 10), state="disabled"
        )
        self.log.grid(row=0, column=0, sticky="nsew")
        self.log.tag_configure("rx", foreground="#0b3d2e")
        self.log.tag_configure("tx", foreground="#1a365d")
        self.log.tag_configure("sys", foreground="#4a5568")
        self.log.tag_configure("err", foreground="#9b2c2c")
        self.log.tag_configure("meta", foreground="#718096")

        self.status_var = tk.StringVar(value="Disconnected")
        ttk.Label(main, textvariable=self.status_var, relief="sunken", anchor="w").grid(
            row=2, column=0, columnspan=2, sticky="ew", pady=(8, 0)
        )

        self._refresh_ports(initial=port)
        self._pump()
        self._refresh_state()
        root.protocol("WM_DELETE_WINDOW", self._on_close)
        if auto_connect:
            root.after(200, self._toggle)

    def run(self) -> None:
        self.root.mainloop()

    def _refresh_ports(self, initial: str | None = None) -> None:
        ports = [ADB_PORT_NAME, OFFLINE_PORT] + list_serial_ports()
        current = self.port_var.get()
        self.port_combo["values"] = ports
        if initial and initial in ports:
            self.port_var.set(initial)
        elif current in ports:
            self.port_var.set(current)
        else:
            self.port_var.set(ports[0])

    def _toggle(self) -> None:
        if self.link.connected:
            self.link.stop()
            self.connect_btn.configure(text="Connect")
            self._append("sys", "session down")
            self._refresh_state()
            return
        port = self.port_var.get()
        baud = int(self.baud_var.get())
        if tcp_port_of(port) is None and port != OFFLINE_PORT:
            _require_serial()
        try:
            self.link.start(port, baud)
        except Exception as exc:
            self._append("err", f"connect failed: {exc}")
            return
        self.connect_btn.configure(text="Disconnect")
        self._toggle_drift()
        self._set_now_status()

    def _reset(self) -> None:
        self.link.reset_device()
        if self.link.connected:
            self.connect_btn.configure(text="Disconnect")
        self._toggle_drift()
        self._set_now_status()
        self._refresh_state()

    def _toggle_moved(self) -> None:
        def op(fw: DeviceFirmware, now: float):
            return fw.set_moved(not fw.moved, now)

        self.link.operator(op)

    def _toggle_health(self) -> None:
        def op(fw: DeviceFirmware, now: float):
            return fw.set_health(not fw.health_ok, now)

        self.link.operator(op)

    def _swivel(self, deg: float) -> None:
        def op(fw: DeviceFirmware, now: float):
            del now
            fw.add_yaw_deg(deg)
            return []

        self.link.operator(op)

    def _toggle_drift(self) -> None:
        drift = 0.08 if self.drift_var.get() else 0.0

        def op(fw: DeviceFirmware, now: float):
            del now
            fw.yaw_drift_rad_s = drift
            return []

        self.link.operator(op)

    def _set_now_status(self) -> None:
        name = self.now_var.get()
        try:
            status = ConnectionStatus[name]
        except KeyError:
            return

        def op(fw: DeviceFirmware, now: float):
            del now
            fw.connection_status = status
            return []

        self.link.operator(op)

    def _inject_sample(self) -> None:
        name = self.sample_var.get()
        for label, raw in self._samples:
            if label == name:
                if not self.link.connected:
                    self._append(
                        "err",
                        "Connect first (OFFLINE is fine) so FIRMWARE_VERSION has been sent.",
                    )
                    return
                self.link.inject(raw)
                return

    def _copy_log(self) -> None:
        text = self.log.get("1.0", "end-1c")
        self.root.clipboard_clear()
        self.root.clipboard_append(text)

    def _clear_log(self) -> None:
        self.log.configure(state="normal")
        self.log.delete("1.0", "end")
        self.log.configure(state="disabled")

    def _on_close(self) -> None:
        self.link.stop()
        self.root.destroy()

    def _pump(self) -> None:
        try:
            while True:
                item = self.events.get_nowait()
                if item[0] == "log":
                    _, kind, text, frame, source, ts = item
                    self._append(kind, text, frame=frame, source=source, ts=ts)
        except queue.Empty:
            pass
        self._refresh_state()
        self.root.after(80, self._pump)

    def _append(
        self,
        kind: str,
        text: str,
        frame: DecodedFrame | None = None,
        source: str = "",
        ts: float | None = None,
    ) -> None:
        stamp = time.strftime("%H:%M:%S", time.localtime(ts or time.time()))
        ms = int(((ts or time.time()) % 1) * 1000)
        prefix = f"{stamp}.{ms:03d}  {kind.upper():3} "
        if source:
            prefix += f"[{source}] "
        line = prefix + text
        if frame is not None and kind == "rx":
            line += f"\n{'':16}{frame.hex}"
        self.log.configure(state="normal")
        self.log.insert("end", line + "\n", kind)
        self.log.see("end")
        self.log.configure(state="disabled")

    def _refresh_state(self) -> None:
        snap = self.link.snapshot()
        yaw = math.degrees(snap.effective_yaw)
        pose = snap.last_known_pose.format() if snap.pose_valid else "(ignore — poseValid=0)"
        tags = ", ".join(str(t) for t in snap.locked_tags) or "—"
        nodes = ", ".join(f"V{n.id:02d}" if n.id <= 99 else str(n.id) for n in snap.nodes) or "—"
        session = "up" if self.link.connected else "down"
        self.state_var.set(
            f"session {session}   mode {snap.mode.value}\n"
            f"healthOK={int(snap.health_ok)}  moved={int(snap.moved)}  "
            f"originIsSet={int(snap.origin_is_set)}\n"
            f"poseValid={int(snap.pose_valid)}  surveyReady={int(snap.survey_ready)}\n"
            f"isSurveying={int(snap.is_surveying)}  isProjecting={int(snap.is_projecting)}\n"
            f"laserOn={int(snap.laser_on)}  laserPower={snap.laser_power}%  "
            f"proj={snap.projection_mode.name}\n"
            f"NOW={snap.connection_status.name}  "
            f"yaw={yaw:.2f}°  el={snap.beam_el_deg:.2f}°\n"
            f"firmware {snap.firmware_version}  ticks {snap.telemetry_ticks}\n"
            f"tags [{tags}]\n"
            f"nodes ({len(snap.nodes)}): {nodes}\n"
            f"lastKnownPose: {pose}"
        )
        port = self.link.port if self.link.connected else "—"
        peer = self.link.peer or ("waiting for tablet" if tcp_port_of(self.link.port) else "")
        extra = f"  peer {peer}" if peer else ""
        self.status_var.set(
            f"{'Listening' if self.link.connected else 'Disconnected'}  {port}{extra}   "
            f"TX {self.link.tx_bytes} B  RX {self.link.rx_bytes} B  "
            f"cmds {self.link.rx_frames}  telemetry ~{self.link.last_hz:.0f} Hz "
            f"(target {TELEMETRY_HZ:.0f})"
        )


def run_cli(port: str, baud: int) -> None:
    events: queue.Queue[tuple] = queue.Queue()
    link = Link(events)
    try:
        if tcp_port_of(port) is None and port != OFFLINE_PORT:
            _require_serial()
        link.start(port, baud)
    except Exception as exc:
        sys.stderr.write(f"connect failed: {exc}\n")
        raise SystemExit(1)

    sys.stderr.write(
        "CLI session. Keys: m moved, h health, [/] yaw, r reset, q quit.\n"
        "For ADB: adb reverse tcp:8765 tcp:8765  then launch the tablet app.\n"
        "Tag Finding: BEGIN_TAG_SEARCH (no origin) then two TAG_FOUND ids.\n"
        "Telemetry is silent here; one-shot TX and every RX command are printed.\n"
    )
    stop = threading.Event()

    def printer() -> None:
        while not stop.is_set():
            try:
                item = events.get(timeout=0.1)
            except queue.Empty:
                continue
            if item[0] != "log":
                continue
            _, kind, text, frame, source, ts = item
            stamp = time.strftime("%H:%M:%S", time.localtime(ts))
            extra = f" {frame.hex}" if frame is not None and kind == "rx" else ""
            print(f"{stamp} {kind:3} [{source}] {text}{extra}", flush=True)

    threading.Thread(target=printer, daemon=True).start()
    try:
        while True:
            line = sys.stdin.readline()
            if not line:
                break
            key = line.strip().lower()
            if key in {"q", "quit", "exit"}:
                break
            if key == "m":
                link.operator(lambda fw, now: fw.set_moved(not fw.moved, now))
            elif key == "h":
                link.operator(lambda fw, now: fw.set_health(not fw.health_ok, now))
            elif key == "[":
                link.operator(lambda fw, now: (fw.add_yaw_deg(-5), [])[1])
            elif key == "]":
                link.operator(lambda fw, now: (fw.add_yaw_deg(5), [])[1])
            elif key == "r":
                link.reset_device()
            elif key == "s":
                snap = link.snapshot()
                print(
                    f"# {snap.mode.value} health={int(snap.health_ok)} "
                    f"moved={int(snap.moved)} origin={int(snap.origin_is_set)} "
                    f"surveyReady={int(snap.survey_ready)} yaw="
                    f"{math.degrees(snap.effective_yaw):.2f}°",
                    flush=True,
                )
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        link.stop()


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Smart Marker device-side USB RX/TX test applet"
    )
    parser.add_argument("--port", help="ADB:8765 (default), COM port, or OFFLINE")
    parser.add_argument("--baud", type=int, default=DEFAULT_BAUD)
    parser.add_argument(
        "--adb",
        action="store_true",
        help="Listen on 127.0.0.1:8765 for the tablet over USB debugging",
    )
    parser.add_argument(
        "--listen-port",
        type=int,
        default=DEFAULT_TCP_PORT,
        help="TCP port for --adb (must match adb reverse)",
    )
    parser.add_argument("--offline", action="store_true", help="Start without a serial port")
    parser.add_argument("--cli", action="store_true", help="Log to stdout instead of the GUI")
    parser.add_argument(
        "--list-ports", action="store_true", help="Print serial ports and exit"
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> None:
    args = parse_args(argv)
    if args.list_ports:
        ports = list_serial_ports()
        if not ports:
            print("No serial ports found.")
            return
        for p in ports:
            print(p)
        return
    if args.offline:
        port = OFFLINE_PORT
        auto = False
    elif args.adb or args.port is None:
        port = f"ADB:{args.listen_port}"
        auto = bool(args.adb) or args.port is None
    else:
        port = args.port
        auto = False
    if args.cli:
        run_cli(port, args.baud)
        return
    try:
        if sys.platform == "win32":
            from ctypes import windll

            windll.shcore.SetProcessDpiAwareness(1)
    except Exception:
        pass
    Applet(port=port, baud=args.baud, auto_connect=auto).run()


if __name__ == "__main__":
    main()
