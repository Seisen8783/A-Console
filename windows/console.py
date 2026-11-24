"""Windows desktop entry point for A Console."""

import bisect
import json
from datetime import datetime, timedelta
from pathlib import Path
import sys

from PySide6.QtCore import QPointF, QLockFile, QProcess, QSaveFile, QSettings, QIODevice, QStandardPaths, Qt, QTimer, QUrl
from PySide6.QtGui import QColor, QDesktopServices, QIcon, QPainter, QPainterPath, QPen
from PySide6.QtWidgets import (
    QApplication, QCheckBox, QComboBox, QDoubleSpinBox, QFileDialog, QHBoxLayout, QLabel,
    QPushButton, QSlider, QSpinBox, QVBoxLayout, QWidget,
)

if not getattr(sys, "frozen", False):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from onboarding import Onboarding
from routes import fit_loop, normalize, read_route, timeline


REPOSITORY_URL = "https://github.com/Seisen8783/A-Console"


class Preview(QWidget):
    def __init__(self):
        super().__init__()
        self.segments = []
        self.current = None
        self.setMinimumSize(500, 270)

    def paintEvent(self, event):
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        painter.fillRect(self.rect(), QColor("#edf2f8"))
        points = [point for segment in self.segments for point in segment]
        if not points:
            painter.drawText(self.rect(), Qt.AlignmentFlag.AlignCenter, "导入轨迹以预览")
            return
        origin = points[0][1]

        def x(lon):
            return (lon - origin + 180) % 360 - 180

        xs, ys = [x(p[1]) for p in points], [p[0] for p in points]
        scale = min((self.width() - 40) / max(max(xs) - min(xs), 1e-6),
                    (self.height() - 40) / max(max(ys) - min(ys), 1e-6))

        center_x = (min(xs) + max(xs)) / 2
        center_y = (min(ys) + max(ys)) / 2

        def project(lat, lon):
            return QPointF(self.width() / 2 + (x(lon) - center_x) * scale,
                           self.height() / 2 - (lat - center_y) * scale)

        painter.setPen(QPen(QColor("#2f62cc"), 3))
        for segment in self.segments:
            path = QPainterPath(project(*segment[0][:2]))
            for point in segment[1:]:
                path.lineTo(project(*point[:2]))
            painter.drawPath(path)
        for point, color in ((points[0][:2], "#18885d"), (points[-1][:2], "#dd6730"),
                             (self.current, "#164298")):
            if point:
                painter.setBrush(QColor(color))
                painter.drawEllipse(project(*point), 5, 5)


