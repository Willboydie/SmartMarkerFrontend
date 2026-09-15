"""Smart Marker v1 wire codec (docs/protocol.md).

Every frame is little-endian: ``cmd:u8``, ``length:u16`` (payload bytes), then payload.
Device → tablet commands are 0x01–0x0F; tablet → device are 0x10–0x1F.
"""

from __future__ import annotations

import math
import struct
from dataclasses import dataclass
from enum import IntEnum
from typing import Iterator

PROTOCOL_VERSION = 1
HEADER_SIZE = 3
# Dongle host limit is 1024-byte frames (docs/WIFI_DONGLE_HOSTING.md). ESP-NOW
# datagrams are 250 bytes; the chip splits larger frames on air.
MAX_FRAME_BYTES = 1024
MAX_PAYLOAD = MAX_FRAME_BYTES - HEADER_SIZE
PAIRING_CMD = 0xE5
CDC_BAUD = 921_600
TWO_PI = math.tau  # 2π

_POSE = struct.Struct("<6f")  # 24 bytes
_NODE = struct.Struct("<H4f")  # 18 bytes
_TELEMETRY = struct.Struct("<12B f 6f")  # 40 bytes


class Cmd(IntEnum):
    FIRMWARE_VERSION = 0x01
    TELEMETRY = 0x02
    SURVEY_POINT = 0x03
    PROJECTION_START = 0x04
    SET_LASER_POWER = 0x10
    SET_PROJECTION_MODE = 0x11
    BEGIN_TAG_SEARCH = 0x12
    BEGIN_SURVEY = 0x13
    BEGIN_PROJECTION = 0x14
    TAG_FOUND = 0x15
    NUDGE_BEAM_X = 0x16
    NUDGE_BEAM_Y = 0x17
    SET_ORIGIN = 0x18
    REGISTER_NODES = 0x19


DEVICE_CMDS = frozenset(
    {
        Cmd.FIRMWARE_VERSION,
        Cmd.TELEMETRY,
        Cmd.SURVEY_POINT,
        Cmd.PROJECTION_START,
    }
)
TABLET_CMDS = frozenset(
    {
        Cmd.SET_LASER_POWER,
        Cmd.SET_PROJECTION_MODE,
        Cmd.BEGIN_TAG_SEARCH,
        Cmd.BEGIN_SURVEY,
        Cmd.BEGIN_PROJECTION,
        Cmd.TAG_FOUND,
        Cmd.NUDGE_BEAM_X,
        Cmd.NUDGE_BEAM_Y,
        Cmd.SET_ORIGIN,
        Cmd.REGISTER_NODES,
    }
)


class ConnectionStatus(IntEnum):
    Linked = 0
    Searching = 1
    Lost = 2


class ProjectionMode(IntEnum):
    Off = 0
    Survey = 1
    Project = 2


def wrap_two_pi(radians: float) -> float:
    """Wrap an angle into [0, 2π)."""
    r = math.fmod(float(radians), TWO_PI)
    if r < 0.0:
        r += TWO_PI
    return r


def node_label(node_id: int) -> str:
    """Human label for a tablet-assigned u16 id (V01 style when it fits)."""
    if 1 <= node_id <= 99:
        return f"{node_id} (V{node_id:02d})"
    return str(node_id)


def pack_frame(cmd: int, payload: bytes = b"") -> bytes:
    cmd = int(cmd) & 0xFF
    if cmd == PAIRING_CMD:
        raise ValueError("cmd 0xE5 is reserved for dongle pairing")
    n = len(payload)
    if n > MAX_PAYLOAD:
        raise ValueError(f"payload too large: {n} (max {MAX_PAYLOAD})")
    return bytes((cmd, n & 0xFF, (n >> 8) & 0xFF)) + payload


def unpack_header(header: bytes) -> tuple[int, int]:
    if len(header) < HEADER_SIZE:
        raise ValueError("short header")
    return header[0], header[1] | (header[2] << 8)


