"""Firmware-side state machine for the PC test applet (docs/protocol.md §3)."""

from __future__ import annotations

import math
from dataclasses import dataclass, field, replace
from enum import Enum

from protocol import (
    PROTOCOL_VERSION,
    BeginProjection,
    BeginSurvey,
    BeginTagSearch,
    ConnectionStatus,
    FirmwareVersion,
    Node,
    NudgeBeamX,
    NudgeBeamY,
    Pose,
    ProjectionMode,
    ProjectionStart,
    RegisterNodes,
    SetLaserPower,
    SetOrigin,
    SetProjectionMode,
    SurveyPoint,
    TABLET_CMDS,
    TagFound,
    Telemetry,
    decode_payload,
    wrap_two_pi,
)

TELEMETRY_HZ = 10.0
TELEMETRY_PERIOD = 1.0 / TELEMETRY_HZ
TAG_SEARCH_TIMEOUT_S = 8.0
CAPTURE_PERIOD_S = 5.0


class Mode(str, Enum):
    UNLINKED = "UNLINKED"
    IDLE = "IDLE"
    TAG_SEARCH = "TAG_SEARCH"
    SURVEYING = "SURVEYING"
    PROJECTING = "PROJECTING"


@dataclass
class Snapshot:
    mode: Mode
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
    effective_yaw: float
    last_known_pose: Pose
    beam_el_deg: float
    nodes: tuple[Node, ...]
    locked_tags: tuple[int, ...]
    telemetry_ticks: int
    firmware_version: str


