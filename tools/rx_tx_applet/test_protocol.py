"""Codec and firmware-sim tests for the RX/TX applet (docs/protocol.md)."""

from __future__ import annotations

import math
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from firmware import DeviceFirmware, Mode
from protocol import (
    CDC_BAUD,
    MAX_PAYLOAD,
    PROTOCOL_VERSION,
    BeginProjection,
    BeginSurvey,
    BeginTagSearch,
    Cmd,
    ConnectionStatus,
    FirmwareVersion,
    FrameDecoder,
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
    decode_frame,
    pack_frame,
)


def _fw_linked() -> tuple[DeviceFirmware, float]:
    fw = DeviceFirmware()
    now = 0.0
    frames = fw.on_link_up(now)
    assert decode_frame(frames[0]).cmd == Cmd.FIRMWARE_VERSION
    assert decode_frame(frames[1]).cmd == Cmd.TELEMETRY
    return fw, now


class ProtocolTests(unittest.TestCase):
    def test_firmware_version_spec_example(self) -> None:
        frame = FirmwareVersion(1, "1.4.2").pack()
        self.assertEqual(frame[0], 0x01)
        self.assertEqual(frame[1] | (frame[2] << 8), 6)
        self.assertEqual(frame[3:], bytes((0x01,)) + b"1.4.2")
        decoded = decode_frame(frame)
        assert isinstance(decoded.message, FirmwareVersion)
        self.assertEqual(decoded.message.firmware_version, "1.4.2")
        self.assertEqual(decoded.message.protocol_version, PROTOCOL_VERSION)

    def test_telemetry_is_40_byte_payload(self) -> None:
        pose = Pose(100.0, 200.0, 30.0, 0.1, 0.2, 1.5)
        telem = Telemetry(
            health_ok=True,
            moved=False,
            origin_is_set=True,
            is_surveying=False,
            survey_ready=True,
            is_projecting=False,
            laser_on=False,
            connection_status=ConnectionStatus.Linked,
            projection_mode=ProjectionMode.Off,
            laser_power=80,
            pose_valid=True,
            reserved=0,
            effective_yaw=1.5,
            last_known_pose=pose,
        )
        frame = telem.pack()
        self.assertEqual(frame[0], Cmd.TELEMETRY)
        self.assertEqual(len(frame) - 3, 40)
        back = decode_frame(frame).message
        assert isinstance(back, Telemetry)
        self.assertTrue(back.health_ok)
        self.assertTrue(back.survey_ready)
        self.assertEqual(back.laser_power, 80)
        self.assertAlmostEqual(back.last_known_pose.east_mm, 100.0, places=4)
        self.assertAlmostEqual(back.effective_yaw, 1.5, places=5)

    def test_pose_angles_wrap(self) -> None:
        pose = Pose(yaw_rad=-0.1).wrapped()
        self.assertGreaterEqual(pose.yaw_rad, 0.0)
        self.assertLess(pose.yaw_rad, math.tau)
        self.assertAlmostEqual(pose.yaw_rad, math.tau - 0.1, places=5)

    def test_tablet_commands_roundtrip(self) -> None:
        node = Node(1, 10.0, 20.0, math.pi / 2, 0.3)
        samples = [
            SetLaserPower(80),
            SetProjectionMode(ProjectionMode.Off),
            BeginTagSearch(),
            BeginSurvey((1, 2, 7)),
            BeginProjection((3,)),
            TagFound(2),
            NudgeBeamX(0.5),
            NudgeBeamY(-0.05),
            SetOrigin(Pose(0.0, 0.0, 0.0, 0.0, 0.0, 1.2)),
            RegisterNodes((node,)),
        ]
        for msg in samples:
            raw = msg.pack()
            decoded = decode_frame(raw)
            self.assertIsNone(decoded.error, decoded.error)
            self.assertEqual(type(decoded.message), type(msg))
            self.assertEqual(decoded.message.pack(), raw)

    def test_register_nodes_chunk_flags(self) -> None:
        a = Node(1, 0.0, 0.0, 0.0, 0.0)
        b = Node(2, 1.0, 2.0, 0.1, 0.2)
        first = RegisterNodes((a,), flags=0x01)
        last = RegisterNodes((b,), flags=0x02)
        d0 = decode_frame(first.pack()).message
        d1 = decode_frame(last.pack()).message
        assert isinstance(d0, RegisterNodes)
        assert isinstance(d1, RegisterNodes)
        self.assertTrue(d0.start)
        self.assertFalse(d0.last)
        self.assertFalse(d1.start)
        self.assertTrue(d1.last)

    def test_survey_point_roundtrip(self) -> None:
        msg = SurveyPoint(1, True, Pose(east_mm=5.0, north_mm=6.0, yaw_rad=0.4))
        back = decode_frame(msg.pack()).message
        self.assertEqual(back.pack(), msg.pack())
        self.assertEqual(back.node_id, 1)
        self.assertTrue(back.success)
        miss = SurveyPoint(7, False, Pose())
        miss_back = decode_frame(miss.pack()).message
        self.assertFalse(miss_back.success)
        self.assertEqual(miss_back.node_id, 7)

    def test_projection_start_roundtrip(self) -> None:
        node = Node(4, 9.0, 8.0, 0.5, 1.0)
        msg = ProjectionStart((node,))
        back = decode_frame(msg.pack()).message
        self.assertEqual(back.pack(), msg.pack())
        self.assertEqual(back.nodes[0].id, 4)

    def test_header_is_little_endian_length(self) -> None:
        payload = b"\x00" * 300
        frame = pack_frame(Cmd.REGISTER_NODES, payload)
        self.assertEqual(frame[1], 300 & 0xFF)
        self.assertEqual(frame[2], (300 >> 8) & 0xFF)

    def test_bad_length_is_error_not_exception(self) -> None:
        frame = pack_frame(Cmd.SET_LASER_POWER, b"\x01\x02")
        decoded = decode_frame(frame)
        self.assertIsNone(decoded.message)
        self.assertIsNotNone(decoded.error)

    def test_streaming_decoder_handles_split_reads(self) -> None:
        decoder = FrameDecoder(accept=TABLET_CMDS)
        frame = BeginSurvey((1, 2, 3)).pack()
        self.assertEqual(list(decoder.feed(frame[:1])), [])
        self.assertEqual(list(decoder.feed(frame[1:4])), [])
        got = list(decoder.feed(frame[4:]))
        self.assertEqual(len(got), 1)
        assert isinstance(got[0].message, BeginSurvey)
        self.assertEqual(got[0].message.ids, (1, 2, 3))

    def test_streaming_decoder_resyncs_on_unknown_cmd(self) -> None:
        decoder = FrameDecoder(accept=TABLET_CMDS)
        good = SetLaserPower(40).pack()
        junk = bytes((0x99, 0x00, 0x00)) + good
        frames = list(decoder.feed(junk[:2]))
        self.assertEqual(frames, [])
        frames = list(decoder.feed(junk[2:]))
        self.assertEqual(len(frames), 1)
        assert isinstance(frames[0].message, SetLaserPower)
        self.assertEqual(frames[0].message.duty, 40)
        self.assertGreater(decoder.dropped_bytes, 0)

    def test_bool_nonzero_is_true(self) -> None:
        payload = bytearray(40)
        payload[0] = 2  # healthOK
        payload[7] = 0  # Linked
        frame = pack_frame(Cmd.TELEMETRY, bytes(payload))
        msg = decode_frame(frame).message
        assert isinstance(msg, Telemetry)
        self.assertTrue(msg.health_ok)

    def test_pack_rejects_pairing_cmd_and_oversize(self) -> None:
        with self.assertRaises(ValueError):
            pack_frame(0xE5, b"")
        with self.assertRaises(ValueError):
            pack_frame(Cmd.TELEMETRY, b"\x00" * (MAX_PAYLOAD + 1))

    def test_pairing_frame_is_skipped_whole(self) -> None:
        from protocol import PAIRING_CMD

        decoder = FrameDecoder(accept=TABLET_CMDS)
        good = SetLaserPower(40).pack()
        pairing = bytes((PAIRING_CMD, 4, 0, 0x10, 0x01, 0x02, 0x03))
        frames = list(decoder.feed(pairing + good))
        self.assertEqual(len(frames), 1)
        assert isinstance(frames[0].message, SetLaserPower)
        self.assertEqual(frames[0].message.duty, 40)
        self.assertEqual(decoder.dropped_bytes, len(pairing))

    def test_zero_length_payload_is_valid_frame(self) -> None:
        decoder = FrameDecoder(accept=TABLET_CMDS)
        empty = BeginTagSearch().pack()
        self.assertEqual(empty, bytes((Cmd.BEGIN_TAG_SEARCH, 0, 0)))
        got = list(decoder.feed(empty))
        self.assertEqual(len(got), 1)
        assert isinstance(got[0].message, BeginTagSearch)

    def test_dongle_host_constants(self) -> None:
        from dongle_host import ESPRESSIF_PID, ESPRESSIF_VID

        self.assertEqual(CDC_BAUD, 921600)
        self.assertEqual(ESPRESSIF_VID, 0x303A)
        self.assertEqual(ESPRESSIF_PID, 0x1001)
        self.assertEqual(MAX_PAYLOAD, 1021)