@dataclass(frozen=True)
class Pose:
    east_mm: float = 0.0
    north_mm: float = 0.0
    up_mm: float = 0.0
    roll_rad: float = 0.0
    pitch_rad: float = 0.0
    yaw_rad: float = 0.0

    def wrapped(self) -> Pose:
        return Pose(
            self.east_mm,
            self.north_mm,
            self.up_mm,
            wrap_two_pi(self.roll_rad),
            wrap_two_pi(self.pitch_rad),
            wrap_two_pi(self.yaw_rad),
        )

    def pack(self) -> bytes:
        p = self.wrapped()
        return _POSE.pack(
            p.east_mm, p.north_mm, p.up_mm, p.roll_rad, p.pitch_rad, p.yaw_rad
        )

    @classmethod
    def unpack(cls, data: bytes, offset: int = 0) -> Pose:
        if offset + _POSE.size > len(data):
            raise ValueError("short Pose")
        return cls(*_POSE.unpack_from(data, offset))

    def format(self) -> str:
        p = self.wrapped()
        yaw_deg = math.degrees(p.yaw_rad)
        return (
            f"E={p.east_mm:.1f} N={p.north_mm:.1f} U={p.up_mm:.1f} mm  "
            f"r={p.roll_rad:.3f} p={p.pitch_rad:.3f} y={p.yaw_rad:.3f} rad "
            f"({yaw_deg:.2f}°)"
        )


@dataclass(frozen=True)
class Node:
    id: int
    east_mm: float
    north_mm: float
    corner_inner_angle_rad: float
    corner_orientation_rad: float

    def pack(self) -> bytes:
        return _NODE.pack(
            int(self.id) & 0xFFFF,
            self.east_mm,
            self.north_mm,
            wrap_two_pi(self.corner_inner_angle_rad),
            wrap_two_pi(self.corner_orientation_rad),
        )

    @classmethod
    def unpack(cls, data: bytes, offset: int = 0) -> Node:
        if offset + _NODE.size > len(data):
            raise ValueError("short Node")
        nid, east, north, inner, orient = _NODE.unpack_from(data, offset)
        return cls(nid, east, north, inner, orient)

    def format(self) -> str:
        return (
            f"{node_label(self.id)}  E={self.east_mm:.1f} N={self.north_mm:.1f}  "
            f"inner={self.corner_inner_angle_rad:.3f} "
            f"orient={self.corner_orientation_rad:.3f}"
        )


@dataclass(frozen=True)
class FirmwareVersion:
    protocol_version: int
    firmware_version: str

    def pack(self) -> bytes:
        encoded = self.firmware_version.encode("utf-8")
        return pack_frame(Cmd.FIRMWARE_VERSION, bytes((self.protocol_version & 0xFF,)) + encoded)

    @classmethod
    def unpack_payload(cls, payload: bytes) -> FirmwareVersion:
        if not payload:
            raise ValueError("empty FIRMWARE_VERSION")
        return cls(payload[0], payload[1:].decode("utf-8"))

    def format(self) -> str:
        return f"protocol={self.protocol_version} firmware={self.firmware_version!r}"