@dataclass
class DeviceFirmware:
    """Simulates marker firmware on the USB hop (docs/protocol.md §3).

    Tag Finding: ``BEGIN_TAG_SEARCH`` is accepted from ``IDLE`` with no origin.
    Two distinct ``TAG_FOUND`` ids set ``origin_is_set``, ``pose_valid``, and
    ``last_known_pose``. ``SET_ORIGIN`` remains an optional force-pose.

    Call :meth:`on_link_up` when the serial session starts, :meth:`handle_bytes`
    for tablet frames, and :meth:`tick` regularly to emit 10 Hz telemetry plus
    one ``SURVEY_POINT`` every :attr:`capture_period_s` while surveying.
    """

    firmware_version: str = "applet-1.0"
    health_ok: bool = True
    moved: bool = False
    origin_is_set: bool = False
    laser_on: bool = False
    connection_status: ConnectionStatus = ConnectionStatus.Linked
    projection_mode: ProjectionMode = ProjectionMode.Off
    laser_power: int = 0
    pose_valid: bool = False
    last_known_pose: Pose = field(default_factory=Pose)
    effective_yaw: float = 0.0
    beam_el_deg: float = 0.0
    mode: Mode = Mode.UNLINKED
    nodes: dict[int, Node] = field(default_factory=dict)
    locked_tags: set[int] = field(default_factory=set)
    telemetry_ticks: int = 0
    yaw_drift_rad_s: float = 0.0
    capture_period_s: float = CAPTURE_PERIOD_S

    _pending_nodes: list[Node] = field(default_factory=list, repr=False)
    _survey_queue: list[int] = field(default_factory=list, repr=False)
    _next_capture: float = 0.0
    _tag_deadline: float = 0.0
    _next_telemetry: float = 0.0
    _last_tick: float = 0.0
    _linked: bool = False

    @property
    def nodes_registered(self) -> bool:
        return bool(self.nodes)

    @property
    def survey_ready(self) -> bool:
        return (
            self.health_ok
            and self.origin_is_set
            and not self.moved
            and self.pose_valid
            and self.nodes_registered
            and self.mode == Mode.IDLE
            and self.connection_status == ConnectionStatus.Linked
        )

    @property
    def is_surveying(self) -> bool:
        return self.mode == Mode.SURVEYING

    @property
    def is_projecting(self) -> bool:
        return self.mode == Mode.PROJECTING

    def snapshot(self) -> Snapshot:
        return Snapshot(
            mode=self.mode,
            health_ok=self.health_ok,
            moved=self.moved,
            origin_is_set=self.origin_is_set,
            is_surveying=self.is_surveying,
            survey_ready=self.survey_ready,
            is_projecting=self.is_projecting,
            laser_on=self.laser_on,
            connection_status=self.connection_status,
            projection_mode=self.projection_mode,
            laser_power=self.laser_power,
            pose_valid=self.pose_valid,
            effective_yaw=self.effective_yaw,
            last_known_pose=self.last_known_pose,
            beam_el_deg=self.beam_el_deg,
            nodes=tuple(self.nodes[k] for k in sorted(self.nodes)),
            locked_tags=tuple(sorted(self.locked_tags)),
            telemetry_ticks=self.telemetry_ticks,
            firmware_version=self.firmware_version,
        )

    def on_link_up(self, now: float) -> list[bytes]:
        self._linked = True
        if self.mode == Mode.UNLINKED:
            self.mode = Mode.IDLE
            self.laser_on = False
            self.projection_mode = ProjectionMode.Off
        self._next_telemetry = now
        self._last_tick = now
        version = FirmwareVersion(PROTOCOL_VERSION, self.firmware_version).pack()
        telem = self._telemetry_frame()
        self.telemetry_ticks += 1
        self._next_telemetry = now + TELEMETRY_PERIOD
        return [version, telem]

    def on_link_down(self) -> None:
        self._linked = False
        self.mode = Mode.UNLINKED
        self.laser_on = False
        self._clear_jobs(send_survey=False)

    def handle_bytes(self, cmd: int, payload: bytes, now: float) -> list[bytes]:
        if cmd not in TABLET_CMDS:
            return []
        if self.mode == Mode.UNLINKED:
            return []
        try:
            message = decode_payload(cmd, payload)
        except (ValueError, KeyError):
            return []
        return self.handle_message(message, now)

    def handle_message(self, message: object, now: float) -> list[bytes]:
        if self.mode == Mode.UNLINKED:
            return []
        if isinstance(message, SetLaserPower):
            self.laser_power = max(0, min(100, int(message.duty)))
            return []
        if isinstance(message, SetProjectionMode):
            return self._set_projection_mode(message.mode, now)
        if isinstance(message, BeginTagSearch):
            return self._begin_tag_search(now)
        if isinstance(message, BeginSurvey):
            return self._begin_survey(message.ids, now)
        if isinstance(message, BeginProjection):
            return self._begin_projection(message.ids)
        if isinstance(message, TagFound):
            return self._tag_found(message.tag_id)
        if isinstance(message, NudgeBeamX):
            return self._nudge_x(message.dx_deg)
        if isinstance(message, NudgeBeamY):
            return self._nudge_y(message.dy_deg)
        if isinstance(message, SetOrigin):
            return self._set_origin(message.pose)
        if isinstance(message, RegisterNodes):
            return self._register_nodes(message)
        return []

    def tick(self, now: float) -> list[bytes]:
        out: list[bytes] = []
        if not self._linked or self.mode == Mode.UNLINKED:
            return out

        dt = now - self._last_tick
        self._last_tick = now
        if dt > 0 and self.yaw_drift_rad_s and self.mode != Mode.SURVEYING:
            self.effective_yaw = wrap_two_pi(
                self.effective_yaw + self.yaw_drift_rad_s * dt
            )

        if self.mode == Mode.TAG_SEARCH and now >= self._tag_deadline:
            self._enter_idle(laser_on=False)

        if self.mode == Mode.SURVEYING:
            out.extend(self._advance_survey(now))

        if now >= self._next_telemetry:
            out.append(self._telemetry_frame())
            self.telemetry_ticks += 1
            # Catch up rather than burst if the UI thread stalled.
            skipped = int((now - self._next_telemetry) / TELEMETRY_PERIOD)
            self._next_telemetry += TELEMETRY_PERIOD * (1 + max(0, skipped))
        return out

    def set_moved(self, moved: bool, now: float) -> list[bytes]:
        self.moved = bool(moved)
        if not self.moved:
            return []
        return self._abort_for_moved(now)

    def set_health(self, ok: bool, now: float) -> list[bytes]:
        del now
        self.health_ok = bool(ok)
        if self.health_ok:
            return []
        out: list[bytes] = []
        if self.mode == Mode.SURVEYING:
            out.extend(self._fail_remaining_survey())
        elif self.mode in (Mode.TAG_SEARCH, Mode.PROJECTING):
            self._enter_idle(laser_on=False)
        return out

    def add_yaw_deg(self, delta_deg: float) -> None:
        """Turret swivel: updates effectiveYaw, does not set moved."""
        self.effective_yaw = wrap_two_pi(
            self.effective_yaw + math.radians(delta_deg)
        )

    def _telemetry_frame(self) -> bytes:
        return Telemetry(
            health_ok=self.health_ok,
            moved=self.moved,
            origin_is_set=self.origin_is_set,
            is_surveying=self.is_surveying,
            survey_ready=self.survey_ready,
            is_projecting=self.is_projecting,
            laser_on=self.laser_on,
            connection_status=self.connection_status,
            projection_mode=self.projection_mode,
            laser_power=self.laser_power,
            pose_valid=self.pose_valid,
            reserved=0,
            effective_yaw=self.effective_yaw,
            last_known_pose=self.last_known_pose,
        ).pack()

    def _enter_idle(self, *, laser_on: bool) -> None:
        self.mode = Mode.IDLE
        self.laser_on = laser_on
        self.projection_mode = ProjectionMode.Off
        self._survey_queue.clear()

    def _clear_jobs(self, *, send_survey: bool) -> list[bytes]:
        out: list[bytes] = []
        if send_survey and self.mode == Mode.SURVEYING:
            out.extend(self._fail_remaining_survey())
        self._survey_queue.clear()
        return out

    def _abort_for_moved(self, now: float) -> list[bytes]:
        del now
        out: list[bytes] = []
        self.moved = True
        self.pose_valid = False
        self.locked_tags.clear()
        if self.mode == Mode.SURVEYING:
            out.extend(self._fail_remaining_survey())
        else:
            self._survey_queue.clear()
            self._enter_idle(laser_on=False)
        return out

    def _set_projection_mode(self, mode: ProjectionMode, now: float) -> list[bytes]:
        del now
        if mode == ProjectionMode.Off:
            out: list[bytes] = []
            if self.mode == Mode.SURVEYING:
                out.extend(self._fail_remaining_survey())
            if self.mode in (Mode.TAG_SEARCH, Mode.SURVEYING, Mode.PROJECTING, Mode.IDLE):
                self._enter_idle(laser_on=False)
            return out
        if self.mode == Mode.IDLE:
            self.projection_mode = mode
        return []

    def _begin_tag_search(self, now: float) -> list[bytes]:
        # Origin is not required: Tag Finding is how pose is identified.
        if self.mode != Mode.IDLE:
            return []
        self.mode = Mode.TAG_SEARCH
        self.laser_on = True
        self._tag_deadline = now + TAG_SEARCH_TIMEOUT_S
        return []

    def _begin_survey(self, ids: tuple[int, ...], now: float) -> list[bytes]:
        if self.mode != Mode.IDLE or not self.survey_ready:
            return []
        self.mode = Mode.SURVEYING
        self.laser_on = True
        self.projection_mode = ProjectionMode.Survey
        self._survey_queue = list(ids)
        self._next_capture = now + self.capture_period_s
        if not self._survey_queue:
            self._enter_idle(laser_on=False)
            return []
        return []

    def _advance_survey(self, now: float) -> list[bytes]:
        out: list[bytes] = []
        while self.mode == Mode.SURVEYING and now >= self._next_capture:
            if not self._survey_queue:
                self._enter_idle(laser_on=False)
                break
            nid = self._survey_queue.pop(0)
            out.append(self._survey_point_frame(nid))
            self._next_capture += self.capture_period_s
            if not self._survey_queue:
                self._enter_idle(laser_on=False)
                break
        return out

    def _survey_point_frame(self, nid: int) -> bytes:
        node = self.nodes.get(nid)
        if node is None:
            return SurveyPoint(nid, False, Pose()).pack()
        pose = Pose(
            east_mm=node.east_mm,
            north_mm=node.north_mm,
            up_mm=self.last_known_pose.up_mm,
            roll_rad=self.last_known_pose.roll_rad,
            pitch_rad=self.last_known_pose.pitch_rad,
            yaw_rad=self.effective_yaw,
        ).wrapped()
        return SurveyPoint(nid, True, pose).pack()

    def _fail_remaining_survey(self) -> list[bytes]:
        out = [SurveyPoint(nid, False, Pose()).pack() for nid in self._survey_queue]
        self._survey_queue.clear()
        self._enter_idle(laser_on=False)
        return out

    def _begin_projection(self, ids: tuple[int, ...]) -> list[bytes]:
        if self.mode != Mode.IDLE:
            return []
        if not self.origin_is_set or self.moved or not self.nodes:
            return []
        nodes = tuple(self.nodes[i] for i in ids if i in self.nodes)
        if not nodes:
            return []
        self.mode = Mode.PROJECTING
        self.laser_on = True
        self.projection_mode = ProjectionMode.Project
        return [ProjectionStart(nodes).pack()]

    def _tag_found(self, tag_id: int) -> list[bytes]:
        if self.mode != Mode.TAG_SEARCH:
            return []
        self.locked_tags.add(int(tag_id))
        self._enter_idle(laser_on=False)
        if len(self.locked_tags) >= 2:
            self.pose_valid = True
            self.origin_is_set = True
            self.moved = False
            self.last_known_pose = replace(
                self.last_known_pose, yaw_rad=self.effective_yaw
            ).wrapped()
        return []

    def _nudge_x(self, dx_deg: float) -> list[bytes]:
        if self.mode == Mode.SURVEYING:
            return []
        self.effective_yaw = wrap_two_pi(self.effective_yaw + math.radians(dx_deg))
        if self.pose_valid:
            self.last_known_pose = replace(
                self.last_known_pose, yaw_rad=self.effective_yaw
            )
        return []

    def _nudge_y(self, dy_deg: float) -> list[bytes]:
        if self.mode == Mode.SURVEYING:
            return []
        self.beam_el_deg += dy_deg
        return []

    def _set_origin(self, pose: Pose) -> list[bytes]:
        if self.mode not in (Mode.IDLE, Mode.TAG_SEARCH):
            return []
        wrapped = pose.wrapped()
        if self.mode == Mode.TAG_SEARCH:
            self._enter_idle(laser_on=False)
        self.origin_is_set = True
        self.last_known_pose = wrapped
        self.pose_valid = True
        self.moved = False
        self.effective_yaw = wrapped.yaw_rad
        self.mode = Mode.IDLE
        self.laser_on = False
        return []

    def _register_nodes(self, message: RegisterNodes) -> list[bytes]:
        if self.mode != Mode.IDLE:
            return []
        if message.flags is None:
            self.nodes = {n.id: n for n in message.nodes}
            self._pending_nodes.clear()
            return []
        if message.start:
            self._pending_nodes = []
            self.nodes = {}
        self._pending_nodes.extend(message.nodes)
        if message.last:
            self.nodes = {n.id: n for n in self._pending_nodes}
            self._pending_nodes.clear()
        return []
