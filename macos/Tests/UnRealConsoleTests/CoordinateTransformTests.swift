import XCTest
@testable import UnRealConsole

final class CoordinateTransformTests: XCTestCase {
    /// 业界通用测试向量：WGS-84 (116.391300, 39.907400) → GCJ-02 ≈ (116.397428, 39.908702)。
    /// 不同实现的参数略有差异，允许约 15 米误差。
    private let vector = (longitude: 116.391300, latitude: 39.907400)
    private let vectorGCJ02 = (longitude: 116.397428, latitude: 39.908702)

    func testWGS84ToGCJ02MatchesReference() {
        let result = CoordinateTransform.wgs84ToGCJ02(longitude: vector.longitude,
                                                      latitude: vector.latitude)
        XCTAssertEqual(result.longitude, vectorGCJ02.longitude, accuracy: 0.0002)
        XCTAssertEqual(result.latitude, vectorGCJ02.latitude, accuracy: 0.0002)
    }

    /// 滨州校园的精确偏移。
    ///
    /// 用 eviltransform 的权威实现算出 (117.990750, 37.388993)，
    /// 即东约 526 米、北约 76 米。
    /// 这条断言曾抓到一个真 bug：transformLon 的正弦项误用 y，
    /// 导致东向少算 57 米（469 而非 526）。
    func testBinzhouOffsetMatchesReferenceImplementation() {
        let campus = (longitude: 117.984803, latitude: 37.388301)
        let result = CoordinateTransform.wgs84ToGCJ02(longitude: campus.longitude,
                                                      latitude: campus.latitude)
        XCTAssertEqual(result.longitude, 117.990750, accuracy: 0.00005)
        XCTAssertEqual(result.latitude, 37.388993, accuracy: 0.00005)

        let east = (result.longitude - campus.longitude) * 111_320
            * cos(campus.latitude * .pi / 180)
        XCTAssertEqual(east, 526, accuracy: 5)
    }

    /// 用真实地物交叉验证：OSM 的 WGS-84 足球场转换后，
    /// 应比错误实现更靠近腾讯地图的 GCJ-02 塑胶篮球场。
    func testRealLandmarkCrossCheck() {
        let osmSoccerPitch = (longitude: 117.982438, latitude: 37.387769)
        let tencentBasketball = (longitude: 117.989281, latitude: 37.388547)
        let converted = CoordinateTransform.wgs84ToGCJ02(longitude: osmSoccerPitch.longitude,
                                                          latitude: osmSoccerPitch.latitude)
        let dx = (converted.longitude - tencentBasketball.longitude) * 111_320
            * cos(37.388 * .pi / 180)
        let dy = (converted.latitude - tencentBasketball.latitude) * 111_320
        let distance = (dx * dx + dy * dy).squareRoot()
        // 两个 POI 不是同一个点，但正确算法下应在 100 米内；
        // 错误算法（正弦项用 y）会到 137 米。
        XCTAssertLessThan(distance, 110)
    }

    func testGCJ02ToWGS84IsInverseOfForward() {
        let forward = CoordinateTransform.wgs84ToGCJ02(longitude: vector.longitude,
                                                       latitude: vector.latitude)
        let back = CoordinateTransform.gcj02ToWGS84(longitude: forward.longitude,
                                                     latitude: forward.latitude)
        XCTAssertEqual(back.longitude, vector.longitude, accuracy: 1e-7)
        XCTAssertEqual(back.latitude, vector.latitude, accuracy: 1e-7)
    }

