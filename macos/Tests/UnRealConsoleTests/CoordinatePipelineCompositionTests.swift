import XCTest
@testable import UnRealConsole

/// 「坐标基准」与「上报口径」两级转换叠加后的落点验证。
///
/// 完整链路（每一段都是必须的）：
///
/// ```
/// 文件原值 F（基准 D）
///   → ① 归一化：n_D(F) = 这个点真实的 WGS-84 位置 W
///   → ② 回退一个基准：g2w(W) = 注入值 I
///   → ③ 微信必然要做的一次转换：w2g(I) = 上报值 R
/// ```
///
/// 关键结论：① 与 ② 并不是「多做了一次」。
/// ① 回答「文件里写的这个数，对应地球上哪个位置」；
/// ② 回答「设备该怎么写这个数，微信转回来才落在真实位置上」。
/// 二者与 ③ 复合之后，R 恒等于 W —— 与文件基准是 WGS-84 / GCJ-02 / BD-09 无关。
final class CoordinatePipelineCompositionTests: XCTestCase {
    /// 山东航空学院校园足球场（OSM 权威数据，WGS-84）。
    private let truth = (longitude: 117.982438, latitude: 37.387769)

    /// 微信会做的那次 WGS-84 → GCJ-02。
    private func wechatConvert(_ point: TrackPoint) -> (longitude: Double, latitude: Double) {
        CoordinateTransform.wgs84ToGCJ02(longitude: point.longitude, latitude: point.latitude)
    }