@dataclass(frozen=True)
class Telemetry:
    health_ok: bool
    moved: bool
    origin_is_set: bool
    is_surveying: bool
    survey_ready: bool
    is_projecting: bool
    laser_on: bool
    connection_status: ConnectionStatus
    projection_mode: ProjectionMode
    laser_power: int
    pose_valid: bool
    reserved: int
    effective_yaw: float
    last_known_pose: Pose

    def pack(self) -> bytes:
        pose = self.last_known_pose.wrapped()
        payload = _TELEMETRY.pack(
            1 if self.health_ok else 0,
            1 if self.moved else 0,
            1 if self.origin_is_set else 0,
            1 if self.is_surveying else 0,
            1 if self.survey_ready else 0,
            1 if self.is_projecting else 0,
            1 if self.laser_on else 0,
            int(self.connection_status) & 0xFF,
            int(self.projection_mode) & 0xFF,
            int(self.laser_power) & 0xFF,
            1 if self.pose_valid else 0,
            int(self.reserved) & 0xFF,
            wrap_two_pi(self.effective_yaw),
            pose.east_mm,
            pose.north_mm,
            pose.up_mm,
            pose.roll_rad,
            pose.pitch_rad,
            pose.yaw_rad,
        )
        return pack_frame(Cmd.TELEMETRY, payload)

    @classmethod
    def unpack_payload(cls, payload: bytes) -> Telemetry:
        if len(payload) != _TELEMETRY.size:
            raise ValueError(f"TELEMETRY length {len(payload)} != 40")
        fields = _TELEMETRY.unpack(payload)
        return cls(
            health_ok=bool(fields[0]),
            moved=bool(fields[1]),
            origin_is_set=bool(fields[2]),
            is_surveying=bool(fields[3]),
            survey_ready=bool(fields[4]),
            is_projecting=bool(fields[5]),
            laser_on=bool(fields[6]),
            connection_status=ConnectionStatus(fields[7]),
            projection_mode=ProjectionMode(fields[8]),
            laser_power=fields[9],
            pose_valid=bool(fields[10]),
            reserved=fields[11],
            effective_yaw=fields[12],
            last_known_pose=Pose(*fields[13:19]),
        )

    def format(self) -> str:
        yaw_deg = math.degrees(wrap_two_pi(self.effective_yaw))
        return (
            f"health={int(self.health_ok)} moved={int(self.moved)} "
            f"origin={int(self.origin_is_set)} poseValid={int(self.pose_valid)} "
            f"surveying={int(self.is_surveying)} surveyReady={int(self.survey_ready)} "
            f"projecting={int(self.is_projecting)} laser={'on' if self.laser_on else 'off'}"
            f"/{self.laser_power}% mode={self.projection_mode.name} "
            f"link={self.connection_status.name} yaw={yaw_deg:.2f}°"
        )


@dataclass(frozen=True)
class SurveyPoint:
    """One result for a vertex requested in BEGIN_SURVEY (27-byte payload)."""

    node_id: int
    success: bool
    pose: Pose = Pose()

    def pack(self) -> bytes:
        pose = self.pose.wrapped() if self.success else Pose()
        payload = struct.pack("<H", int(self.node_id) & 0xFFFF)
        payload += bytes((1 if self.success else 0,))
        payload += pose.pack()
        return pack_frame(Cmd.SURVEY_POINT, payload)

    @classmethod
    def unpack_payload(cls, payload: bytes) -> SurveyPoint:
        if len(payload) != 27:
            raise ValueError(f"SURVEY_POINT length {len(payload)} != 27")
        nid = struct.unpack_from("<H", payload, 0)[0]
        success = payload[2] != 0
        pose = Pose.unpack(payload, 3)
        return cls(nid, success, pose)

    def format(self) -> str:
        status = "ok" if self.success else "miss"
        pose = self.pose.format() if self.success else "—"
        return f"{node_label(self.node_id)} {status} {pose}"


@dataclass(frozen=True)
class ProjectionStart:
    nodes: tuple[Node, ...]

    def pack(self) -> bytes:
        return pack_frame(
            Cmd.PROJECTION_START,
            struct.pack("<H", len(self.nodes)) + b"".join(n.pack() for n in self.nodes),
        )

    @classmethod
    def unpack_payload(cls, payload: bytes) -> ProjectionStart:
        nodes = _unpack_node_list(payload, flags=False)
        return cls(tuple(nodes))

    def format(self) -> str:
        ids = ", ".join(node_label(n.id) for n in self.nodes) or "—"
        return f"count={len(self.nodes)} [{ids}]"


@dataclass(frozen=True)
class SetLaserPower:
    duty: int

    def pack(self) -> bytes:
        return pack_frame(Cmd.SET_LASER_POWER, bytes((self.duty & 0xFF,)))

    @classmethod
    def unpack_payload(cls, payload: bytes) -> SetLaserPower:
        if len(payload) != 1:
            raise ValueError("SET_LASER_POWER length != 1")
        return cls(payload[0])

    def format(self) -> str:
        return f"duty={self.duty}%"


