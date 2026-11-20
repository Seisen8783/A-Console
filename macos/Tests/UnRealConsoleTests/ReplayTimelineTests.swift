import XCTest
@testable import UnRealConsole

final class ReplayTimelineTests: XCTestCase {
    private let route = TrackRoute(
        name: "闭合轨迹",
        points: [
            TrackPoint(latitude: 37, longitude: 117, offset: 0),
            TrackPoint(latitude: 37.01, longitude: 117, offset: 10),
            TrackPoint(latitude: 37, longitude: 117, offset: 20)
        ],
        segmentStarts: [0], timingIssue: nil
    )

    func testLapBoundariesAndFinalPoint() {
        let laps = 3
        let timeline = ReplayTimeline.build(route, laps: laps)
        XCTAssertEqual(timeline.map(\.offset), [0, 10, 20, 30, 40, 50, 60])
        let labels = timeline.indices.map { ReplayTimeline.lap(at: $0, pointsPerLap: 3, totalLaps: laps) }
        XCTAssertEqual(labels, [1, 1, 2, 2, 3, 3, 3])
    }

    func testPaceChangeAndLapsKeepPausePointAndElapsedHistory() {
        let initial = ReplayTimeline.build(route, laps: 2)
        let faster = ReplayTimeline.build(route, laps: 3, scale: 0.5, keeping: initial.prefix(4))
        XCTAssertEqual(faster.map(\.offset), [0, 10, 20, 30, 35, 40, 45])
        XCTAssertEqual(faster.prefix(4).map(\.latitude), initial.prefix(4).map(\.latitude))
        XCTAssertEqual(faster[3].latitude, initial[3].latitude)
        let slower = ReplayTimeline.build(route, laps: 2, scale: 2, keeping: faster.prefix(4))
        XCTAssertEqual(slower.map(\.offset), [0, 10, 20, 30, 50])
        XCTAssertTrue(ReplayTimeline.build(route, laps: 1, keeping: faster.prefix(4)).isEmpty)
    }
}
