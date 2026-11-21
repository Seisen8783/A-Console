import XCTest
@testable import UnRealConsole

/// 端到端验证：真实轨迹文件 → 坐标基准归一化 → 标定偏移 → 注入坐标。
///
/// 背景：壹路悦动体育小程序判定「非校园区域」的排查中，
/// 需要确认 UnReal 注入到设备的坐标与校园围栏的相对位置关系。
final class CampusTrackPipelineTests: XCTestCase {
    /// 山东航空学院校园足球场（OSM 权威数据，WGS-84）。
    private let pitch = (latitude: 37.387769, longitude: 117.982438)

    /// 真实跑动轨迹：Ground20261120105514.kmz 里的「校园跑」KML（644 点）。
    private func loadCampusTrack() throws -> TrackRoute {
        let url = URL(fileURLWithPath: "/Users/songxiuyu/Downloads/Ground20261120105514.kmz")
        try XCTSkipUnless(FileManager.default.fileExists(atPath: url.path),
                          "真实轨迹文件不在本机，跳过。")
        return try KMLRoute.load(url)
    }

    func testCampusTrackIsWGS84AndStaysNearPitch() {
        let route = try! loadCampusTrack()
        let center = route.points.reduce(into: (lat: 0.0, lon: 0.0)) { sum, point in
            sum.lat += point.latitude
            sum.lon += point.longitude
        }
        let latitude = center.lat / Double(route.points.count)
        let longitude = center.lon / Double(route.points.count)

        // 该文件已是 WGS-84：按 WGS-84 解读时距足球场两百余米（同一片场地内）。
        let meters = Self.metersBetween((latitude, longitude), pitch)
        XCTAssertTrue(meters < 400, "轨迹中心应落在足球场附近，实际 \(meters) m")

        // 若误标为 GCJ-02 并做归一化，整条轨迹会被整体平移一个基准偏移量
        // （滨州一带约 525 米）。这才是可靠的不变量——位移量，而不是与球场的距离增减。
        let mislabeled = CoordinateSystem.gcj02.normalized(route: route)
        let moved = mislabeled.points.reduce(into: (lat: 0.0, lon: 0.0)) { sum, point in
            sum.lat += point.latitude
            sum.lon += point.longitude
        }
        let movedLatitude = moved.lat / Double(mislabeled.points.count)
        let movedLongitude = moved.lon / Double(mislabeled.points.count)
        let displacement = Self.metersBetween((movedLatitude, movedLongitude),
                                              (latitude, longitude))
        XCTAssertTrue(displacement > 300,
                      "误标 GCJ-02 应把轨迹整体平移数百米，实际位移 \(displacement) m")
        XCTAssertTrue(displacement < 700,
                      "偏移量应在合理区间，实际位移 \(displacement) m")

        // 归一化后若落到了校园西侧空白区（无球场、无公寓），说明基准选错了。
        let schoolBoundary = (minLongitude: 117.975247, maxLongitude: 117.988588)
        XCTAssertTrue(movedLongitude >= schoolBoundary.minLongitude
                      && movedLongitude <= schoolBoundary.maxLongitude,
                      "归一化后的经度应仍在校园范围内，实际 \(movedLongitude)")
    }

    func testCalibrationMovesTrackTowardPitch() {
        let route = try! loadCampusTrack()
        let center = route.points.reduce(into: (lat: 0.0, lon: 0.0)) { sum, point in
            sum.lat += point.latitude
            sum.lon += point.longitude
        }
        let latitude = center.lat / Double(route.points.count)
        let longitude = center.lon / Double(route.points.count)

        // 把轨迹往西南方向平移，应更靠近足球场。
        let offset = CalibrationOffset(eastMeters: -160, northMeters: -120)
        let moved = offset.apply(to: route.points)
        let movedCenter = moved.reduce(into: (lat: 0.0, lon: 0.0)) { sum, point in
            sum.lat += point.latitude
            sum.lon += point.longitude
        }
        let before = Self.metersBetween((latitude, longitude), pitch)
        let after = Self.metersBetween(
            (movedCenter.lat / Double(moved.count), movedCenter.lon / Double(moved.count)), pitch)
        XCTAssertTrue(after < before, "标定偏移后应更靠近足球场：\(before) m → \(after) m")
    }

    private static func metersBetween(_ a: (latitude: Double, longitude: Double),
                                      _ b: (latitude: Double, longitude: Double)) -> Double {
        let north = (a.latitude - b.latitude) * 111_320
        let east = (a.longitude - b.longitude) * 111_320 * cos(a.latitude * .pi / 180)
        return (north * north + east * east).squareRoot()
    }
}
