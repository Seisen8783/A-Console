import XCTest
@testable import UnRealConsole

/// 轨迹来源与坐标基准的联动。
///
/// 内置默认轨迹的基准是确定的（WGS-84），不受用户「坐标基准」选择影响；
/// 本地导入的轨迹沿用用户选择。
final class TrackSourceTests: XCTestCase {
    func testBundledTrackLoadsWithWGS84RegardlessOfUserChoice() {
        let model = ConsoleModel.shared
        // 用户把基准选成 GCJ-02（默认值）。
        model.coordinateSystem = .gcj02
        model.loadBundledTrack()

        XCTAssertNotNil(model.route, "应载入内置默认轨迹")
        XCTAssertEqual(model.trackSource, .bundled)
        // 用户的基准选择仍保留，但内置轨迹不按它解读。
        XCTAssertEqual(model.coordinateSystem, .gcj02)
        XCTAssertEqual(model.effectiveCoordinateSystem, .wgs84)
    }

    func testLocalTrackUsesUserCoordinateSystem() {
        let model = ConsoleModel.shared
        model.coordinateSystem = .gcj02
        model.trackSource = .localFile
        XCTAssertEqual(model.effectiveCoordinateSystem, .gcj02)

        model.coordinateSystem = .wgs84
        XCTAssertEqual(model.effectiveCoordinateSystem, .wgs84)
    }

    func testBundledTrackIgnoresCoordinateSystemChange() {
        let model = ConsoleModel.shared
        model.loadBundledTrack()
        let before = model.route?.points.first
        // 用户改基准，内置轨迹不应被重新解读。
        model.setCoordinateSystem(.bd09)
        let after = model.route?.points.first
        XCTAssertEqual(before?.latitude, after?.latitude)
        XCTAssertEqual(before?.longitude, after?.longitude)
        XCTAssertEqual(model.effectiveCoordinateSystem, .wgs84)
    }

    func testBundledTrackIsTheCampusCorridor() throws {
        let model = ConsoleModel.shared
        model.loadBundledTrack()
        guard let route = model.route else {
            XCTFail("应载入内置默认轨迹")
            return
        }
        // 默认轨迹没有时间戳，会按配速重采样并拟合出单圈中线，
        // 因此点数既不是原始的 644，也不是固定的。
        let segments = try XCTUnwrap(route.untimedSegments)
        XCTAssertEqual(segments.count, 1)

        // 中心落在滨州（山东航空学院），这是内置轨迹最本质的特征。
        let points = segments[0]
        let latitude = points.reduce(0) { $0 + $1.latitude } / Double(points.count)
        let longitude = points.reduce(0) { $0 + $1.longitude } / Double(points.count)
        XCTAssertEqual(latitude, 37.388, accuracy: 0.01)
        XCTAssertEqual(longitude, 117.984, accuracy: 0.01)

        // 单圈长度约 450 米。
        let length = TrackMetrics.length(points)
        XCTAssertTrue(length > 300 && length < 600, "单圈长度应约 450 米，实际 \(length)")
    }

    func testBundledCoordinateSystemIsWGS84() {
        // 内置轨迹与 core/assets/campus.geojson 逐点一致，后者声明为 WGS-84。
        XCTAssertEqual(ConsoleModel.bundledCoordinateSystem, .wgs84)
    }

    func testTrackSourceDisplayNames() {
        XCTAssertEqual(TrackSource.bundled.displayName, "默认路径")
        XCTAssertEqual(TrackSource.localFile.displayName, "本地导入")
    }
}
