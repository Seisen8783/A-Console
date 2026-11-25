import os
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from PySide6.QtWidgets import QApplication

from console import Console
from routes import timeline


class DefaultTrackTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
        cls.app = QApplication.instance() or QApplication([])

    def test_launch_loads_default_and_can_return_to_it_after_import(self):
        with TemporaryDirectory() as folder, patch.object(Console, "launch", lambda self, *args: None):
            console = Console(Path(folder))
            self.assertEqual(console.source.text(), "已载入默认轨迹")
            self.assertTrue(console.preview.segments)
            default_segments = console.segments
            points = timeline(console.adjusted_segments(), console.gpx, 449)
            self.assertGreater(points[-1]["meters"], 0)

            sample = Path(__file__).resolve().parents[1] / "macos/Tests/Fixtures/sample.gpx"
            console.load(sample)
            console.datum.setCurrentText("GCJ-02")
            self.assertEqual(console.source.text(), sample.name)
            self.assertTrue(console.gpx)

            console.default_track.click()
            self.assertEqual(console.segments, default_segments)
            self.assertEqual(console.source.text(), "已载入默认轨迹")
            self.assertEqual(console.datum.currentText(), "WGS-84")
            self.assertFalse(console.gpx)
            console.close()
