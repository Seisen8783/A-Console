import Foundation
import XCTest
@testable import UnRealConsole

final class TrackRouteTests: XCTestCase {
    private func load(_ body: String) throws -> TrackRoute {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".gpx")
        defer { try? FileManager.default.removeItem(at: file) }
        let xml = "<gpx xmlns='http://www.topografix.com/GPX/1/1'><trk>\(body)</trk></gpx>"
        try Data(xml.utf8).write(to: file)
        return try TrackRoute.load(file)
    }

    func testOffsetsAndSegmentBoundaries() throws {
        let route = try load("""
            <trkseg>
              <trkpt lat="37" lon="117"><time>2026-11-20T11:00:00Z</time></trkpt>
              <trkpt lat="37.1" lon="117"><time>2026-11-20T11:00:01.500Z</time></trkpt>
            </trkseg><trkseg>
              <trkpt lat="37.2" lon="117"><time>2026-11-20T19:00:01.500+08:00</time></trkpt>
            </trkseg>
            """)
        XCTAssertEqual(route.segmentStarts, [0, 2])
        XCTAssertEqual(route.points.map(\.offset), [0, 1.5, 1.5])
        XCTAssertNil(route.timingIssue)
    }

    func testMissingOrReversedTimesStillAllowPreview() throws {
        for time in ["", "<time>2026-11-20T10:59:59Z</time>"] {
            let route = try load("""
                <trkseg>
                  <trkpt lat="37" lon="117"><time>2026-11-20T11:00:00Z</time></trkpt>
                  <trkpt lat="37.1" lon="117">\(time)</trkpt>
                </trkseg>
                """)
            XCTAssertNotNil(route.timingIssue)
        }
    }

    func testRejectsOutOfRangeCoordinates() {
        XCTAssertThrowsError(try load("""
            <trkseg><trkpt lat="91" lon="117"/><trkpt lat="0" lon="0"/></trkseg>
            """))
    }
}
