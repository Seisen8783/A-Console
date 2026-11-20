import XCTest
@testable import UnRealConsole

/// 两圈融合成一圈（中线拟合）的精度核查。
///
/// 场景：轨迹文件记录了两圈，但实际只需要跑一圈。
/// 控制台用 `LoopRoute.fit` 从多圈轨迹拟合出单圈中线，
/// 这里验证拟合中线与原始轨迹的偏差、以及拼接后的累计误差。
final class LoopFitAccuracyTests: XCTestCase {
    /// 默认校园轨迹（约 906 米，两圈），从仓库资源读取。
    ///
    /// 用真实的 KML 加载路径，这样 `untimedSegments` 会被正确填充，
    /// `LoopRoute.fit` 才有输入。
    private func locateDefaultTrack() -> URL? {
        var directory = URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
        for _ in 0..<8 {
            let candidate = directory.appendingPathComponent("core/assets/default.kml")
            if FileManager.default.fileExists(atPath: candidate.path) { return candidate }
            let parent = directory.deletingLastPathComponent()
            if parent.path == directory.path { break }
            directory = parent
        }
        return nil
    }

    private func loadDefaultTrack() throws -> TrackRoute {
        guard let url = locateDefaultTrack() else {
            throw XCTSkip("默认轨迹资源不在本机，跳过。")
        }
        let route = try KMLRoute.load(url)
        XCTAssertNotNil(route.untimedSegments, "KML 无时间戳时应落到未计时分段")
        return route
    }

    /// 逐点距离（米）。
    private func meters(_ a: TrackPoint, _ b: TrackPoint) -> Double {
        let north = (a.latitude - b.latitude) * 111_320
        let east = (a.longitude - b.longitude) * 111_320 * cos(a.latitude * .pi / 180)
        return (north * north + east * east).squareRoot()
    }

    /// 折线总长（米）。
    private func length(_ points: [TrackPoint]) -> Double {
        zip(points, points.dropFirst()).reduce(0) { $0 + meters($1.0, $1.1) }
    }

    func testDefaultTrackContainsTwoLaps() {
        let route = try! loadDefaultTrack()
        let total = length(route.points)
        // 单圈约 450 米，默认轨迹应为两圈量级。
        XCTAssertGreaterThan(total, 800, "默认轨迹总长应超过 800 米，实际 \(total)")
        XCTAssertLessThan(total, 1000, "默认轨迹总长应小于 1000 米，实际 \(total)")
    }

    func testFittedCenterlineLengthMatchesHalfOfOriginal() {
        let route = try! loadDefaultTrack()
        let original = length(route.points)
        guard let loop = LoopRoute.fit(route) else {
            XCTFail("两圈轨迹应能拟合出单圈中线")
            return
        }
        let fitted = length(loop.centerline)

        // 拟合出的中线长度应接近原轨迹的一半。
        let ratio = fitted / original
        XCTAssertTrue(ratio > 0.45 && ratio < 0.55,
                      "中线长度应约为原轨迹一半，实际比例 \(ratio)（原 \(original) m，中线 \(fitted) m）")

        // 中线自身应闭合（首尾相接）。
        XCTAssertLessThan(meters(loop.centerline.first!, loop.centerline.last!), 5.0,
                          "中线首尾应重合，实际间距 \(meters(loop.centerline.first!, loop.centerline.last!)) m")
    }

    func testFittedCenterlineStaysNearOriginalTrack() {
        let route = try! loadDefaultTrack()
        guard let loop = LoopRoute.fit(route) else {
            XCTFail("两圈轨迹应能拟合出单圈中线")
            return
        }
        // 中线上每个点到原始轨迹的最短距离应很小。
        var worst = 0.0
        for point in loop.centerline {
            var best = Double.greatestFiniteMagnitude
            for original in route.points {
                best = min(best, meters(point, original))
                if best < 0.5 { break }
            }
            worst = max(worst, best)
        }
        XCTAssertLessThan(worst, 12.0, "中线最大偏离原始轨迹 \(worst) 米")
    }

