"""Offline onboarding for the Windows console."""

import ctypes
import sys

from PySide6.QtCore import Property, QPointF, QPropertyAnimation, QRectF, Qt
from PySide6.QtGui import QColor, QPainter, QPainterPath, QPen, QShortcut, QKeySequence
from PySide6.QtWidgets import (
    QDialog, QHBoxLayout, QLabel, QPushButton, QStyle, QVBoxLayout, QWidget,
)


STEPS = (
    ("连接 iPhone", "用数据线连接 iPhone。解锁手机，轻点「信任」。",
     ("电脑", "USB", "信任此电脑"),
     "Windows 需要 Apple Mobile Device Service。可通过 Apple 官网的 Windows 版 iTunes 安装。"),
    ("开启开发者模式", "开启开发者模式，按手机提示重启并确认。",
     ("设置", "隐私与安全性", "开发者模式"),
     "找不到开关时，可参考 Windows 使用说明中的 reveal-developer-mode。"),
    ("选择设备和轨迹", "在 USB 列表选中 iPhone，再导入或拖入 GPX、KML、KMZ。",
     ("选择 iPhone", "导入文件", "预览轨迹"),
     "找不到设备时，检查数据线、手机信任状态和 Apple Mobile Device Service，再点「刷新 USB」。"),
    ("核对起点，开始回放", "点「定位到起点」，在 iPhone 上核对后点「开始回放」。回放时保持 USB 连接。",
     ("定位到起点", "开始回放", "沿轨迹移动"), ""),
    ("恢复真实定位", "回放结束会返回轨迹起点。用完后点「恢复真实定位」，再到 iPhone 上确认。",
     ("回到起点", "恢复真实定位", "在手机上确认"),
     "USB 已断开时，请重新连接原来的 iPhone，再恢复定位。"),
)


def reduce_motion():
    if sys.platform != "win32":
        return False
    enabled = ctypes.c_int()
    ok = ctypes.windll.user32.SystemParametersInfoW(0x1042, 0, ctypes.byref(enabled), 0)
    return bool(ok and not enabled.value)


class Illustration(QWidget):
    ICONS = (
        ("SP_ComputerIcon", "SP_DriveHDIcon", "SP_DialogYesButton"),
        ("SP_FileDialogDetailedView", "SP_MessageBoxWarning", "SP_DialogApplyButton"),
        ("SP_ComputerIcon", "SP_FileIcon", "SP_DirOpenIcon"),
        ("SP_ArrowDown", "SP_MediaPlay", "SP_ArrowRight"),
        ("SP_ArrowBack", "SP_BrowserReload", "SP_DialogApplyButton"),
    )

    def __init__(self):
        super().__init__()
        self.setMinimumHeight(155)
        self.labels = STEPS[0][2]
        self.step = 0
        self._progress = 1.0

    @Property(float)
    def progress(self):
        return self._progress

    @progress.setter
    def progress(self, value):
        self._progress = value
        self.update()

    def paintEvent(self, event):
        painter = QPainter(self)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        painter.setPen(Qt.PenStyle.NoPen)
        painter.setBrush(QColor("#eaf1ff"))
        painter.drawRoundedRect(QRectF(self.rect()), 16, 16)
        gap = 16
        width = (self.width() - gap * 4) / 3
        for index, label in enumerate(self.labels):
            x = gap + index * (width + gap)
            active = self.progress >= index / 3
            card = QRectF(x, 20, width, 70)
            painter.setPen(Qt.PenStyle.NoPen)
            painter.setBrush(QColor("#2456aa" if active else "#d5dfee"))
            painter.drawRoundedRect(card, 10, 10)
            painter.setPen(QColor("white" if active else "#304460"))
            icon = self.style().standardIcon(getattr(QStyle.StandardPixmap, self.ICONS[self.step][index]))
            icon.paint(painter, int(x + width / 2 - 10), 29, 20, 20)
            painter.drawText(card.adjusted(0, 30, 0, 0), Qt.AlignmentFlag.AlignCenter, label)
        left, right = gap * 2, self.width() - gap * 2
        path = QPainterPath(QPointF(left, 124))
        if self.step >= 2:
            path.cubicTo(left + (right - left) * 0.35, 92,
                         left + (right - left) * 0.65, 155, right, 116)
        else:
            path.lineTo(right, 124)
        painter.setPen(QPen(QColor("#b5c8e8"), 3))
        painter.setBrush(Qt.BrushStyle.NoBrush)
        painter.drawPath(path)
        painter.setPen(QPen(QColor("#2456aa"), 7, Qt.PenStyle.SolidLine, Qt.PenCapStyle.RoundCap))
        position = self.progress
        if self.step == 4:
            position = abs(1 - 2 * self.progress) if self.progress < 0.5 else 0
        painter.drawPoint(path.pointAtPercent(position))


