#!/usr/bin/env python3
"""坐标基准与围栏位置诊断工具。

用途：
1. 判断一条轨迹文件用的是哪套坐标系（WGS-84 / GCJ-02 / BD-09）。
2. 计算把轨迹平移到目标位置所需的标定偏移（东/北各多少米）。

判据原理：
- WGS-84 → GCJ-02 在国内会产生数百米的偏移（滨州一带约 475 m）。
- 若某点按 WGS-84 解读时落在学校建筑/球场附近，而按 GCJ-02 解读就落到空白区，
  那么该点用的是 WGS-84。

用法：
    python3 tools/coordinate_probe.py <轨迹文件> [--target LAT,LON] [--target-name 名称]
    python3 tools/coordinate_probe.py <轨迹文件> --probe LAT,LON
"""

import argparse
import json
import math
import re
import sys
import zipfile
from pathlib import Path

PI = math.pi
A = 6378245.0
EE = 0.00669342162296594323


def out_of_china(lng, lat):
    return not (72.004 <= lng <= 137.8347 and 0.8293 <= lat <= 55.8271)


def _t_lat(x, y):
    r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * math.sqrt(abs(x))
    r += (20.0 * math.sin(6.0 * x * PI) + 20.0 * math.sin(2.0 * x * PI)) * 2.0 / 3.0
    r += (20.0 * math.sin(y * PI) + 40.0 * math.sin(y / 3.0 * PI)) * 2.0 / 3.0
    r += (160.0 * math.sin(y / 12.0 * PI) + 320 * math.sin(y * PI / 30.0)) * 2.0 / 3.0
    return r


def _t_lng(x, y):
    r = 300.0 + x + 2.0 * y + 0.1 * x * y + 0.1 * x * x + 0.1 * math.sqrt(abs(x))
    r += (20.0 * math.sin(6.0 * x * PI) + 20.0 * math.sin(2.0 * x * PI)) * 2.0 / 3.0
    r += (20.0 * math.sin(y * PI) + 40.0 * math.sin(y / 3.0 * PI)) * 2.0 / 3.0
    r += (150.0 * math.sin(y / 12.0 * PI) + 300.0 * math.sin(y * PI / 30.0)) * 2.0 / 3.0
    return r


def wgs84_to_gcj02(lng, lat):
    if out_of_china(lng, lat):
        return lng, lat
    d_lat = _t_lat(lng - 105.0, lat - 35.0)
    d_lng = _t_lng(lng - 105.0, lat - 35.0)
    rad = lat / 180.0 * PI
    magic = 1 - EE * math.sin(rad) ** 2
    sqrt_magic = math.sqrt(magic)
    d_lat = (d_lat * 180.0) / ((A * (1 - EE)) / (magic * sqrt_magic) * PI)
    d_lng = (d_lng * 180.0) / (A / sqrt_magic * math.cos(rad) * PI)
    return lng + d_lng, lat + d_lat


def gcj02_to_wgs84(lng, lat):
    if out_of_china(lng, lat):
        return lng, lat
    w_lng, w_lat = lng, lat
    for _ in range(12):
        g_lng, g_lat = wgs84_to_gcj02(w_lng, w_lat)
        err_lng, err_lat = lng - g_lng, lat - g_lat
        w_lng += err_lng
        w_lat += err_lat
        if abs(err_lng) < 1e-9 and abs(err_lat) < 1e-9:
            break
    return w_lng, w_lat


def meters_between(a_lat, a_lng, b_lat, b_lng):
    north = (a_lat - b_lat) * 111_320.0
    east = (a_lng - b_lng) * 111_320.0 * math.cos(math.radians(a_lat))
    return math.hypot(north, east)


