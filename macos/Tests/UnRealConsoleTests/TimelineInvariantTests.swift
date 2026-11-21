import XCTest
@testable import UnRealConsole

/// 时间轴与累计距离必须始终等长。
///
/// 现场崩溃（v2.0.25，ConsoleModel.swift:155）：
/// 「应用新设置」时只换了 `timeline`、没换 `timelineCumulative`，
/// 两个数组长度脱节，界面刷新按新长度取下标 → Index out of range / SIGTRAP。
final class TimelineInvariantTests: XCTestCase {
    private func loadedModel() -> ConsoleModel {
        let model = ConsoleModel.shared
        model.lengthMode = .laps
        model.targetKilometers = ""
        model.setLaps("1")
        model.loadBundledTrack()
        return model
    }

    /// 任何时刻，两个数组长度都必须一致。
    private func assertInSync(_ model: ConsoleModel, _ label: String,
                              file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(model.timeline.count, model.timelineCumulative.count,
                       "\(label)：时间轴与累计距离长度不一致", file: file, line: line)
    }

    // MARK: - 崩溃现场

    /// 直接复现崩溃路径：换成一条更长的轨迹后立刻读已模拟米数。
    ///
    /// 修复前：`timeline` 变长、累计距离没变，按新下标取值越界。
    func testSetTimelineKeepsCumulativeInSync() {
        let model = loadedModel()
        assertInSync(model, "载入默认轨迹后")

        // 造一条比默认轨迹长得多的时间轴。
        let long = (0..<500).map {
            TrackPoint(latitude: 37.388 + Double($0) * 1e-5,
                       longitude: 117.984 + Double($0) * 1e-5, offset: Double($0))
        }
        model.setTimeline(long)
        assertInSync(model, "替换为 500 点后")
        XCTAssertEqual(model.timeline.count, 500)

        // 崩溃点就是这一句：修复前会越过累计距离的末尾。
        _ = model.simulatedMeters(at: 499)

        // 缩短回来也要同步。
        model.setTimeline(Array(long.prefix(10)))
        assertInSync(model, "缩短为 10 点后")
        XCTAssertEqual(model.simulatedMeters(at: 9), model.timelineCumulative[9])
    }

    /// 空时间轴不能崩（`1..<0` 是非法区间）。
    func testEmptyTimelineIsSafe() {
        let model = loadedModel()
        model.setTimeline([])
        assertInSync(model, "清空后")
        XCTAssertEqual(model.simulatedMeters, 0)
        XCTAssertEqual(model.simulatedMeters(at: 0), 0)
        XCTAssertEqual(model.simulatedMeters(at: 999), 0)
    }

    /// 下标超界时钳到末尾，而不是崩。
    func testSimulatedMetersClampsOutOfRangeIndex() {
        let model = loadedModel()
        let points = (0..<20).map {
            TrackPoint(latitude: 37.388 + Double($0) * 1e-5,
                       longitude: 117.984 + Double($0) * 1e-5, offset: Double($0))
        }
        model.setTimeline(points)

        XCTAssertEqual(model.simulatedMeters(at: -5), 0)
        XCTAssertEqual(model.simulatedMeters(at: 10_000),
                       model.timelineCumulative.last ?? -1)
        XCTAssertEqual(model.simulatedMeters(at: 20),
                       model.timelineCumulative.last ?? -1)
    }

    // MARK: - 累计距离本身

    func testCumulativeDistancesHandlesEdgeCases() {
        XCTAssertTrue(TrackMetrics.cumulativeDistances([]).isEmpty)
        XCTAssertEqual(TrackMetrics.cumulativeDistances([TrackPoint(latitude: 37, longitude: 117, offset: 0)]),
                       [0])
    }

    func testCumulativeDistancesMatchesLength() {
        let points = (0..<50).map {
            TrackPoint(latitude: 37 + Double($0) * 0.0001,
                       longitude: 117 + Double($0) * 0.0001, offset: Double($0))
        }
        let cumulative = TrackMetrics.cumulativeDistances(points)
        XCTAssertEqual(cumulative.count, points.count)
        XCTAssertEqual(cumulative.last ?? 0,
                       TrackMetrics.length(points), accuracy: 1e-9)
    }

    // MARK: - 长度模式切换后仍然同步

    func testSwitchingLengthModeKeepsArraysInSync() {
        let model = loadedModel()
        model.setLaps("3")
        assertInSync(model, "3 圈")

        model.setLengthMode(.kilometers)
        model.setTargetKilometers("1.50")
        assertInSync(model, "1.5 公里")

        model.setLengthMode(.laps)
        assertInSync(model, "切回圈数")
    }
}