class Onboarding(QDialog):
    def __init__(self, parent=None, first=False):
        super().__init__(parent)
        self.setWindowTitle("A Console · 使用引导")
        self.resize(620, 405)
        self.first = first
        self.index = 0
        self.counter = QLabel()
        self.title = QLabel()
        font = self.title.font()
        font.setPointSize(21)
        font.setBold(True)
        self.title.setFont(font)
        self.detail = QLabel()
        self.tip = QLabel()
        for label in (self.detail, self.tip):
            label.setWordWrap(True)
            label.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByKeyboard |
                                          Qt.TextInteractionFlag.TextSelectableByMouse)
        self.art = Illustration()
        self.animation = QPropertyAnimation(self.art, b"progress", self)
        self.animation.setDuration(1850)
        self.help = QPushButton("遇到问题？")
        self.help.setCheckable(True)
        self.help.toggled.connect(self.tip.setVisible)
        self.back = QPushButton("上一步")
        self.back.clicked.connect(lambda: self.show_step(self.index - 1))
        self.next = QPushButton()
        self.next.setDefault(True)
        self.next.clicked.connect(self.advance)
        skip = QPushButton("跳过引导" if first else "关闭引导")
        skip.clicked.connect(self.accept)
        footer = QHBoxLayout()
        footer.addWidget(skip)
        footer.addStretch()
        footer.addWidget(self.back)
        footer.addWidget(self.next)
        layout = QVBoxLayout(self)
        layout.setContentsMargins(24, 22, 24, 22)
        for item in (self.counter, self.title, self.art, self.detail, self.help, self.tip):
            layout.addWidget(item)
        layout.addStretch()
        layout.addLayout(footer)
        QShortcut(QKeySequence(Qt.Key.Key_Left), self, activated=self.previous)
        QShortcut(QKeySequence(Qt.Key.Key_Right), self, activated=self.advance)
        self.show_step(0)

    def previous(self):
        if self.index:
            self.show_step(self.index - 1)

    def advance(self):
        if self.index + 1 == len(STEPS):
            self.accept()
        else:
            self.show_step(self.index + 1)

    def show_step(self, index):
        self.index = index
        title, detail, labels, tip = STEPS[index]
        self.counter.setText(f"{index + 1} / {len(STEPS)}")
        self.title.setText(title)
        self.detail.setText(detail)
        self.tip.setText(tip)
        self.help.setChecked(False)
        self.help.setVisible(bool(tip))
        self.tip.hide()
        self.back.setEnabled(index != 0)
        end = "进入控制台" if self.first else "完成"
        self.next.setText(end if index + 1 == len(STEPS) else "下一步")
        self.art.labels = labels
        self.art.step = index
        self.animation.stop()
        if reduce_motion():
            self.art.progress = 1.0
        else:
            self.art.progress = 0.0
            self.animation.setStartValue(0.0)
            self.animation.setEndValue(1.0)
            self.animation.start()
