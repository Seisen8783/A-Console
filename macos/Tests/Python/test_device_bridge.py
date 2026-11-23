from argparse import Namespace
import asyncio
from contextlib import asynccontextmanager, redirect_stdout
from io import StringIO
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import AsyncMock, patch

from pymobiledevice3.exceptions import ConnectionTerminatedError

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "Sources/UnRealConsole/Resources"))
from device_bridge import ReplayCommands, main, operate


class DeviceBridgeTests(unittest.IsolatedAsyncioTestCase):
    async def run_operation(self, device, stop, action="play"):
        @asynccontextmanager
        async def connect(serial):
            self.assertEqual(serial, "target")
            yield device

        points = [
            {"latitude": 37.0, "longitude": 117.0, "offset": 0},
            {"latitude": 37.01, "longitude": 117.0, "offset": 0},
        ]
        output = StringIO()
        with redirect_stdout(output):
            code = await operate(action, "target", points, stop, connect)
        return code, [json.loads(line) for line in output.getvalue().splitlines()]

    async def test_completion_returns_to_start_and_keeps_position(self):
        device = type("Device", (), {"set": AsyncMock(), "clear": AsyncMock()})()
        with patch("device_bridge.wait_for_stop", return_value=False):
            code, events = await self.run_operation(device, asyncio.Event())
        self.assertEqual(code, 0)
        # 走完全程后，最后一次写入应是起点坐标。
        self.assertEqual([event["index"] for event in events if event["kind"] == "point"], [0, 1, 0])
        self.assertEqual(device.set.await_args_list[-1].args, (37.0, 117.0))
        # 位置保持在起点，不应恢复真实定位。
        device.clear.assert_not_awaited()
        self.assertFalse(events[-1]["clean"])
        self.assertIn("回到起点", events[-1]["message"])

    async def test_stop_after_first_write_clears(self):
        stop = asyncio.Event()
        device = type("Device", (), {"set": AsyncMock(side_effect=lambda *_: stop.set()), "clear": AsyncMock()})()
        code, events = await self.run_operation(device, stop)
        self.assertEqual(code, 0)
        self.assertEqual(device.set.await_count, 2)
        device.clear.assert_not_awaited()
        self.assertFalse(events[-1]["clean"])

    async def test_return_to_start_failure_reports_error(self):
        stop = asyncio.Event()
        writes = 0
        async def write(*_):
            nonlocal writes
            writes += 1
            if writes == 1:
                stop.set()
            else:
                raise ConnectionError("disconnected")
        device = type("Device", (), {"set": AsyncMock(side_effect=write), "clear": AsyncMock()})()
        code, events = await self.run_operation(device, stop)
        self.assertEqual(code, 1)
        self.assertFalse(events[-1]["ok"])
        self.assertFalse(events[-1]["clean"])
        device.clear.assert_not_awaited()

    async def test_explicit_reset_clears_active_session(self):
        stop = asyncio.Event()
        controls = ReplayCommands()
        async def reset_after_write(*_):
            controls.reset_requested = True
            stop.set()
        device = type("Device", (), {"set": AsyncMock(side_effect=reset_after_write), "clear": AsyncMock()})()
        @asynccontextmanager
        async def connect(_):
            yield device
        points = [{"latitude": 37.0, "longitude": 117.0, "offset": 0}]
        with redirect_stdout(StringIO()):
            code = await operate("anchor", "target", points, stop, connect, controls)
        self.assertEqual(code, 0)
        device.clear.assert_awaited_once()

    async def test_stop_before_connect_does_not_write(self):
        stop = asyncio.Event()
        stop.set()
        device = type("Device", (), {"set": AsyncMock(), "clear": AsyncMock()})()
        code, events = await self.run_operation(device, stop)
        self.assertEqual(code, 0)
        device.set.assert_not_awaited()
        device.clear.assert_not_awaited()
        self.assertTrue(events[-1]["clean"])

    async def test_failed_clear_leaves_pending(self):
        device = type("Device", (), {
            "set": AsyncMock(side_effect=ConnectionError("USB disconnected")),
            "clear": AsyncMock(side_effect=ConnectionError("USB disconnected")),
        })()
        code, events = await self.run_operation(device, asyncio.Event())
        self.assertEqual(code, 1)
        self.assertFalse(events[-1]["clean"])

    async def test_closed_channel_retries_same_point_without_rewinding(self):
        devices = [
            type("Device", (), {"set": AsyncMock(side_effect=[None, ConnectionTerminatedError("closed")])})(),
            type("Device", (), {"set": AsyncMock()})(),
        ]
        connections = []

        @asynccontextmanager
        async def connect(serial):
            device = devices[len(connections)]
            connections.append(serial)
            yield device

        points = [dict(latitude=37.0 + i / 1000, longitude=118.0, offset=0) for i in range(3)]
        output = StringIO()
        with redirect_stdout(output), patch("device_bridge.wait_for_stop", return_value=False):
            code = await operate("play", "target", points, asyncio.Event(), connect)

        self.assertEqual(code, 0)
        self.assertEqual(connections, ["target", "target"])
        self.assertEqual(
            [call.args[0] for call in devices[1].set.await_args_list],
            [points[1]["latitude"], points[2]["latitude"], points[0]["latitude"]],
        )
        events = [json.loads(line) for line in output.getvalue().splitlines()]
        self.assertEqual([event["index"] for event in events if event["kind"] == "point"], [0, 1, 2, 0])

    async def test_seek_forward_then_restart_in_same_session(self):
        controls = ReplayCommands()
        stop = asyncio.Event()
        visited = []

        async def write(latitude, _):
            visited.append(latitude)
            if len(visited) == 1:
                controls.seek(2)
            elif len(visited) == 2:
                controls.seek(0)
            else:
                stop.set()
                controls.changed.set()

        device = type("Device", (), {"set": AsyncMock(side_effect=write), "clear": AsyncMock()})()

        @asynccontextmanager
        async def connect(_):
            yield device

        points = [
            {"latitude": 37.0 + index, "longitude": 117.0, "offset": index * 120}
            for index in range(3)
        ]
        with redirect_stdout(StringIO()):
            code = await asyncio.wait_for(operate("play", "target", points, stop, connect, controls), 2)
        self.assertEqual(code, 0)
        self.assertEqual(visited, [37.0, 39.0, 37.0, 37.0])
        device.clear.assert_not_awaited()

    async def test_anchor_sets_once_and_clears_on_stop(self):
        stop = asyncio.Event()
        device = type("Device", (), {"set": AsyncMock(side_effect=lambda *_: stop.set()), "clear": AsyncMock()})()
        code, events = await self.run_operation(device, stop, action="anchor")
        self.assertEqual(code, 0)
        self.assertEqual(device.set.await_count, 2)
        device.clear.assert_not_awaited()
        self.assertFalse(events[-1]["clean"])

    async def test_anchor_and_pause_resume_without_clearing_session(self):
        points = [
            {"latitude": 37.0 + index, "longitude": 117.0, "offset": index * 0.01}
            for index in range(3)
        ]
        stop = asyncio.Event()
        controls = ReplayCommands()
        device = type("Device", (), {"set": AsyncMock(), "clear": AsyncMock()})()
        connected = 0
        initial_hold = asyncio.Event()
        second_hold = asyncio.Event()

        @asynccontextmanager
        async def connect(_):
            nonlocal connected
            connected += 1
            yield device

        def emit(kind, **values):
            if kind == "point":
                if values["index"] == 1:
                    controls.set_paused(True)
                elif values["index"] == 2:
                    stop.set()
            elif kind == "mode" and values["paused"]:
                if values["message"] == "已定位到起点":
                    initial_hold.set()
                else:
                    second_hold.set()

        with patch("device_bridge.emit", side_effect=emit):
            task = asyncio.create_task(operate("anchor", "target", points, stop, connect, controls))
            try:
                await asyncio.wait_for(initial_hold.wait(), 1)
                await asyncio.sleep(0.03)
                self.assertEqual(device.set.await_count, 1)
                device.clear.assert_not_awaited()
                controls.set_paused(False)
                await asyncio.wait_for(second_hold.wait(), 1)
                await asyncio.sleep(0.03)
                self.assertEqual(device.set.await_count, 2)
                device.clear.assert_not_awaited()
                controls.set_paused(False)
                self.assertEqual(await asyncio.wait_for(task, 1), 0)
            finally:
                if not task.done():
                    stop.set()
                    controls.changed.set()
                    await task

        self.assertEqual(connected, 1)
        self.assertEqual([call.args[0] for call in device.set.await_args_list], [37.0, 38.0, 39.0, 37.0])
        device.clear.assert_not_awaited()

    async def test_paused_update_continues_from_same_point_within_one_session(self):
        points = [
            {"latitude": 37.0, "longitude": 117.0, "offset": 0},
            {"latitude": 38.0, "longitude": 117.0, "offset": 30},
        ]
        updated = [
            points[0],
            {"latitude": 38.0, "longitude": 117.0, "offset": 0.01},
            {"latitude": 37.0, "longitude": 117.0, "offset": 0.02},
        ]
        stop = asyncio.Event()
        controls = ReplayCommands(lambda: updated)
        device = type("Device", (), {"set": AsyncMock(), "clear": AsyncMock()})()
        held = asyncio.Event()
        paused = asyncio.Event()
        accepted = asyncio.Event()
        connection_count = 0

        @asynccontextmanager
        async def connect(_):
            nonlocal connection_count
            connection_count += 1
            yield device

        def event(kind, **values):
            if kind == "point" and values["index"] == 0:
                controls.set_paused(True)
                held.set()
            elif kind == "mode" and values["paused"]:
                paused.set()
            elif kind == "updated":
                accepted.set()
            elif kind == "point" and values["index"] == 2:
                stop.set()

        with patch("device_bridge.emit", side_effect=event):
            task = asyncio.create_task(operate("play", "target", points, stop, connect, controls))
            try:
                await asyncio.wait_for(held.wait(), 1)
                await asyncio.wait_for(paused.wait(), 1)
                device.clear.assert_not_awaited()
                controls.apply_update(restarting=False)
                await asyncio.wait_for(accepted.wait(), 1)
                self.assertEqual(await asyncio.wait_for(task, 1), 0)
            finally:
                if not task.done():
                    stop.set()
                    controls.changed.set()
                    await task

        self.assertEqual(connection_count, 1)
        self.assertEqual([call.args[0] for call in device.set.await_args_list], [37.0, 38.0, 37.0, 37.0])
        device.clear.assert_not_awaited()

    async def test_update_while_anchored_stays_paused_until_resume(self):
        points = [
            {"latitude": 37.0, "longitude": 117.0, "offset": 0},
            {"latitude": 38.0, "longitude": 117.0, "offset": 0.01},
        ]
        updated = [points[0], {"latitude": 39.0, "longitude": 117.0, "offset": 0.01}]
        controls = ReplayCommands(lambda: updated)
        stop = asyncio.Event()
        held = asyncio.Event()
        applied = asyncio.Event()
        device = type("Device", (), {"set": AsyncMock(), "clear": AsyncMock()})()

        @asynccontextmanager
        async def connect(_):
            yield device

        def event(kind, **values):
            if kind == "mode" and values.get("message") == "已定位到起点":
                held.set()
            elif kind == "updated":
                self.assertTrue(values["paused"])
                applied.set()
            elif kind == "point" and values.get("index") == 1:
                stop.set()
                controls.changed.set()

        with patch("device_bridge.emit", side_effect=event):
            task = asyncio.create_task(operate("anchor", "target", points, stop, connect, controls))
            try:
                await asyncio.wait_for(held.wait(), 1)
                controls.apply_update(False, hold=True)
                await asyncio.wait_for(applied.wait(), 1)
                await asyncio.sleep(0.05)
                device.set.assert_awaited_once_with(37.0, 117.0)
                controls.set_paused(False)
                await asyncio.wait_for(task, 1)
                self.assertEqual(device.set.await_args_list[1].args, (39.0, 117.0))
            finally:
                if not task.done():
                    stop.set()
                    controls.changed.set()
                    await task

    async def test_restore_command_has_no_route_argument(self):
        with patch("device_bridge.operate", new_callable=AsyncMock, return_value=0) as run:
            code = await main(Namespace(action="restore", serial="target"))
        self.assertEqual(code, 0)
        action, serial, points, _ = run.await_args.args
        self.assertEqual((action, serial, points), ("restore", "target", None))

    async def test_restore_targets_device_without_writing_position(self):
        device = type("Device", (), {"set": AsyncMock(), "clear": AsyncMock()})()
        code, events = await self.run_operation(device, asyncio.Event(), action="restore")
        self.assertEqual(code, 0)
        device.set.assert_not_awaited()
        device.clear.assert_awaited_once()
        self.assertTrue(events[-1]["clean"])
