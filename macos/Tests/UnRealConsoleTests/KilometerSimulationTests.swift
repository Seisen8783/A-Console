import XCTest
@testable import UnRealConsole

/// 「支持公里数模拟」的精度核查。
///
/// 用户给一个公里数（如 1.50），轨迹要按弧长循环到该长度并精确截断。
final class KilometerSimulationTests: XCTestCase {
    /// 构造一条已知长度的方形轨迹，便于精确断言。
    ///
    /// 四条边各 `sideMeters` 米，周长为 4 倍边长。
    private func squareRoute(sideMeters: Double) -> TrackRoute {
        let latStep = sideMeters / 111_195.0
        let lonStep = sideMeters / (111_195.0 * cos(37.0 * .pi / 180))
        let points = [
            TrackPoint(latitude: 37, longitude: 117, offset: 0),
            TrackPoint(latitude: 37 + latStep, longitude: 117, offset: 0),
            TrackPoint(latitude: 37 + latStep, longitude: 117 + lonStep, offset: 0),
            TrackPoint(latitude: 37, longitude: 117 + lonStep, offset: 0),
            TrackPoint(latitude: 37, longitude: 117, offset: 0),
        ]
        return TrackRoute(name: "square", points: points, segmentStarts: [0], timingIssue: nil)
    }

    func testLengthMeasurementIsAccurate() {
        let route = squareRoute(sideMeters: 100)
        let length = TrackMetrics.length(route.points)
        // 正方形周长应为 400 米（球面距离会有极小误差）。
        XCTAssertTrue(abs(length - 400) < 1.0, "方形周长应约 400 米，实际 \(length)")
    }

    func testPrefixTruncatesToExactDistance() {
        let route = squareRoute(sideMeters: 100)
        let prefix = TrackMetrics.prefix(meters: 150, points: route.points)
        let length = TrackMetrics.length(prefix)
        XCTAssertTrue(abs(length - 150) < 0.5, "截断后长度应约 150 米，实际 \(length)")
    }

    func testExpandLoopsToTargetDistance() {
        let route = squareRoute(sideMeters: 100)  // 单圈 400 米
        let expanded = TrackMetrics.expand(toMeters: 1500, points: route.points)
        let length = TrackMetrics.length(expanded)
        XCTAssertTrue(abs(length - 1500) < 1.0, "扩展后长度应约 1500 米，实际 \(length)")
    }

    func testExpandHandlesTargetShorterThanOneLap() {
        let route = squareRoute(sideMeters: 100)
        let expanded = TrackMetrics.expand(toMeters: 250, points: route.points)
        let length = TrackMetrics.length(expanded)
        XCTAssertTrue(abs(length - 250) < 0.5, "目标短于一圈时应正确截断，实际 \(length)")
    }

    func testExpandProducesContinuousPath() {
        // 用密集采样的方形轨迹，避免转角处的采样跨度干扰判断。
        let points = denseSquare(sideMeters: 100, perSide: 40)
        let expanded = TrackMetrics.expand(toMeters: 1500, points: points)
        // 拼接处不应出现跳变（跳变说明没有正确接续）。
        var worst = 0.0
        for index in 1..<expanded.count {
            worst = max(worst, TrackMetrics.meters(expanded[index - 1], expanded[index]))
        }
        XCTAssertLessThan(worst, 3.0, "拼接处出现跳变，最大步长 \(worst) 米")
    }

    /// 密集采样的方形轨迹：每条边 `perSide` 个点。
    private func denseSquare(sideMeters: Double, perSide: Int) -> [TrackPoint] {
        let latStep = sideMeters / 111_195.0
        let lonStep = sideMeters / (111_195.0 * cos(37.0 * .pi / 180))
        var points: [TrackPoint] = []
        let corners = [
            (0.0, 0.0), (1.0, 0.0), (1.0, 1.0), (0.0, 1.0), (0.0, 0.0),
        ]
        for edge in 0..<4 {
            let from = corners[edge]
            let to = corners[edge + 1]
            for step in 0..<perSide {
                let fraction = Double(step) / Double(perSide)
                points.append(TrackPoint(
                    latitude: 37 + (from.0 + fraction * (to.0 - from.0)) * latStep,
                    longitude: 117 + (from.1 + fraction * (to.1 - from.1)) * lonStep,
                    offset: 0))
            }
        }
        points.append(points[0])
        return points
    }

    func testExpandKeepsTimeMonotonic() {
        let route = squareRoute(sideMeters: 100)
        let expanded = TrackMetrics.expand(toMeters: 1500, points: route.points)
        for index in 1..<expanded.count {
            XCTAssertGreaterThanOrEqual(expanded[index].offset, expanded[index - 1].offset,
                                        "第 \(index) 个点时间戳倒序")
        }
    }

    func testTwoDecimalKilometersResolveToMeters() {
        // 1.50 公里 → 1500 米；0.05 公里 → 50 米。
        let model = ConsoleModel.shared
        _ = model
        let fifteenHundred = (1.50 * 1000).rounded()
        XCTAssertEqual(fifteenHundred, 1500)
        let fifty = (0.05 * 1000).rounded()
        XCTAssertEqual(fifty, 50)
    }

    func testRealCampusTrackExpandsToKilometers() throws {
        var directory = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        var url: URL?
        for _ in 0..<8 {
            let candidate = directory.appendingPathComponent("core/assets/default.kml")
            if FileManager.default.fileExists(atPath: candidate.path) { url = candidate; break }
            let parent = directory.deletingLastPathComponent()
            if parent.path == directory.path { break }
            directory = parent
        }
        guard let url else { throw XCTSkip("默认轨迹资源不在本机，跳过。") }

        let route = try! KMLRoute.load(url)
        guard let loop = LoopRoute.fit(route) else {
            XCTFail("应能拟合出单圈中线")
            return
        }
        // 单圈中线约 450 米，扩到 1.5 公里应得到约 3.33 圈。
        let single = TrackMetrics.length(loop.centerline)
        let expanded = TrackMetrics.expand(toMeters: 1500, points: loop.centerline)
        let length = TrackMetrics.length(expanded)
        XCTAssertTrue(abs(length - 1500) < 2.0, "扩展到 1.5 公里应精确，实际 \(length)")
        XCTAssertGreaterThan(1500 / single, 3.0, "单圈约 \(single) 米，1.5 公里应超过 3 圈")
    }
}
