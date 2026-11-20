import Foundation
import XCTest
@testable import NoRealConsole

final class KMLRouteTests: XCTestCase {
    func testRepositoryKMZWithLocalizedEntry() throws {
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let route = try TrackRoute.load(root.appendingPathComponent("core/assets/default.kmz"))
        XCTAssertNotNil(route.untimedSegments)
        XCTAssertGreaterThan(route.points.count, 2)
        XCTAssertGreaterThan(route.duration, 0)
        XCTAssertEqual(route.points[0].latitude, 37.388089191992755, accuracy: 0.000001)
        XCTAssertEqual(route.points[0].longitude, 117.98433160802179, accuracy: 0.000001)
        let loop = try XCTUnwrap(LoopRoute.fit(route))
        XCTAssertEqual(loop.sourceLaps, 2)
        XCTAssertEqual(loop.meters, 431, accuracy: 3)
        XCTAssertEqual(loop.centerline.first?.latitude, loop.centerline.last?.latitude)
        XCTAssertEqual(loop.centerline.first?.longitude, loop.centerline.last?.longitude)
        let slower = KMLRoute.withPace(route, secondsPerKilometer: 480)
        XCTAssertEqual(slower.duration / route.duration, 480.0 / 449.0, accuracy: 0.000001)
    }

    func testPaceAcceptsExactSecondsAndRejectsInvalidInput() {
        XCTAssertEqual(ReplayPace.format(KMLRoute.defaultPace), "7:29")
        XCTAssertEqual(ReplayPace.parse("7:29"), 449)
        XCTAssertEqual(ReplayPace.parse(" 07:30 "), 450)
        for value in ["", "7", "7:9", "7:60", "-7:29", "0:00"] {
            XCTAssertNil(ReplayPace.parse(value))
        }
    }

    func testKMLTrackRetainsTimestamps() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".kml")
        defer { try? FileManager.default.removeItem(at: file) }
        try Data("""
            <kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2">
              <Placemark><gx:Track>
                <when>2026-11-20T11:00:00Z</when><when>2026-11-20T11:00:04.500Z</when>
                <gx:coord>117 37 0</gx:coord><gx:coord>117.01 37 0</gx:coord>
              </gx:Track></Placemark>
            </kml>
            """.utf8).write(to: file)
        let route = try TrackRoute.load(file)
        XCTAssertNil(route.untimedSegments)
        XCTAssertEqual(route.points.map(\.offset), [0, 4.5])
        XCTAssertEqual(route.points.last?.longitude, 117.01)
    }
}