class Console(QWidget):
    def __init__(self, folder):
        super().__init__()
        self.setWindowTitle("A Console")
        self.setAcceptDrops(True)
        self.segments = []
        self.points = []
        self.gpx = False
        self.times = []
        self.buffer = bytearray()
        self.operation = ""
        self.paused = False
        self.closing = False
        self.device = None
        self.detached = False
        self.last_index = 0
        self.eta = QLabel()
        self.marker = folder / "pending-device"
        self.route_file = folder / "timeline.json"
        self.bridge = QProcess(self)
        self.bridge.readyReadStandardOutput.connect(self.receive)
        self.bridge.readyReadStandardError.connect(lambda: self.bridge.readAllStandardError())
        self.bridge.finished.connect(self.finished)
        self.bridge.errorOccurred.connect(self.process_error)
        self.devices = QComboBox()
        self.refresh = QPushButton("刷新 USB")
        self.refresh.clicked.connect(lambda: self.launch("list"))
        self.open = QPushButton("导入 GPX / KML / KMZ")
        self.open.clicked.connect(self.choose)
        guide = QPushButton("使用引导")
        guide.clicked.connect(lambda: Onboarding(self).exec())
        repository = QPushButton("GitHub Repository")
        repository.clicked.connect(lambda: QDesktopServices.openUrl(QUrl(REPOSITORY_URL)))
        self.source = QLabel("未选择轨迹")
        self.pace = QSpinBox()
        self.pace.setRange(60, 3600)
        self.pace.setValue(449)
        self.pace.setSuffix(" 秒/公里")
        self.laps = QSpinBox()
        self.laps.setRange(1, 99)
        self.km = QDoubleSpinBox()
        self.km.setRange(0, 1000)
        self.km.setDecimals(3)
        self.km.setSuffix(" km")
        self.km.setSpecialValueText("按圈数")
        self.datum = QComboBox()
        self.datum.addItems(["WGS-84", "GCJ-02", "BD-09"])
        self.east = QSpinBox()
        self.north = QSpinBox()
        for field in (self.east, self.north):
            field.setRange(-100000, 100000)
            field.setSuffix(" m")
        self.centerline = QCheckBox("使用单圈中线")
        self.preview = Preview()
        self.seek = QSlider(Qt.Orientation.Horizontal)
        self.seek.sliderReleased.connect(self.jump)
        self.anchor = QPushButton("定位到起点")
        self.anchor.clicked.connect(lambda: self.start("anchor"))
        self.play = QPushButton("开始回放")
        self.play.clicked.connect(lambda: self.start("play"))
        self.pause = QPushButton("暂停")
        self.pause.clicked.connect(lambda: self.command("resume" if self.paused else "pause"))
        self.restore = QPushButton("恢复真实定位")
        self.restore.clicked.connect(self.reset)
        self.progress = QLabel()
        self.status = QLabel("等待 USB 设备")
        self.status.setWordWrap(True)
        layout = QVBoxLayout(self)
        for row in ((self.devices, self.refresh, self.open, self.source, guide),
                    (QLabel("配速"), self.pace, QLabel("圈数"), self.laps, QLabel("目标距离"), self.km),
                    (QLabel("坐标基准"), self.datum, QLabel("向东"), self.east,
                     QLabel("向北"), self.north, self.centerline),
                    (self.anchor, self.play, self.pause, self.restore, self.progress)):
            line = QHBoxLayout()
            for item in row:
                line.addWidget(item)
            layout.addLayout(line)
        for widget in (self.preview, self.seek, self.eta, self.status):
            layout.addWidget(widget)
        self.devices.currentIndexChanged.connect(self.sync_controls)
        self.km.valueChanged.connect(self.sync_controls)
        self.datum.currentTextChanged.connect(self.update_preview)
        self.east.valueChanged.connect(self.update_preview)
        self.north.valueChanged.connect(self.update_preview)
        self.centerline.toggled.connect(self.update_preview)
        self.sync_controls()
        self.launch("list")

    def pending(self):
        return self.marker.read_text(encoding="utf-8") if self.marker.exists() else ""

    def sync_controls(self):
        busy = self.bridge.state() != QProcess.ProcessState.NotRunning
        playing = busy and self.operation in ("play", "anchor")
        serial = self.devices.currentData()
        allowed = bool(serial and self.segments and not busy and self.pending() in ("", serial))
        self.anchor.setEnabled(allowed and not self.detached)
        self.play.setEnabled(allowed)
        self.play.setText("继续中断的回放" if self.detached else "开始回放")
        remaining = max(0, self.times[-1] - self.times[self.last_index]) if self.times else 0
        if playing and not self.paused and remaining:
            end = datetime.now() + timedelta(seconds=remaining)
            self.eta.setText(f"预计完成 {end:%H:%M:%S}")
        else:
            self.eta.clear()
        self.pause.setEnabled(playing)
        self.pause.setText("继续" if self.paused else "暂停")
        self.seek.setEnabled(playing)
        self.restore.setEnabled(playing or (not busy and bool(self.pending() or serial)))
        for widget in (self.refresh, self.devices, self.open, self.pace, self.km,
                       self.datum, self.east, self.north, self.centerline):
            widget.setEnabled(not busy and (not self.detached or widget in (self.refresh, self.devices)))
        self.laps.setEnabled(self.km.value() == 0 and not busy and not self.detached)

    def choose(self):
        path, _ = QFileDialog.getOpenFileName(self, "导入轨迹", "", "轨迹 (*.gpx *.kml *.kmz)")
        if path:
            self.load(path)

    def dragEnterEvent(self, event):
        if self.open.isEnabled() and event.mimeData().hasUrls():
            event.acceptProposedAction()

    def dropEvent(self, event):
        urls = event.mimeData().urls()
        if self.open.isEnabled() and len(urls) == 1 and urls[0].isLocalFile():
            self.load(urls[0].toLocalFile())

    def load(self, path):
        try:
            if Path(path).suffix.lower() not in (".gpx", ".kml", ".kmz"):
                raise ValueError("请选择 GPX、KML 或 KMZ")
            self.segments, self.gpx = read_route(path)
        except Exception as error:
            self.status.setText(f"导入失败：{error}")
            return
        self.source.setText(Path(path).name)
        self.update_preview()
        self.sync_controls()

    def adjusted_segments(self):
        adjusted = normalize(self.segments, self.datum.currentText(), self.east.value(), self.north.value())
        return fit_loop(adjusted) if self.centerline.isChecked() else adjusted

    def update_preview(self):
        if not self.segments:
            return
        try:
            self.preview.segments = self.adjusted_segments()
        except ValueError as error:
            self.status.setText(str(error))
            return
        self.preview.current = None
        self.preview.update()

    def start(self, action):
        try:
            if not self.detached:
                self.points = timeline(self.adjusted_segments(), self.gpx, self.pace.value(),
                                       self.laps.value(), self.km.value() * 1000)
                self.last_index = 0
            self.times = [point["offset"] for point in self.points]
            self.seek.setMaximum(round(self.times[-1]))
            self.write(self.route_file, json.dumps(self.points))
            self.device = self.devices.currentData()
            self.write(self.marker, self.device)
        except Exception as error:
            self.status.setText(str(error))
            return
        args = [self.device, str(self.route_file)]
        if action == "play":
            args.extend(["--index", str(self.last_index)])
        self.detached = False
        self.launch(action, *args)

    @staticmethod
    def write(path, text):
        target = QSaveFile(str(path))
        if not target.open(QIODevice.OpenModeFlag.WriteOnly):
            raise OSError(target.errorString())
        data = text.encode("utf-8")
        if target.write(data) != len(data) or not target.commit():
            raise OSError(target.errorString())

    def launch(self, action, *args):
        if self.bridge.state() != QProcess.ProcessState.NotRunning:
            return
        self.operation = action
        self.buffer.clear()
        self.paused = action == "anchor"
        self.status.setText("正在读取 USB 设备…" if action == "list" else "正在连接 iPhone…")
        if getattr(sys, "frozen", False):
            executable = Path(sys.executable).parent / "bridge" / "UnRealDeviceBridge.exe"
            prefix = []
        else:
            executable = sys.executable
            prefix = ["-X", "utf8", "-u", str(Path(__file__).resolve().parents[1] /
                      "macos/Sources/UnRealConsole/Resources/device_bridge.py")]
        self.bridge.start(str(executable), [*prefix, action, *args])
        self.sync_controls()

    def command(self, value):
        self.bridge.write((value + "\n").encode("ascii"))

    def jump(self):
        index = min(bisect.bisect_left(self.times, self.seek.value()), len(self.times) - 1)
        if index and self.seek.value() - self.times[index - 1] <= self.times[index] - self.seek.value():
            index -= 1
        self.command(f"seek {index}")

    def reset(self):
        if self.bridge.state() != QProcess.ProcessState.NotRunning:
            self.command("reset")
        else:
            self.launch("restore", self.pending() or self.devices.currentData())

    def receive(self):
        self.buffer.extend(bytes(self.bridge.readAllStandardOutput()))
        while b"\n" in self.buffer:
            line, _, remainder = self.buffer.partition(b"\n")
            self.buffer = bytearray(remainder)
            try:
                event = json.loads(line)
            except (ValueError, UnicodeError):
                continue
            kind = event.get("kind")
            if event.get("message"):
                self.status.setText(event["message"])
            if kind == "devices":
                selected = self.pending() or self.devices.currentData()
                self.devices.clear()
                for device in event["devices"]:
                    self.devices.addItem(f'{device["name"]} · {device["version"]} · {device["detail"]}',
                                         device["id"])
                index = self.devices.findData(selected)
                if index >= 0:
                    self.devices.setCurrentIndex(index)
                self.status.setText("请选择 USB 设备" if self.devices.count() else
                                    "未找到设备。检查数据线、iPhone 信任状态及 Apple Mobile Device Service。")
                if self.pending():
                    self.status.setText("有待恢复的定位会话，请连接原 iPhone。")
            elif kind == "point":
                self.last_index = event["index"]
                point = self.points[self.last_index]
                self.preview.current = (point["latitude"], point["longitude"])
                self.preview.update()
                if not self.seek.isSliderDown():
                    self.seek.setValue(round(point["offset"]))
                self.progress.setText(f'{point["meters"]:.1f} / {self.points[-1]["meters"]:.1f} m')
            elif kind == "mode":
                self.paused = event["paused"]
            elif kind == "cleared":
                self.marker.unlink(missing_ok=True)
                self.detached = False
                self.device = None
                self.preview.current = None
                self.preview.update()
            self.sync_controls()

    def finished(self, code, _status):
        self.receive()
        if self.operation == "restore" and not self.pending() and code == 0:
            self.status.setText("恢复请求已发送，请在 iPhone 上确认。")
        if self.operation in ("play", "anchor"):
            if not self.pending() and code == 0:
                self.status.setText("恢复请求已发送，请在 iPhone 上确认。")
            elif code != 0 and self.pending():
                self.detached = True
        self.sync_controls()
        if self.closing:
            if self.operation == "list" and self.pending():
                self.reset()
            else:
                self.close()

    def process_error(self, error):
        if error == QProcess.ProcessError.FailedToStart:
            self.status.setText(f"设备桥接无法启动：{self.bridge.errorString()}")
            self.sync_controls()
            if self.closing:
                self.close()

    def closeEvent(self, event):
        busy = self.bridge.state() != QProcess.ProcessState.NotRunning
        if self.closing and not busy:
            event.accept()
        elif busy or self.pending():
            event.ignore()
            self.closing = True
            if self.operation in ("play", "anchor") and busy:
                self.command("quit")
            elif not busy:
                self.reset()
            QTimer.singleShot(8000, self.close_timeout)
        else:
            event.accept()

    def close_timeout(self):
        if self.bridge.state() != QProcess.ProcessState.NotRunning:
            self.bridge.kill()
        else:
            self.close()


if __name__ == "__main__":
    app = QApplication(sys.argv)
    app.setApplicationName("UnReal Console")
    folder = Path(QStandardPaths.writableLocation(QStandardPaths.StandardLocation.AppLocalDataLocation))
    app.setApplicationName("A Console")
    app.setWindowIcon(QIcon(str(Path(__file__).with_name("a-console.ico"))))
    folder.mkdir(parents=True, exist_ok=True)
    lock = QLockFile(str(folder / "console.lock"))
    lock.setStaleLockTime(0)
    if not lock.tryLock():
        from PySide6.QtWidgets import QMessageBox
        QMessageBox.information(None, "A Console", "控制台已打开。")
        sys.exit(1)
    settings = QSettings("UnReal", "Console")
    if not (folder / "pending-device").exists() and not settings.value("onboardingSeen", False, type=bool):
        if not Onboarding(first=True).exec():
            sys.exit(0)
        settings.setValue("onboardingSeen", True)
        settings.sync()
    console = Console(folder)
    console.show()
    sys.exit(app.exec())