    func testTwoLapTimelineIsContinuousAtSeam() {
        let route = try! loadDefaultTrack()
        // 用中线作为路线，模拟两圈拼接。
        guard let loop = LoopRoute.fit(route) else {
            XCTFail("两圈轨迹应能拟合出单圈中线")
            return
        }
        let single = TrackRoute(name: "single", points: loop.centerline,
                                segmentStarts: [0], timingIssue: nil)
        let timeline = ReplayTimeline.build(single, laps: 2)
        XCTAssertGreaterThan(timeline.count, single.points.count)

        // 拼接点（第 1 圈末 → 第 2 圈首）应连续，不应跳变。
        let seam = single.points.count - 1
        let jump = meters(timeline[seam], timeline[seam + 1])
        let typical = length(single.points) / Double(single.points.count)
        XCTAssertLessThan(jump, max(typical * 3, 2.0),
                          "圈与圈之间出现跳变：\(jump) 米（典型步长 \(typical) 米）")

        // 时间戳必须单调不减。
        for index in 1..<timeline.count {
            XCTAssertGreaterThanOrEqual(timeline[index].offset, timeline[index - 1].offset,
                                        "第 \(index) 个点的时间戳倒序")
        }
    }

    func testLapsDoNotAccumulateDistanceDrift() {
        let route = try! loadDefaultTrack()
        guard let loop = LoopRoute.fit(route) else {
            XCTFail("两圈轨迹应能拟合出单圈中线")
            return
        }
        let single = TrackRoute(name: "single", points: loop.centerline,
                                segmentStarts: [0], timingIssue: nil)
        let oneLap = length(ReplayTimeline.build(single, laps: 1))
        let twoLaps = length(ReplayTimeline.build(single, laps: 2))
        let threeLaps = length(ReplayTimeline.build(single, laps: 3))

        // 每圈长度应基本一致，误差不超过 1%。
        XCTAssertTrue(abs(twoLaps / 2 - oneLap) / oneLap < 0.01,
                      "两圈平均圈长应接近单圈：\(oneLap) vs \(twoLaps / 2)")
        XCTAssertTrue(abs(threeLaps / 3 - oneLap) / oneLap < 0.01,
                      "三圈平均圈长应接近单圈：\(oneLap) vs \(threeLaps / 3)")
    }

    /// 圈数识别依赖「第二圈穿过起始方向且横向偏差 ≤ 12 米」。
    /// 原始轨迹首尾不重合时（实测 21 米），这个识别会失效——
    /// 这里量化该风险，确认拟合仍能正确识别圈数。
    func testFitDetectsLapsDespiteOpenLoopEndpoints() {
        let route = try! loadDefaultTrack()
        guard let loop = LoopRoute.fit(route) else {
            XCTFail("两圈轨迹应能拟合出单圈中线")
            return
        }
        // 应识别为 2 圈。
        XCTAssertEqual(loop.sourceLaps, 2, "应识别出 2 圈，实际 \(loop.sourceLaps)")

        // 首尾不重合的距离（原始数据的真实情况）。
        guard let first = route.points.first, let last = route.points.last else { return }
        let gap = meters(first, last)
        XCTAssertGreaterThan(gap, 5.0, "原始轨迹首尾本就不重合（实测约 21 米），用于验证识别容错")
    }

    /// 拟合出的中线应保持闭合，这是两圈融合后能连续回放的前提。
    func testFittedCenterlineIsClosedAndUsable() {
        let route = try! loadDefaultTrack()
        guard let loop = LoopRoute.fit(route) else {
            XCTFail("两圈轨迹应能拟合出单圈中线")
            return
        }
        let centerline = loop.centerline
        XCTAssertGreaterThanOrEqual(centerline.count, 32)

        // 首尾必须重合（否则回放一圈会跳回起点）。
        let closure = meters(centerline.first!, centerline.last!)
        XCTAssertLessThan(closure, 5.0, "中线首尾应重合，实际 \(closure) 米")

        // 中线不应有异常跳变。
        var worst = 0.0
        for index in 1..<centerline.count {
            worst = max(worst, meters(centerline[index - 1], centerline[index]))
        }
        XCTAssertLessThan(worst, 8.0, "中线存在异常跳变，最大步长 \(worst) 米")
    }
}
