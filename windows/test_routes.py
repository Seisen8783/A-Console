from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from zipfile import ZipFile

from defusedxml.common import EntitiesForbidden

from routes import fit_loop, normalize, read_route, timeline


SAMPLE = Path(__file__).resolve().parents[1] / "macos/Tests/Fixtures/sample.gpx"
KML = "<kml><LineString><coordinates>1,1 1.001,1</coordinates></LineString></kml>"


class Tracks(unittest.TestCase):
    def test_sample_gpx_preserves_recorded_time(self):
        segments, gpx = read_route(SAMPLE)
        points = timeline(segments, gpx, 449)
        self.assertEqual(points[0]["offset"], 0)
        self.assertEqual(points[-1]["offset"], 20)

    def test_kmz_target_is_interpolated(self):
        with TemporaryDirectory() as folder:
            path = Path(folder) / "course.kmz"
            with ZipFile(path, "w") as archive:
                archive.writestr("doc.kml", KML)
            segments, gpx = read_route(path)
            points = timeline(segments, gpx, 449, target=225)
        self.assertEqual(points[-1]["meters"], 225)
        self.assertGreater(points[-1]["offset"], 90)

    def test_coordinate_normalization_precedes_offset(self):
        source = [[(37.4, 118.0, None), (37.41, 118.01, None)]]
        normalized = normalize(source, "GCJ-02", 0, 0)
        shifted = normalize(source, "GCJ-02", 20, 30)
        self.assertNotEqual(normalized, source)
        self.assertAlmostEqual(shifted[0][0][0] - normalized[0][0][0], 30 / 111_195)
        self.assertGreater(shifted[0][0][1], normalized[0][0][1])

    def test_centerline_is_closed(self):
        from math import cos, pi, sin
        route = [[(37 + .0005 * (cos(i * pi / 20) - 1),
                   118 + .0005 * sin(i * pi / 20), None) for i in range(121)]]
        points, = fit_loop(route)
        self.assertEqual(points[0], points[-1])
        self.assertGreater(len(points), 32)

    def test_unsafe_xml_is_rejected(self):
        with TemporaryDirectory() as folder:
            path = Path(folder) / "bad.kml"
            path.write_text('<!DOCTYPE a [<!ENTITY x SYSTEM "file:///example">]><kml>&x;</kml>')
            with self.assertRaises(EntitiesForbidden):
                read_route(path)

    def test_gpx_without_times_cannot_play(self):
        with self.assertRaisesRegex(ValueError, "时间戳"):
            timeline([[(1, 1, None), (1, 2, None)]], True, 449)