@dataclass(frozen=True)
class SetProjectionMode:
    mode: ProjectionMode

    def pack(self) -> bytes:
        return pack_frame(Cmd.SET_PROJECTION_MODE, bytes((int(self.mode) & 0xFF,)))

    @classmethod
    def unpack_payload(cls, payload: bytes) -> SetProjectionMode:
        if len(payload) != 1:
            raise ValueError("SET_PROJECTION_MODE length != 1")
        return cls(ProjectionMode(payload[0]))

    def format(self) -> str:
        return f"mode={self.mode.name} ({int(self.mode)})"


@dataclass(frozen=True)
class BeginTagSearch:
    """IDLE → TAG_SEARCH. Origin is not required."""

    def pack(self) -> bytes:
        return pack_frame(Cmd.BEGIN_TAG_SEARCH, b"")

    @classmethod
    def unpack_payload(cls, payload: bytes) -> BeginTagSearch:
        if payload:
            raise ValueError("BEGIN_TAG_SEARCH must be empty")
        return cls()

    def format(self) -> str:
        return "(no payload)"


@dataclass(frozen=True)
class BeginSurvey:
    ids: tuple[int, ...]

    def pack(self) -> bytes:
        return pack_frame(Cmd.BEGIN_SURVEY, _pack_id_list(self.ids))

    @classmethod
    def unpack_payload(cls, payload: bytes) -> BeginSurvey:
        return cls(_unpack_id_list(payload))

    def format(self) -> str:
        ids = ", ".join(node_label(i) for i in self.ids) or "—"
        return f"count={len(self.ids)} [{ids}]"


@dataclass(frozen=True)
class BeginProjection:
    ids: tuple[int, ...]

    def pack(self) -> bytes:
        return pack_frame(Cmd.BEGIN_PROJECTION, _pack_id_list(self.ids))

    @classmethod
    def unpack_payload(cls, payload: bytes) -> BeginProjection:
        return cls(_unpack_id_list(payload))

    def format(self) -> str:
        ids = ", ".join(node_label(i) for i in self.ids) or "—"
        return f"count={len(self.ids)} [{ids}]"


@dataclass(frozen=True)
class TagFound:
    """TAG_SEARCH → IDLE. Two distinct ids identify pose (originIsSet + poseValid)."""

    tag_id: int

    def pack(self) -> bytes:
        return pack_frame(Cmd.TAG_FOUND, struct.pack("<H", int(self.tag_id) & 0xFFFF))

    @classmethod
    def unpack_payload(cls, payload: bytes) -> TagFound:
        if len(payload) != 2:
            raise ValueError("TAG_FOUND length != 2")
        return cls(struct.unpack("<H", payload)[0])

    def format(self) -> str:
        return f"tagID={self.tag_id}"


@dataclass(frozen=True)
class NudgeBeamX:
    dx_deg: float

    def pack(self) -> bytes:
        return pack_frame(Cmd.NUDGE_BEAM_X, struct.pack("<f", self.dx_deg))

    @classmethod
    def unpack_payload(cls, payload: bytes) -> NudgeBeamX:
        if len(payload) != 4:
            raise ValueError("NUDGE_BEAM_X length != 4")
        return cls(struct.unpack("<f", payload)[0])

    def format(self) -> str:
        return f"dx={self.dx_deg:.4f}° (azimuth, CCW +)"


@dataclass(frozen=True)
class NudgeBeamY:
    dy_deg: float

    def pack(self) -> bytes:
        return pack_frame(Cmd.NUDGE_BEAM_Y, struct.pack("<f", self.dy_deg))

    @classmethod
    def unpack_payload(cls, payload: bytes) -> NudgeBeamY:
        if len(payload) != 4:
            raise ValueError("NUDGE_BEAM_Y length != 4")
        return cls(struct.unpack("<f", payload)[0])

    def format(self) -> str:
        return f"dy={self.dy_deg:.4f}° (elevation, up +)"


