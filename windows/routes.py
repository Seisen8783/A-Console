"""Import and schedule user-supplied location test tracks."""

from datetime import datetime
import math
from pathlib import Path
from zipfile import ZipFile

from defusedxml.ElementTree import fromstring


def local_name(node):
    return node.tag.rsplit("}", 1)[-1]


def children(node, name):
    return [child for child in node if local_name(child) == name]


def read_route(path):
    path = Path(path)
    limit = 20 * 1024 * 1024
    archived = path.suffix.lower() == ".kmz"
    if path.stat().st_size > (limit // 2 if archived else limit):
        raise ValueError("轨迹文件超过大小上限")
    if archived:
        with ZipFile(path) as archive:
            entries = [entry for entry in archive.infolist()
                       if entry.filename.lower().endswith(".kml")]
            roots = [entry for entry in entries if entry.filename.lower() == "doc.kml"]
            selected = roots or entries
            if len(selected) != 1:
                raise ValueError("KMZ 应包含一个 doc.kml 或唯一的 KML 文件")
            with archive.open(selected[0]) as source:
                data = source.read(limit + 1)
    else:
        data = path.read_bytes()
    if len(data) > limit:
        raise ValueError("轨迹内容超过大小上限")

    xml = fromstring(data)
    segments = []
    gpx = path.suffix.lower() == ".gpx"
    for node in xml.iter():
        name = local_name(node)
        if gpx and name == "trkseg":
            segment = [(float(p.attrib["lat"]), float(p.attrib["lon"]),
                        next((t.text for t in children(p, "time")), None))
                       for p in children(node, "trkpt")]
        elif not gpx and name == "LineString":
            segment = []
            for coord in children(node, "coordinates"):
                for value in (coord.text or "").split():
                    lon, lat, *_ = value.split(",")
                    segment.append((float(lat), float(lon), None))
        elif not gpx and name == "Track":
            coords = children(node, "coord")
            times = children(node, "when")
            segment = []
            for index, coord in enumerate(coords):
                lon, lat, *_ = (coord.text or "").split()
                segment.append((float(lat), float(lon),
                                times[index].text if index < len(times) else None))
        else:
            continue
        if segment:
            segments.append(segment)

    points = [point for segment in segments for point in segment]
    if len(points) < 2:
        raise ValueError("轨迹至少需要两个点")
    if any(not (-90 <= lat <= 90 and -180 <= lon <= 180) for lat, lon, _ in points):
        raise ValueError("轨迹包含无效坐标")
    return segments, gpx


def distance(a, b):
    lat1, lat2 = map(math.radians, (a[0], b[0]))
    dlon = math.radians(b[1] - a[1])
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return 12_742_000 * math.asin(math.sqrt(min(1, h)))


def timeline(segments, gpx, pace, laps=1, target=0):
    raw = [point for segment in segments for point in segment]
    timed = all(point[2] for point in raw)
    if gpx and not timed:
        raise ValueError("GPX 缺少有效时间戳")
    stamps = [datetime.fromisoformat(point[2].replace("Z", "+00:00")).timestamp()
              for point in raw] if timed else []
    if any(a > b for a, b in zip(stamps, stamps[1:])):
        raise ValueError("时间戳倒序")
    elapsed = meters = 0.0
    result = []
    previous = None
    index = 0
    for segment in segments:
        previous = None
        for point in segment:
            length = distance(previous, point) if previous else 0
            duration = stamps[index] - stamps[0] - elapsed if timed else length * pace / 1000
            steps = 1 if timed else max(1, math.ceil(duration))
            if len(result) + steps > 200_000:
                raise ValueError("轨迹采样点过多")
            for step in range(1, steps + 1):
                part = step / steps
                lat, lon = point[:2]
                if previous and step < steps:
                    lat = previous[0] + (lat - previous[0]) * part
                    lon = (previous[1] + ((lon - previous[1] + 180) % 360 - 180) * part + 180) % 360 - 180
                result.append(dict(latitude=lat, longitude=lon, offset=elapsed + duration * part,
                                   meters=meters + length * part))
            meters += length
            elapsed += duration
            previous = point
            index += 1
    if not meters or not elapsed:
        raise ValueError("轨迹需要有效距离和时长")
    output = [result[0]]
    repeats = laps if not target else math.ceil(target / meters)
    for lap in range(repeats):
        for point in result[1:]:
            m = point["meters"] + lap * meters
            t = point["offset"] + lap * elapsed
            if target and m > target:
                previous = output[-1]
                fraction = (target - previous["meters"]) / (m - previous["meters"])
                lat = previous["latitude"] + (point["latitude"] - previous["latitude"]) * fraction
                delta = (point["longitude"] - previous["longitude"] + 180) % 360 - 180
                lon = (previous["longitude"] + delta * fraction + 180) % 360 - 180
                output.append(dict(latitude=lat, longitude=lon,
                                   offset=previous["offset"] + (t - previous["offset"]) * fraction,
                                   meters=target))
                return output
            output.append(dict(point, offset=t, meters=m))
            if target and m == target:
                return output
            if len(output) >= 200_000:
                raise ValueError("回放采样点过多")
    return output


def normalize(segments, system, east, north):
    from tools.coordinate_probe import gcj02_to_wgs84

    result = []
    for segment in segments:
        adjusted = []
        for lat, lon, timestamp in segment:
            if system == "BD-09":
                x, y = lon - 0.0065, lat - 0.006
                factor = math.pi * 3000 / 180
                radius = math.hypot(x, y) - 0.00002 * math.sin(y * factor)
                angle = math.atan2(y, x) - 0.000003 * math.cos(x * factor)
                lon, lat = radius * math.cos(angle), radius * math.sin(angle)
            if system != "WGS-84":
                lon, lat = gcj02_to_wgs84(lon, lat)
            longitude_scale = 111_195 * math.cos(math.radians(lat))
            if longitude_scale > 1 and (abs(east) >= 0.5 or abs(north) >= 0.5):
                lat += north / 111_195
                lon = (lon + east / longitude_scale + 180) % 360 - 180
            if not -90 <= lat <= 90:
                raise ValueError("标定偏移超出纬度范围")
            adjusted.append((lat, lon, timestamp))
        result.append(adjusted)
    return result


def fit_loop(segments):
    from bisect import bisect_right

    if len(segments) != 1 or len(segments[0]) < 10 or all(p[2] for p in segments[0]):
        raise ValueError("单圈中线需要一段未计时的多圈轨迹")
    origin_lat, origin_lon, _ = segments[0][0]
    north = 111_195
    east = north * math.cos(math.radians(origin_lat))
    if east <= 1:
        raise ValueError("无法在极区拟合中线")
    xy = [((lon - origin_lon) * east, (lat - origin_lat) * north)
          for lat, lon, _ in segments[0]]
    distances = [0.0]
    for a, b in zip(xy, xy[1:]):
        distances.append(distances[-1] + math.dist(a, b))
    heading = next((xy[i] for i, d in enumerate(distances) if d >= 10), (0, 0))
    norm = math.hypot(*heading)
    if norm <= 5:
        raise ValueError("无法识别单圈起点方向")
    direction = tuple(v / norm for v in heading)

    def interpolate(a, b, fraction):
        return tuple(start + (end - start) * fraction for start, end in zip(a, b))

    anchors = [0.0]
    for i, (a, b) in enumerate(zip(xy, xy[1:])):
        before = sum(v * d for v, d in zip(a, direction))
        after = sum(v * d for v, d in zip(b, direction))
        if before < 0 <= after:
            fraction = -before / (after - before)
            crossing = distances[i] + (distances[i + 1] - distances[i]) * fraction
            if math.hypot(*interpolate(a, b, fraction)) <= 12 and crossing - anchors[-1] >= 30:
                anchors.append(crossing)
    laps = [(a, b - a) for a, b in zip(anchors, anchors[1:])]
    if not 2 <= len(laps) <= 100:
        raise ValueError("未识别到至少两个完整圈")
    length = sum(span for _, span in laps) / len(laps)
    if any(abs(span - length) >= .12 * length for _, span in laps):
        raise ValueError("各圈长度差异过大")

    def sample(meters):
        i = min(bisect_right(distances, meters) - 1, len(xy) - 2)
        span = distances[i + 1] - distances[i]
        return interpolate(xy[i], xy[i + 1], (meters - distances[i]) / span) if span else xy[i]

    count = max(32, min(2048, math.ceil(length / 2)))
    points = []
    for i in range(count):
        samples = [sample(start + i / count * span) for start, span in laps]
        mean = tuple(sum(axis) / len(laps) for axis in zip(*samples))
        if any(math.dist(value, mean) > 9 for value in samples):
            raise ValueError("各圈形状差异过大")
        points.append((origin_lat + mean[1] / north, origin_lon + mean[0] / east, None))
    return [points + [points[0]]]