def read_points(path):
    """从 GPX / KML / KMZ 提取轨迹点，返回 [(lat, lon), ...]。"""
    path = Path(path)
    suffix = path.suffix.lower()
    if suffix == ".kmz":
        with zipfile.ZipFile(path) as archive:
            names = [n for n in archive.namelist() if n.lower().endswith(".kml")]
            if not names:
                raise SystemExit("KMZ 内没有 KML 文件。")
            name = next((n for n in names if n.lower() == "doc.kml"), names[0])
            text = archive.read(name).decode("utf-8", "ignore")
    else:
        text = path.read_text(encoding="utf-8", errors="ignore")

    points = []
    for block in re.findall(r"<coordinates>(.*?)</coordinates>", text, re.S):
        for chunk in block.split():
            parts = chunk.split(",")
            if len(parts) >= 2:
                points.append((float(parts[1]), float(parts[0])))
    if not points:
        # GPX 用 trkpt 属性
        for match in re.finditer(r'<trkpt[^>]*lat="([-\d.]+)"[^>]*lon="([-\d.]+)"', text):
            points.append((float(match.group(1)), float(match.group(2))))
    if not points:
        raise SystemExit("未能从文件里提取到轨迹点。")
    return points


def centroid(points):
    return (sum(p[0] for p in points) / len(points), sum(p[1] for p in points) / len(points))


def main():
    parser = argparse.ArgumentParser(description="坐标基准与围栏位置诊断")
    parser.add_argument("track", help="轨迹文件（GPX / KML / KMZ）")
    parser.add_argument("--target", help="目标位置，格式 LAT,LON（服务端围栏里的点）")
    parser.add_argument("--target-name", default="目标", help="目标位置的名称")
    parser.add_argument("--probe", help="已知地物的位置，格式 LAT,LON（用来判断基准）")
    args = parser.parse_args()

    points = read_points(args.track)
    lat, lon = centroid(points)
    print(f"轨迹点数   : {len(points)}")
    print(f"轨迹中心   : {lat:.6f}, {lon:.6f}（文件原值）")
    print()

    shifted = wgs84_to_gcj02(lon, lat)
    print("=== 基准判读 ===")
    print(f"  若原值是 WGS-84 → 真值在 {lat:.6f}, {lon:.6f}")
    print(f"  若原值是 GCJ-02 → 真值在 {shifted[1]:.6f}, {shifted[0]:.6f}")
    print(f"  两者相距        : {meters_between(lat, lon, shifted[1], shifted[0]):.0f} 米")
    print()

    if args.probe:
        p_lat, p_lng = (float(v) for v in args.probe.split(","))
        d_wgs = meters_between(lat, lon, p_lat, p_lng)
        d_gcj = meters_between(shifted[1], shifted[0], p_lat, p_lng)
        verdict = "WGS-84" if d_wgs < d_gcj else "GCJ-02"
        print("=== 用已知地物判读 ===")
        print(f"  地物位置     : {p_lat:.6f}, {p_lng:.6f}")
        print(f"  按 WGS-84 读 : 距 {d_wgs:.0f} 米")
        print(f"  按 GCJ-02 读 : 距 {d_gcj:.0f} 米")
        print(f"  → 该文件应选 : {verdict}")
        print()

    if args.target:
        t_lat, t_lng = (float(v) for v in args.target.split(","))
        east = (t_lng - lon) * 111_320.0 * math.cos(math.radians(lat))
        north = (t_lat - lat) * 111_320.0
        print(f"=== 到「{args.target_name}」的标定偏移 ===")
        print(f"  目标位置 : {t_lat:.6f}, {t_lng:.6f}")
        print(f"  东向偏移 : {east:.1f} 米")
        print(f"  北向偏移 : {north:.1f} 米")
        print()
        print("  把这两个数填进 UnReal Console的「标定偏移」即可。")
        print("  注意：若轨迹文件本身是 GCJ-02，坐标基准选 GCJ-02，归一化后偏移量需重算。")
        if out_of_china(lon, lat):
            print("  ⚠️ 该坐标在境外，不做偏移。")
    else:
        print("提示：加上 --target LAT,LON 可算出把轨迹平移到目标位置所需的偏移。")


if __name__ == "__main__":
    main()
