"""USB device and DVT operations for the UnReal desktop console."""

import argparse
import asyncio
from contextlib import AsyncExitStack, asynccontextmanager, suppress
import json
import os
from pathlib import Path
import signal
import sys

from pymobiledevice3 import usbmux
from pymobiledevice3.exceptions import (
    AlreadyMountedError, ConnectionFailedError, ConnectionTerminatedError,
    DeviceNotFoundError, InvalidServiceError, NoDeviceConnectedError,
    NotPairedError, NotTrustedError, PairingDialogResponsePendingError,
    PasswordRequiredError, UserDeniedPairingError,
)
from pymobiledevice3.lockdown import create_using_usbmux
from pymobiledevice3.remote.rsd_tunnel import PreferredRsdTunnel
from pymobiledevice3.services.dvt.instruments.dvt_provider import DvtProvider
from pymobiledevice3.services.dvt.instruments.location_simulation import LocationSimulation
from pymobiledevice3.services.mobile_image_mounter import auto_mount


def emit(kind, **values):
    print(json.dumps(dict(kind=kind, **values), ensure_ascii=False), flush=True)


async def list_usb_devices():
    devices = []
    for device in await usbmux.list_devices():
        if not device.is_usb:
            continue
        info = dict(id=device.serial, name="USB 设备", version="", detail="请解锁设备并信任此 Mac")
        try:
            async with await asyncio.wait_for(
                create_using_usbmux(serial=device.serial, connection_type="USB", autopair=False), 3
            ) as lockdown:
                values = lockdown.all_values
                if values.get("DeviceClass") != "iPhone":
                    continue
                info.update(name=lockdown.display_name, version=lockdown.product_version,
                            detail="USB 已连接" if lockdown.paired else "请解锁设备并信任此 Mac")
        except Exception:
            pass
        devices.append(info)
    emit("devices", devices=devices)


@asynccontextmanager
async def location_session(serial):
    if not any(device.is_usb and device.serial == serial for device in await usbmux.list_devices()):
        raise DeviceNotFoundError(serial, "找不到所选 USB 设备，请重新连接。")
    emit("state", message="检查设备信任和开发者模式")
    async with await asyncio.wait_for(
        create_using_usbmux(serial=serial, connection_type="USB", pair_timeout=25), 35
    ) as lockdown:
        if lockdown.all_values.get("DeviceClass") != "iPhone":
            raise RuntimeError("请选择 iPhone。")
        if int(lockdown.product_version.split(".")[0]) < 17:
            raise RuntimeError("此控制台支持 iOS 17 及以上。")
        if not await asyncio.wait_for(lockdown.get_developer_mode_status(), 8):
            raise RuntimeError("请在 iPhone 的「设置 → 隐私与安全性」开启开发者模式。")

    emit("state", message="连接 iOS 开发者服务")
    async with PreferredRsdTunnel(serial=serial, autopair=False) as rsd:
        try:
            rsd.get_service_port(DvtProvider.RSD_SERVICE_NAME)
        except InvalidServiceError:
            emit("state", message="准备开发者镜像，首次使用可能需要下载")
            with suppress(AlreadyMountedError):
                await auto_mount(rsd)
        else:
            async with DvtProvider(rsd) as dvt, LocationSimulation(dvt) as location:
                yield location
            return

    emit("state", message="重新连接开发者服务")
    async with PreferredRsdTunnel(serial=serial, autopair=False) as rsd:
        async with DvtProvider(rsd) as dvt, LocationSimulation(dvt) as location:
            yield location


async def wait_for_stop(stop, delay):
    if stop.is_set():
        return True
    if delay <= 0:
        return False
    try:
        await asyncio.wait_for(stop.wait(), delay)
    except asyncio.TimeoutError:
        return False
    return True


CONNECTION_ERRORS = (
    ConnectionFailedError, ConnectionTerminatedError, DeviceNotFoundError,
    NoDeviceConnectedError, ConnectionError, TimeoutError,
)


class LocationConnection:
    """Own the developer connection and retry a command once on a fresh channel."""

    def __init__(self, serial, connect):
        self.serial = serial
        self.connect = connect
        self.stack = AsyncExitStack()
        self.location = None

    async def open(self, timeout):
        async with asyncio.timeout(timeout):
            self.location = await self.stack.enter_async_context(self.connect(self.serial))

    async def close(self):
        self.location = None
        async with asyncio.timeout(3):
            await self.stack.aclose()

    async def reconnect(self):
        emit("state", message="设备连接中断，正在重新连接同一台 iPhone")
        with suppress(*CONNECTION_ERRORS):
            await self.close()
        try:
            await self.open(12)
        except CONNECTION_ERRORS:
            await asyncio.sleep(1)
            await self.open(12)

    async def request(self, method, *args, timeout, stop=None):
        async def send():
            await asyncio.wait_for(getattr(self.location, method)(*args), timeout)

        if stop is not None and stop.is_set():
            return None
        started = asyncio.get_running_loop().time()
        try:
            await send()
            return 0.0
        except CONNECTION_ERRORS:
            await self.reconnect()
            if stop is not None and stop.is_set():
                return None
            await send()
            return asyncio.get_running_loop().time() - started