class FirmwareTests(unittest.TestCase):
    def test_link_up_sends_version_then_telemetry(self) -> None:
        fw, _ = _fw_linked()
        self.assertEqual(fw.mode, Mode.IDLE)
        self.assertFalse(fw.survey_ready)

    def test_origin_and_nodes_make_survey_ready(self) -> None:
        fw, now = _fw_linked()
        node = Node(1, 100.0, 200.0, math.pi / 2, 0.0)
        fw.handle_message(RegisterNodes((node,)), now)
        fw.handle_message(SetLaserPower(80), now)
        self.assertEqual(fw.laser_power, 80)
        fw.handle_message(SetOrigin(Pose(yaw_rad=0.7)), now)
        self.assertTrue(fw.origin_is_set)
        self.assertTrue(fw.pose_valid)
        self.assertTrue(fw.survey_ready)
        self.assertAlmostEqual(fw.effective_yaw, 0.7, places=5)

    def test_begin_survey_ignored_until_ready(self) -> None:
        fw, now = _fw_linked()
        out = fw.handle_message(BeginSurvey((1,)), now)
        self.assertEqual(out, [])
        self.assertEqual(fw.mode, Mode.IDLE)

    def test_survey_emits_point_updates_then_idle(self) -> None:
        fw, now = _fw_linked()
        fw.capture_period_s = 0.05
        nodes = (
            Node(1, 0.0, 0.0, 0.0, 0.0),
            Node(2, 10.0, 0.0, 0.0, 0.0),
        )
        fw.handle_message(RegisterNodes(nodes), now)
        fw.handle_message(SetOrigin(Pose()), now)
        out = fw.handle_message(BeginSurvey((1, 2, 99)), now)
        self.assertEqual(out, [])
        self.assertEqual(fw.mode, Mode.SURVEYING)
        self.assertTrue(fw.is_surveying)
        points = []
        for _ in range(3):
            now += fw.capture_period_s
            frames = fw.tick(now)
            for raw in frames:
                decoded = decode_frame(raw)
                if decoded.cmd == Cmd.SURVEY_POINT:
                    points.append(decoded.message)
        self.assertEqual(len(points), 3)
        self.assertEqual([p.node_id for p in points], [1, 2, 99])
        self.assertTrue(points[0].success)
        self.assertTrue(points[1].success)
        self.assertFalse(points[2].success)
        self.assertEqual(fw.mode, Mode.IDLE)
        self.assertFalse(fw.laser_on)
        self.assertFalse(fw.is_surveying)

    def test_projection_start_omits_unknown_ids(self) -> None:
        fw, now = _fw_linked()
        fw.handle_message(RegisterNodes((Node(1, 0.0, 1.0, 0.0, 0.0),)), now)
        fw.handle_message(SetOrigin(Pose()), now)
        frames = fw.handle_message(BeginProjection((1, 50)), now)
        self.assertEqual(len(frames), 1)
        msg = decode_frame(frames[0]).message
        assert isinstance(msg, ProjectionStart)
        self.assertEqual([n.id for n in msg.nodes], [1])
        self.assertEqual(fw.mode, Mode.PROJECTING)
        self.assertTrue(fw.laser_on)

    def test_empty_projection_stays_idle(self) -> None:
        fw, now = _fw_linked()
        fw.handle_message(RegisterNodes((Node(1, 0.0, 0.0, 0.0, 0.0),)), now)
        fw.handle_message(SetOrigin(Pose()), now)
        frames = fw.handle_message(BeginProjection((50,)), now)
        self.assertEqual(frames, [])
        self.assertEqual(fw.mode, Mode.IDLE)

    def test_tag_search_and_two_tags_validate_pose(self) -> None:
        fw, now = _fw_linked()
        fw.handle_message(SetOrigin(Pose()), now)
        fw.handle_message(BeginTagSearch(), now)
        self.assertEqual(fw.mode, Mode.TAG_SEARCH)
        self.assertTrue(fw.laser_on)
        fw.handle_message(TagFound(1), now)
        self.assertEqual(fw.mode, Mode.IDLE)
        fw.handle_message(BeginTagSearch(), now)
        fw.handle_message(TagFound(2), now)
        self.assertTrue(fw.pose_valid)
        self.assertFalse(fw.moved)

    def test_tag_search_without_origin_identifies_pose(self) -> None:
        fw, now = _fw_linked()
        fw.handle_message(BeginTagSearch(), now)
        self.assertEqual(fw.mode, Mode.TAG_SEARCH)
        fw.handle_message(TagFound(1), now)
        fw.handle_message(BeginTagSearch(), now)
        fw.handle_message(TagFound(2), now)
        self.assertTrue(fw.pose_valid)
        self.assertTrue(fw.origin_is_set)
        self.assertFalse(fw.moved)
        self.assertAlmostEqual(fw.last_known_pose.yaw_rad, fw.effective_yaw)

    def test_nudge_is_ignored_while_surveying(self) -> None:
        fw, now = _fw_linked()
        fw.handle_message(RegisterNodes((Node(1, 0.0, 0.0, 0.0, 0.0),)), now)
        fw.handle_message(SetOrigin(Pose(yaw_rad=0.0)), now)
        fw.handle_message(BeginSurvey((1,)), now)
        yaw = fw.effective_yaw
        fw.handle_message(NudgeBeamX(10.0), now)
        self.assertAlmostEqual(fw.effective_yaw, yaw)
        fw.handle_message(SetProjectionMode(ProjectionMode.Off), now)
        self.assertEqual(fw.mode, Mode.IDLE)

    def test_moved_aborts_projection(self) -> None:
        fw, now = _fw_linked()
        fw.handle_message(RegisterNodes((Node(1, 0.0, 0.0, 0.0, 0.0),)), now)
        fw.handle_message(SetOrigin(Pose()), now)
        fw.handle_message(BeginProjection((1,)), now)
        frames = fw.set_moved(True, now)
        self.assertEqual(fw.mode, Mode.IDLE)
        self.assertFalse(fw.pose_valid)
        self.assertFalse(fw.survey_ready)
        self.assertTrue(fw.moved)
        self.assertFalse(any(decode_frame(f).cmd == Cmd.SURVEY_POINT for f in frames))

    def test_chunked_register_commits_on_last(self) -> None:
        fw, now = _fw_linked()
        a = Node(1, 0.0, 0.0, 0.0, 0.0)
        b = Node(2, 1.0, 0.0, 0.0, 0.0)
        fw.handle_message(RegisterNodes((a,), flags=0x01), now)
        self.assertEqual(fw.nodes, {})
        fw.handle_message(RegisterNodes((b,), flags=0x02), now)
        self.assertEqual(set(fw.nodes), {1, 2})

    def test_moved_during_survey_fails_remaining_and_clears_tags(self) -> None:
        fw, now = _fw_linked()
        fw.capture_period_s = 0.05
        fw.handle_message(RegisterNodes((Node(1, 0.0, 0.0, 0.0, 0.0), Node(2, 1.0, 0.0, 0.0, 0.0))), now)
        fw.handle_message(SetOrigin(Pose()), now)
        fw.handle_message(BeginTagSearch(), now)
        fw.handle_message(TagFound(1), now)
        fw.handle_message(BeginTagSearch(), now)
        fw.handle_message(TagFound(2), now)
        self.assertEqual(fw.locked_tags, {1, 2})
        fw.handle_message(BeginSurvey((1, 2)), now)
        now += fw.capture_period_s
        fw.tick(now)
        frames = fw.set_moved(True, now)
        points = [
            decode_frame(f).message
            for f in frames
            if decode_frame(f).cmd == Cmd.SURVEY_POINT
        ]
        self.assertEqual(len(points), 1)
        self.assertEqual(points[0].node_id, 2)
        self.assertFalse(points[0].success)
        self.assertEqual(fw.mode, Mode.IDLE)
        self.assertEqual(fw.locked_tags, set())
        self.assertFalse(fw.pose_valid)

    def test_jobs_ignored_outside_idle(self) -> None:
        fw, now = _fw_linked()
        fw.handle_message(SetOrigin(Pose()), now)
        fw.handle_message(BeginTagSearch(), now)
        fw.handle_message(RegisterNodes((Node(1, 0.0, 0.0, 0.0, 0.0),)), now)
        self.assertEqual(fw.nodes, {})
        fw.handle_message(BeginSurvey((1,)), now)
        self.assertEqual(fw.mode, Mode.TAG_SEARCH)


if __name__ == "__main__":
    unittest.main()