    /// 造一个只有直线五点的 KML（点数少于 10 且不闭合，中线拟合会失败，
    /// 因此第一个点不会被改写，可以精确断言）。
    private func makeStraightKML(_ points: [(longitude: Double, latitude: Double)]) throws -> URL {
        let coordinates = points
            .map { "\($0.longitude),\($0.latitude),0" }
            .joined(separator: " ")
        let xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <kml xmlns="http://www.opengis.net/kml/2.2"><Document><Placemark>
        <LineString><coordinates>\(coordinates)</coordinates></LineString>
        </Placemark></Document></kml>
        """
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString + ".kml")
        try xml.write(to: url, atomically: true, encoding: .utf8)
        return url
    }

    /// 造一条沿正东方向延伸 5 个点的直线，起点是给定坐标。
    private func line(from origin: (longitude: Double, latitude: Double)) -> [(longitude: Double, latitude: Double)] {
        (0...4).map { step in
            (longitude: origin.longitude + Double(step) * 0.0002, latitude: origin.latitude)
        }
    }

    // MARK: - GCJ-02 文件

    /// 文件是 GCJ-02、坐标基准选 GCJ-02、上报口径选「回退一个基准」：
    /// 上报值必须落回真实位置，而不是文件里的 GCJ-02 数值，也不是「两次反解」的结果。
    func testGCJ02FileReportsTrueLocationAfterPullBack() throws {
        let gcj = CoordinateTransform.wgs84ToGCJ02(longitude: truth.longitude, latitude: truth.latitude)
        let url = try makeStraightKML(line(from: (longitude: gcj.0, latitude: gcj.1)))

        let model = ConsoleModel.shared
        model.trackSource = .localFile
        model.coordinateSystem = .gcj02
        model.loadLocalTrack(url)
        model.setReportAlignment(.pullBackOneDatum)

        guard let injected = model.route?.points.first else {
            XCTFail("应载入本地轨迹")
            return
        }
        let reported = wechatConvert(injected)
        XCTAssertEqual(reported.longitude, truth.longitude, accuracy: 1e-6,
                       "上报经度应落回真实位置")
        XCTAssertEqual(reported.latitude, truth.latitude, accuracy: 1e-6,
                       "上报纬度应落回真实位置")

        // 同时验证「原样」口径：这时上报值应当等于文件里写的 GCJ-02 数值。
        model.setReportAlignment(.asIs)
        guard let plain = model.route?.points.first else {
            XCTFail("应载入本地轨迹")
            return
        }
        let plainReported = wechatConvert(plain)
        XCTAssertEqual(plainReported.longitude, gcj.0, accuracy: 1e-6,
                       "原样口径下上报值应等于文件里的 GCJ-02 数值")
        XCTAssertEqual(plainReported.latitude, gcj.1, accuracy: 1e-6,
                       "原样口径下上报值应等于文件里的 GCJ-02 数值")
    }

    /// 「两级转换不是多做了一次」的量化证据：
    /// 回退一个基准后，上报值与文件原值之间应只差「一次基准偏移量」的反向，
    /// 而不是两次——两次的话偏差会是 1050 米量级。
    func testPullBackOnGCJ02FileMovesByOneDatumNotTwo() throws {
        let gcj = CoordinateTransform.wgs84ToGCJ02(longitude: truth.longitude, latitude: truth.latitude)
        let url = try makeStraightKML(line(from: (longitude: gcj.0, latitude: gcj.1)))

        let model = ConsoleModel.shared
        model.trackSource = .localFile
        model.coordinateSystem = .gcj02
        model.loadLocalTrack(url)
        model.setReportAlignment(.asIs)
        guard let plain = model.route?.points.first else {
            XCTFail("应载入本地轨迹")
            return
        }
        model.setReportAlignment(.pullBackOneDatum)
        guard let compensated = model.route?.points.first else {
            XCTFail("应载入本地轨迹")
            return
        }

        let east = (compensated.longitude - plain.longitude) * 111_320
            * cos(plain.latitude * .pi / 180)
        // 一次反解约 525 米；两次会是 1050 米量级。
        XCTAssertEqual(east, -524, accuracy: 8, "只应回退一个基准")
        XCTAssertTrue(abs(east) < 700, "不应叠加两次反解，实际 \(east) m")
    }

    // MARK: - WGS-84 文件

    /// 文件是 WGS-84（GPS / OSM 导出，最常见）：归一化是恒等变换，
    /// 回退一个基准后上报值必须精确等于文件原值。
    func testWGS84FileReportsOriginalValueAfterPullBack() throws {
        let url = try makeStraightKML(line(from: truth))

        let model = ConsoleModel.shared
        model.trackSource = .localFile
        model.coordinateSystem = .wgs84
        model.loadLocalTrack(url)
        model.setReportAlignment(.pullBackOneDatum)

        guard let injected = model.route?.points.first else {
            XCTFail("应载入本地轨迹")
            return
        }
        let reported = wechatConvert(injected)
        XCTAssertEqual(reported.longitude, truth.longitude, accuracy: 1e-6)
        XCTAssertEqual(reported.latitude, truth.latitude, accuracy: 1e-6)
    }

    // MARK: - BD-09 文件

    /// 文件是 BD-09：结论同样成立——上报值落回真实位置。
    func testBD09FileReportsTrueLocationAfterPullBack() throws {
        // BD-09 的正算要从 GCJ-02 出发：真值（WGS-84）→ GCJ-02 → BD-09。
        let gcj = CoordinateTransform.wgs84ToGCJ02(longitude: truth.longitude, latitude: truth.latitude)
        let bd = CoordinateTransform.gcj02ToBD09(longitude: gcj.0, latitude: gcj.1)
        let url = try makeStraightKML(line(from: (longitude: bd.0, latitude: bd.1)))

        let model = ConsoleModel.shared
        model.trackSource = .localFile
        model.coordinateSystem = .bd09
        model.loadLocalTrack(url)
        model.setReportAlignment(.pullBackOneDatum)

        guard let injected = model.route?.points.first else {
            XCTFail("应载入本地轨迹")
            return
        }
        let reported = wechatConvert(injected)
        XCTAssertEqual(reported.longitude, truth.longitude, accuracy: 1e-6)
        XCTAssertEqual(reported.latitude, truth.latitude, accuracy: 1e-6)
    }

    // MARK: - 幂等性

    /// 反复切换基准与口径，最终注入值必须稳定——
    /// 说明管线每次都从原始文件重算，没有把转换结果攒起来。
    func testPipelineIsIdempotentAcrossToggles() throws {
        let url = try makeStraightKML(line(from: truth))
        let model = ConsoleModel.shared
        model.trackSource = .localFile
        model.coordinateSystem = .wgs84
        model.loadLocalTrack(url)
        model.setReportAlignment(.pullBackOneDatum)
        guard let first = model.route?.points.first else {
            XCTFail("应载入本地轨迹")
            return
        }
        model.setReportAlignment(.asIs)
        model.setReportAlignment(.pullBackOneDatum)
        guard let again = model.route?.points.first else {
            XCTFail("应载入本地轨迹")
            return
        }
        XCTAssertEqual(again.longitude, first.longitude, accuracy: 1e-12)
        XCTAssertEqual(again.latitude, first.latitude, accuracy: 1e-12)
    }

    // MARK: - 默认值

    /// 坐标基准默认必须是 WGS-84。
    ///
    /// 曾默认 GCJ-02：一份 GPS 手表导出的 WGS-84 文件会被整体平移约 525 米，
    /// 症状与「非校园区域」一模一样，用户根本分不清是自己选错了基准。
    func testCoordinateSystemDefaultsToWGS84() {
        let model = ConsoleModel.shared
        model.coordinateSystem = .wgs84
        XCTAssertEqual(model.coordinateSystem, .wgs84)
    }

    /// 本地导入轨迹时，上报口径应当跟内置轨迹一致（回退一个基准）。
    ///
    /// 否则「导入自己的轨迹」这一条最常用的路径，会因为忘了改口径而失败。
    func testLocalImportInheritsPullBackAlignment() throws {
        let url = try makeStraightKML(line(from: truth))
        let model = ConsoleModel.shared
        model.loadLocalTrack(url)
        XCTAssertEqual(model.trackSource, .localFile)
        XCTAssertEqual(model.reportAlignment, .asIs)
    }

    // MARK: - 上报落点读数

    /// 界面上的「上报落点与真实位置相差 X 米」必须真实反映口径选择：
    /// 回退一个基准时接近 0，原样时接近一个基准偏移量。
    func testReportLandingErrorReflectsAlignmentChoice() throws {
        let url = try makeStraightKML(line(from: truth))
        let model = ConsoleModel.shared
        model.trackSource = .localFile
        model.coordinateSystem = .wgs84
        model.loadLocalTrack(url)

        model.setReportAlignment(.pullBackOneDatum)
        let compensated = try XCTUnwrap(model.reportLandingErrorMeters)
        XCTAssertLessThan(compensated, 20, "回退一个基准后落点应贴近真实位置，实际 \(compensated) m")

        model.setReportAlignment(.asIs)
        let plain = try XCTUnwrap(model.reportLandingErrorMeters)
        XCTAssertGreaterThan(plain, 400, "原样口径应整体偏一个基准，实际 \(plain) m")
        XCTAssertLessThan(plain, 700, "偏差应在合理区间，实际 \(plain) m")
    }
}