class ReplayCommands:
    def __init__(self, read_points=None):
        self.read_points = read_points
        self.restart_with_update = None
        self.changed = asyncio.Event()
        self.target = None
        self.paused = None
        self.reset_requested = False

    def seek(self, index):
        self.target = index
        self.changed.set()

    def set_paused(self, paused):
        self.paused = paused
        self.changed.set()

    def apply_update(self, restarting, hold=False):
        self.restart_with_update = (restarting, hold)
        self.changed.set()

    def take(self):
        target, paused, update = self.target, self.paused, self.restart_with_update
        self.target = self.paused = self.restart_with_update = None
        self.changed.clear()
        return target, paused, update


async def replay(location, points, stop, on_write, commands=None, initial_index=0, anchor=False):
    loop = asyncio.get_running_loop()
    index = min(max(0, initial_index), len(points) - 1)
    last_index = None
    start = loop.time() - points[index]["offset"]
    paused = anchor
    force_point = True
    finished_at = 0.0

    while not stop.is_set():
        if commands is not None:
            target, requested_pause, update = commands.take()
            if update is not None:
                restarting, hold = update
                try:
                    if not paused or last_index is None or target is not None:
                        raise ValueError("请等待暂停或跳转完成后再继续")
                    updated = commands.read_points()
                    if not restarting and (
                        last_index >= len(updated) or updated[last_index] != points[last_index]
                    ):
                        raise ValueError("更新后的暂停位置不一致")
                except (OSError, ValueError) as error:
                    emit("update-error", message=f"回放设置未更新：{error}")
                else:
                    points = updated
                    if restarting:
                        target = 0
                    if not hold:
                        requested_pause = False
                    emit("updated", index=None if restarting else last_index, paused=hold)
            if target is not None:
                index = min(max(0, target), len(points) - 1)
                start = loop.time() - points[index]["offset"]
                force_point = True
            if requested_pause is not None and paused != requested_pause:
                paused = requested_pause
                if not paused and not force_point and last_index is not None:
                    start = loop.time() - points[last_index]["offset"]
                    finished_at = loop.time() + 1
                emit("mode", paused=paused, message="已暂停，保持当前位置" if paused else "正在回放")

        if paused and not force_point:
            if commands is None:
                await stop.wait()
            else:
                await commands.changed.wait()
            continue

        at_end = index == len(points)
        deadline = finished_at if at_end else start + points[index]["offset"]
        delay = deadline - loop.time()
        if commands is None:
            if await wait_for_stop(stop, delay):
                break
        elif delay > 0:
            try:
                await asyncio.wait_for(commands.changed.wait(), delay)
                continue
            except asyncio.TimeoutError:
                if stop.is_set() or commands.changed.is_set():
                    continue
        if at_end:
            # 自然结束：把设备位置退回起点并保持，不恢复真实定位。
            await return_to_start(location, points, on_write)
            if commands is None:
                return False
            index = 1
            last_index = 0
            paused = True
            force_point = False
            continue

        point = points[index]
        on_write()
        recovery_delay = await location.request(
            "set", point["latitude"], point["longitude"], timeout=10, stop=stop,
        )
        if recovery_delay is None:
            break
        if recovery_delay:
            start += recovery_delay
            if not (anchor and last_index is None and paused):
                message = "已重连，保持暂停" if paused else "已重连，正在回放"
                emit("mode", paused=paused, message=message)
        emit("point", index=index, elapsed=point["offset"])
        if anchor and last_index is None and paused:
            emit("mode", paused=True, message="已定位到起点")
        last_index = index
        force_point = False
        index += 1
        if index == len(points):
            finished_at = loop.time() + 1
    return True


async def return_to_start(location, points, on_write):
    """回放结束后把位置写回轨迹起点，并保持在该位置。"""
    if not points:
        return
    origin = points[0]
    on_write()
    await location.request("set", origin["latitude"], origin["longitude"], timeout=10)
    emit("point", index=0, elapsed=0)
    emit("mode", paused=True, message="回放结束，已回到起点")