    func testBinzhouCampusRoundTrip() {
        // 山东航空学院校园跑道附近的实际坐标（WGS-84）。
        let campus = (longitude: 117.984803, latitude: 37.388301)
        let forward = CoordinateTransform.wgs84ToGCJ02(longitude: campus.longitude,
                                                       latitude: campus.latitude)
        // 滨州一带 GCJ-02 相对 WGS-84 的偏移在数百米量级。
        let eastMeters = (forward.longitude - campus.longitude) * 111_320 * cos(campus.latitude * .pi / 180)
        let northMeters = (forward.latitude - campus.latitude) * 111_320
        XCTAssertGreaterThan(eastMeters, 300)
        XCTAssertLessThan(eastMeters, 700)
        XCTAssertGreaterThan(abs(northMeters), 20)
        XCTAssertLessThan(abs(northMeters), 200)

        let back = CoordinateTransform.gcj02ToWGS84(longitude: forward.longitude,
                                                     latitude: forward.latitude)
        XCTAssertEqual(back.longitude, campus.longitude, accuracy: 1e-7)
        XCTAssertEqual(back.latitude, campus.latitude, accuracy: 1e-7)
    }

    func testOutsideChinaIsNotShifted() {
        // 境外坐标不做偏移，否则会越偏越远。
        let tokyo = (longitude: 139.6917, latitude: 35.6895)
        let result = CoordinateTransform.wgs84ToGCJ02(longitude: tokyo.longitude,
                                                      latitude: tokyo.latitude)
        XCTAssertEqual(result.longitude, tokyo.longitude, accuracy: 1e-9)
        XCTAssertEqual(result.latitude, tokyo.latitude, accuracy: 1e-9)
    }

    func testBD09RoundTripThroughGCJ02() {
        let point = (longitude: 116.397428, latitude: 39.908702)
        let bd = CoordinateTransform.gcj02ToBD09(longitude: point.longitude, latitude: point.latitude)
        let back = CoordinateTransform.bd09ToGCJ02(longitude: bd.longitude, latitude: bd.latitude)
        // BD-09 公式本身带低阶截断，容差取 1e-5 度（约 1 米）。
        XCTAssertEqual(back.longitude, point.longitude, accuracy: 1e-5)
        XCTAssertEqual(back.latitude, point.latitude, accuracy: 1e-5)
    }

    func testCoordinateSystemNormalizationShiftsGCJ02Track() {
        let gcj = TrackPoint(latitude: vectorGCJ02.latitude, longitude: vectorGCJ02.longitude, offset: 0)
        let normalized = CoordinateSystem.gcj02.normalized(gcj)
        // 归一化后应回到 WGS-84 参考值附近。
        XCTAssertEqual(normalized.longitude, vector.longitude, accuracy: 0.0002)
        XCTAssertEqual(normalized.latitude, vector.latitude, accuracy: 0.0002)
        XCTAssertEqual(normalized.offset, gcj.offset)
    }

    func testWGS84NormalizationIsIdentity() {
        let point = TrackPoint(latitude: 37.388301, longitude: 117.984803, offset: 42)
        let normalized = CoordinateSystem.wgs84.normalized(point)
        XCTAssertEqual(normalized.latitude, point.latitude)
        XCTAssertEqual(normalized.longitude, point.longitude)
        XCTAssertEqual(normalized.offset, point.offset)
    }

    func testCalibrationOffsetAppliesMeters() {
        let point = TrackPoint(latitude: 37.388301, longitude: 117.984803, offset: 0)
        let offset = CalibrationOffset(eastMeters: 100, northMeters: -50)
        let moved = offset.apply(to: point)
        let eastMeters = (moved.longitude - point.longitude) * 111_320 * cos(point.latitude * .pi / 180)
        let northMeters = (moved.latitude - point.latitude) * 111_320
        XCTAssertEqual(eastMeters, 100, accuracy: 0.5)
        XCTAssertEqual(northMeters, -50, accuracy: 0.5)
        XCTAssertEqual(moved.offset, point.offset)
    }

    func testZeroCalibrationKeepsPointsUntouched() {
        let point = TrackPoint(latitude: 37.388301, longitude: 117.984803, offset: 7)
        let moved = CalibrationOffset.zero.apply(to: point)
        XCTAssertEqual(moved.latitude, point.latitude)
        XCTAssertEqual(moved.longitude, point.longitude)
        XCTAssertEqual(moved.offset, point.offset)
    }
}