@dataclass(frozen=True)
class SetOrigin:
    """Optional force-pose. Immediately sets originIsSet and poseValid."""

    pose: Pose

    def pack(self) -> bytes:
        return pack_frame(Cmd.SET_ORIGIN, self.pose.pack())

    @classmethod
    def unpack_payload(cls, payload: bytes) -> SetOrigin:
        if len(payload) != _POSE.size:
            raise ValueError("SET_ORIGIN length != 24")
        return cls(Pose.unpack(payload))

    def format(self) -> str:
        return self.pose.format()


@dataclass(frozen=True)
class RegisterNodes:
    nodes: tuple[Node, ...]
    flags: int | None = None
    """If not None, this frame used the optional §7 chunk header (bit0 start, bit1 last)."""

    def pack(self) -> bytes:
        body = struct.pack("<H", len(self.nodes)) + b"".join(n.pack() for n in self.nodes)
        if self.flags is not None:
            body = bytes((int(self.flags) & 0xFF,)) + body
        return pack_frame(Cmd.REGISTER_NODES, body)

    @classmethod
    def unpack_payload(cls, payload: bytes) -> RegisterNodes:
        if _is_chunked_register(payload):
            flags = payload[0]
            nodes = _unpack_node_list(payload[1:], flags=False)
            return cls(tuple(nodes), flags=flags)
        nodes = _unpack_node_list(payload, flags=False)
        return cls(tuple(nodes), flags=None)

    @property
    def start(self) -> bool:
        return self.flags is not None and bool(self.flags & 0x01)

    @property
    def last(self) -> bool:
        return self.flags is None or bool(self.flags & 0x02)

    def format(self) -> str:
        ids = ", ".join(node_label(n.id) for n in self.nodes) or "—"
        chunk = ""
        if self.flags is not None:
            bits = []
            if self.start:
                bits.append("start")
            if self.last:
                bits.append("last")
            chunk = f" chunk=0x{self.flags:02x}({','.join(bits) or 'mid'})"
        return f"count={len(self.nodes)}{chunk} [{ids}]"


DeviceMessage = FirmwareVersion | Telemetry | SurveyPoint | ProjectionStart
TabletMessage = (
    SetLaserPower
    | SetProjectionMode
    | BeginTagSearch
    | BeginSurvey
    | BeginProjection
    | TagFound
    | NudgeBeamX
    | NudgeBeamY
    | SetOrigin
    | RegisterNodes
)
Message = DeviceMessage | TabletMessage


_TABLET_UNPACK = {
    Cmd.SET_LASER_POWER: SetLaserPower.unpack_payload,
    Cmd.SET_PROJECTION_MODE: SetProjectionMode.unpack_payload,
    Cmd.BEGIN_TAG_SEARCH: BeginTagSearch.unpack_payload,
    Cmd.BEGIN_SURVEY: BeginSurvey.unpack_payload,
    Cmd.BEGIN_PROJECTION: BeginProjection.unpack_payload,
    Cmd.TAG_FOUND: TagFound.unpack_payload,
    Cmd.NUDGE_BEAM_X: NudgeBeamX.unpack_payload,
    Cmd.NUDGE_BEAM_Y: NudgeBeamY.unpack_payload,
    Cmd.SET_ORIGIN: SetOrigin.unpack_payload,
    Cmd.REGISTER_NODES: RegisterNodes.unpack_payload,
}

_DEVICE_UNPACK = {
    Cmd.FIRMWARE_VERSION: FirmwareVersion.unpack_payload,
    Cmd.TELEMETRY: Telemetry.unpack_payload,
    Cmd.SURVEY_POINT: SurveyPoint.unpack_payload,
    Cmd.PROJECTION_START: ProjectionStart.unpack_payload,
}


@dataclass(frozen=True)
class DecodedFrame:
    cmd: int
    payload: bytes
    message: Message | None
    error: str | None = None

    @property
    def name(self) -> str:
        try:
            return Cmd(self.cmd).name
        except ValueError:
            return f"UNKNOWN_0x{self.cmd:02X}"

    @property
    def hex(self) -> str:
        n = len(self.payload)
        frame = bytes((self.cmd & 0xFF, n & 0xFF, (n >> 8) & 0xFF)) + self.payload
        return frame.hex(" ")

    def format(self) -> str:
        if self.error:
            return f"{self.name}  [{self.error}]  {self.hex}"
        if self.message is None:
            return f"{self.name}  {self.hex}"
        return f"{self.name}  {self.message.format()}"