async def operate(action, serial, points, stop, connect=location_session, commands=None, initial_index=0):
    pending = action == "restore"
    stopped = False
    keep_position = False

    def mark_write():
        nonlocal pending
        pending = True

    location = LocationConnection(serial, connect)
    try:
        async with AsyncExitStack() as stack:
            if action in ("play", "anchor") and stop.is_set():
                emit("result", clean=True, ok=True, message="已停止回放")
                return 0
            stack.push_async_callback(location.close)
            try:
                await location.open(180)
            except CONNECTION_ERRORS:
                await location.reconnect()
            try:
                if action in ("play", "anchor"):
                    if action == "play":
                        emit("mode", paused=False, message="正在回放")
                    stopped = await replay(location, points, stop, mark_write, commands,
                                           initial_index, anchor=action == "anchor")
                    # 自然结束（未中止）时，位置已退回起点，保持不动，不恢复真实定位。
                    keep_position = not stopped
            finally:
                if action == "restore" or (commands is not None and commands.reset_requested):
                    emit("state", message="正在重置位置")
                    await location.request("clear", timeout=8)
                    pending = False
                    emit("cleared")
                elif pending and stopped:
                    await return_to_start(location, points, mark_write)

    except Exception as error:
        if isinstance(error, (DeviceNotFoundError, NoDeviceConnectedError)):
            message = "找不到这台 iPhone。请检查 USB 数据线和连接，再刷新设备列表。"
        elif isinstance(error, (NotPairedError, NotTrustedError, PairingDialogResponsePendingError,
                                PasswordRequiredError, UserDeniedPairingError)):
            message = "请解锁 iPhone，在手机上确认「信任此电脑」后重试。"
        elif isinstance(error, CONNECTION_ERRORS):
            message = "iPhone 开发者服务连接失败。请检查 USB 连接，解锁手机后重试。"
        else:
            message = str(error)
        emit("result", clean=not pending, ok=False, message=f"{message}（{type(error).__name__}）")
        return 1

    if action == "restore":
        message = "恢复请求已发送"
    elif keep_position:
        message = "回放完成，位置已回到起点"
    elif stopped:
        message = "已重置位置" if commands is not None and commands.reset_requested else "已尝试返回起点"
    else:
        message = "回放完成"
    emit("result", clean=not pending, ok=True, message=message)
    return 0


def read_track(path):
    points = json.loads(Path(path).read_text())
    if not isinstance(points, list) or not points:
        raise ValueError("轨迹没有坐标点")
    return points


async def main(args):
    if args.action == "list":
        try:
            await list_usb_devices()
        except Exception as error:
            emit("result", clean=True, ok=False, message=f"设备列表读取失败：{error}")
            return 1
        return 0

    if args.action == "restore":
        return await operate(args.action, args.serial, None, asyncio.Event())

    try:
        points = read_track(args.route)
    except (OSError, ValueError) as error:
        emit("result", clean=True, ok=False, message=f"无法读取回放轨迹：{error}")
        return 1

    stop = asyncio.Event()
    controls = ReplayCommands(lambda: read_track(args.route))
    loop = asyncio.get_running_loop()
    buffer = bytearray()

    def stop_playback():
        stop.set()
        controls.changed.set()

    def handle_input():
        data = os.read(sys.stdin.fileno(), 4096)
        if not data:
            loop.remove_reader(sys.stdin.fileno())
            stop_playback()
            return
        buffer.extend(data)
        while b"\n" in buffer:
            line, remainder = buffer.split(b"\n", 1)
            buffer[:] = remainder
            if line == b"reset":
                controls.reset_requested = True
                stop_playback()
            elif line == b"pause":
                controls.set_paused(True)
            elif line == b"resume":
                controls.set_paused(False)
            elif line in (b"apply", b"apply-restart", b"apply-hold"):
                controls.apply_update(line == b"apply-restart", hold=line == b"apply-hold")
            elif line.startswith(b"seek "):
                with suppress(ValueError):
                    controls.seek(int(line[5:]))
        if len(buffer) > 4096:
            buffer.clear()

    loop.add_reader(sys.stdin.fileno(), handle_input)
    for sig in (signal.SIGINT, signal.SIGTERM):
        loop.add_signal_handler(sig, stop_playback)
    try:
        return await operate(args.action, args.serial, points, stop, commands=controls,
                             initial_index=getattr(args, "index", 0))
    finally:
        loop.remove_reader(sys.stdin.fileno())
        for sig in (signal.SIGINT, signal.SIGTERM):
            loop.remove_signal_handler(sig)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="action", required=True)
    commands.add_parser("list")
    for command in ("play", "anchor", "restore"):
        action_parser = commands.add_parser(command)
        action_parser.add_argument("serial")
        if command != "restore":
            action_parser.add_argument("route")
        if command == "play":
            action_parser.add_argument("--index", type=int, default=0)
    raise SystemExit(asyncio.run(main(parser.parse_args())))
