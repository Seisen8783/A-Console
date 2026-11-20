import XCTest
@testable import UnRealConsole

/// 上报口径：注入值 → 微信转 GCJ-02 → 上报值。
///
/// 校园跑小程序用 `wx.startLocationUpdateBackground`（未传 type，微信默认 gcj02）
/// 取定位，原样上报给服务端。服务端围栏若是 WGS-84，就差一个基准偏移量。
/// 「回退一个基准」预先反解一次，让上报值落回文件原值。
final class ReportAlignmentTests: XCTestCase {
    /// 微信会做的 WGS-84 → GCJ-02 转换。
    private func wechatConvert(longitude: Double, latitude: Double) -> (Double, Double) {
        CoordinateTransform.wgs84ToGCJ02(longitude: longitude, latitude: latitude)
    }

    func testAsIsKeepsInjectedValueUnchanged() {
        let model = ConsoleModel.shared
        model.loadBundledTrack()
        // 内置轨迹基准是 WGS-84，归一化是恒等变换，所以「原样」口径下
        // 注入值就是文件里的原始值。
        model.setReportAlignment(.asIs)
        guard let point = model.route?.points.first else {
            XCTFail("应载入内置默认轨迹")
            return
        }
        // 原始文件的第一点（WGS-84）。
        XCTAssertEqual(point.longitude, 117.9843303158298, accuracy: 1e-9)
        XCTAssertEqual(point.latitude, 37.388089191992755, accuracy: 1e-9)
    }

    func testPullBackMakesWechatConversionReturnOriginalValue() {
        let model = ConsoleModel.shared
        model.loadBundledTrack()

        // 先取「原样」口径下的注入值——它等于文件原值。
        model.setReportAlignment(.asIs)
        guard let original = model.route?.points.first else {
            XCTFail("应载入内置默认轨迹")
            return
        }
        let originalLongitude = original.longitude
        let originalLatitude = original.latitude

        // 切到「回退一个基准」。
        model.setReportAlignment(.pullBackOneDatum)
        guard let compensated = model.route?.points.first else {
            XCTFail("应载入内置默认轨迹")
            return
        }

        // 关键断言：补偿后，微信再转一次 GCJ-02，应当落回文件原值。
        let reported = wechatConvert(longitude: compensated.longitude, latitude: compensated.latitude)
        XCTAssertEqual(reported.0, originalLongitude, accuracy: 1e-6,
                       "上报经度应落回文件原值")
        XCTAssertEqual(reported.1, originalLatitude, accuracy: 1e-6,
                       "上报纬度应落回文件原值")

        // 且补偿本身确实移动了坐标（差约一个基准偏移量）。
        let moved = CoordinateTransform.wgs84ToGCJ02(longitude: originalLongitude,
                                                     latitude: originalLatitude)
        let east = (moved.0 - originalLongitude) * 111_320
            * cos(originalLatitude * .pi / 180)
        XCTAssertGreaterThan(abs(east), 300, "补偿应移动数百米")
    }

    func testBundledTrackDefaultsToPullBack() {
        let model = ConsoleModel.shared
        model.loadBundledTrack()
        XCTAssertEqual(model.reportAlignment, .pullBackOneDatum)
    }

    func testPullBackShiftsTrackWestwardByAbout475Meters() {
        let model = ConsoleModel.shared
        model.loadBundledTrack()
        model.setReportAlignment(.asIs)
        let plain = model.route?.points.first
        model.setReportAlignment(.pullBackOneDatum)
        let compensated = model.route?.points.first
        guard let plain, let compensated else {
            XCTFail("应载入内置默认轨迹")
            return
        }
        let east = (compensated.longitude - plain.longitude) * 111_320
            * cos(plain.latitude * .pi / 180)
        let north = (compensated.latitude - plain.latitude) * 111_320
        // GCJ-02 反解是往西南方向约 475 米。
        XCTAssertEqual(east, -466, accuracy: 30)
        XCTAssertEqual(north, -75, accuracy: 30)
    }

    func testReportAlignmentDisplayNames() {
        XCTAssertEqual(ReportAlignment.asIs.displayName, "原样")
        XCTAssertEqual(ReportAlignment.pullBackOneDatum.displayName, "回退一个基准")
    }
}