def decode_payload(cmd: int, payload: bytes) -> Message:
    if cmd in _TABLET_UNPACK:
        return _TABLET_UNPACK[cmd](payload)
    if cmd in _DEVICE_UNPACK:
        return _DEVICE_UNPACK[cmd](payload)
    raise ValueError(f"unknown cmd 0x{cmd:02X}")


def decode_frame(data: bytes) -> DecodedFrame:
    cmd, length = unpack_header(data)
    payload = data[HEADER_SIZE : HEADER_SIZE + length]
    if len(data) < HEADER_SIZE + length:
        raise ValueError("truncated frame")
    try:
        message = decode_payload(cmd, payload)
        return DecodedFrame(cmd, payload, message)
    except (ValueError, KeyError, struct.error) as exc:
        return DecodedFrame(cmd, payload, None, error=str(exc))


class FrameDecoder:
    """Streaming parser: feed USB chunks, yield complete frames.

    Pairing cmd ``0xE5`` is skipped as a whole frame (chip-to-chip, not
    forwarded). Unknown ``cmd`` bytes are discarded one at a time so a
    desynced stream can resynchronise on the next tablet (0x10–0x19) or
    device (0x01–0x04) command.
    """

    def __init__(self, *, accept: frozenset[int] | None = None) -> None:
        self._buf = bytearray()
        self._accept = accept if accept is not None else (TABLET_CMDS | DEVICE_CMDS)
        self.dropped_bytes = 0

    def feed(self, data: bytes) -> Iterator[DecodedFrame]:
        self._buf.extend(data)
        yield from self._drain()

    def _drain(self) -> Iterator[DecodedFrame]:
        while True:
            if len(self._buf) < HEADER_SIZE:
                return
            cmd = self._buf[0]
            length = self._buf[1] | (self._buf[2] << 8)
            if length > MAX_PAYLOAD:
                del self._buf[0]
                self.dropped_bytes += 1
                continue
            need = HEADER_SIZE + length
            if cmd == PAIRING_CMD:
                if len(self._buf) < need:
                    return
                del self._buf[:need]
                self.dropped_bytes += need
                continue
            if cmd not in self._accept:
                del self._buf[0]
                self.dropped_bytes += 1
                continue
            if len(self._buf) < need:
                return
            raw = bytes(self._buf[:need])
            del self._buf[:need]
            yield decode_frame(raw)

    def reset(self) -> None:
        self._buf.clear()


def _pack_id_list(ids: tuple[int, ...] | list[int]) -> bytes:
    return struct.pack("<H", len(ids)) + b"".join(
        struct.pack("<H", int(i) & 0xFFFF) for i in ids
    )


def _unpack_id_list(payload: bytes) -> tuple[int, ...]:
    if len(payload) < 2:
        raise ValueError("short id list")
    count = struct.unpack_from("<H", payload, 0)[0]
    if len(payload) != 2 + 2 * count:
        raise ValueError("id-list length does not match count")
    return tuple(struct.unpack_from("<H", payload, 2 + 2 * i)[0] for i in range(count))


def _unpack_node_list(payload: bytes, flags: bool) -> list[Node]:
    offset = 1 if flags else 0
    if len(payload) < offset + 2:
        raise ValueError("short node list")
    count = struct.unpack_from("<H", payload, offset)[0]
    start = offset + 2
    if len(payload) != start + _NODE.size * count:
        raise ValueError("node-list length does not match count")
    return [Node.unpack(payload, start + _NODE.size * i) for i in range(count)]


def _is_chunked_register(payload: bytes) -> bool:
    """§7 optional flags byte. Unchunked length is 2+18n; chunked is 3+18n.

    Those never collide: (3+18n-2) % 18 == 1.
    """
    if len(payload) < 3:
        return False
    if (len(payload) - 2) % 18 == 0:
        return False
    return (len(payload) - 3) % 18 == 0
