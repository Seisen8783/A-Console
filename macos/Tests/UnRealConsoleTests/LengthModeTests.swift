import XCTest
@testable import UnRealConsole

/// 长度计量方式的互斥切换：圈数 ↔ 公里数。
final class LengthModeTests: XCTestCase {
    private func loadedModel() -> ConsoleModel {
        let model = ConsoleModel.shared
        // 单例，测试之间要显式重置长度模式相关的状态。
        model.lengthMode = .laps
        model.targetKilometers = ""
        model.setLaps("1")
        model.loadBundledTrack()
        return model
    }

    func testDefaultModeIsLaps() {
        XCTAssertEqual(ConsoleModel.shared.lengthMode, .laps)
    }

    func testModesAreMutuallyExclusive() {
        let model = loadedModel()
        model.setLengthMode(.kilometers)
        XCTAssertEqual(model.lengthMode, .kilometers)
        XCTAssertTrue(model.usesKilometers)

        model.setLengthMode(.laps)
        XCTAssertEqual(model.lengthMode, .laps)
        XCTAssertFalse(model.usesKilometers)
    }

    func testSwitchingToKilometersPrefillsCurrentTotalLength() {
        let model = loadedModel()
        model.setLaps("3")
        model.setLengthMode(.kilometers)

        // 预填值应等于「3 圈」的总长度换算成公里，保留两位小数。
        // 公里值本身经过两次取整（格式化到 2 位小数，再换算回米），允许几米误差。
        let expectedKilometers = model.lapMeters * 3 / 1000
        XCTAssertEqual(model.targetKilometers, String(format: "%.2f", expectedKilometers))
        XCTAssertEqual(model.targetMeters ?? 0, (expectedKilometers * 1000).rounded(), accuracy: 6)
        // 3 圈应明显长于 1 圈。
        XCTAssertGreaterThan(model.targetMeters ?? 0, model.lapMeters * 2.9)
    }

    func testTargetMetersOnlyAppliesInKilometersMode() {
        let model = loadedModel()
        model.setLengthMode(.kilometers)
        model.setTargetKilometers("1.50")
        XCTAssertEqual(model.targetMeters, 1500)

        // 切回圈数模式后，目标值不再生效。
        model.setLengthMode(.laps)
        XCTAssertNil(model.targetMeters)
    }

    func testInvalidKilometersOnlyFlaggedInKilometersMode() {
        let model = loadedModel()
        model.setTargetKilometers("abc")
        // 圈数模式下不提示，公里数模式下才提示。
        XCTAssertFalse(model.invalidKilometers)

        model.setLengthMode(.kilometers)
        XCTAssertTrue(model.invalidKilometers)
    }

    func testKilometersModeKeepsKilometersTextWhenSwitchingBack() {
        let model = loadedModel()
        model.setLengthMode(.kilometers)
        model.setTargetKilometers("2.00")
        model.setLengthMode(.laps)
        // 切回来时不应被预填覆盖。
        model.setLengthMode(.kilometers)
        XCTAssertEqual(model.targetKilometers, "2.00")
    }

    func testTotalMetersFollowsActiveMode() {
        let model = loadedModel()
        model.setLengthMode(.kilometers)
        model.setTargetKilometers("1.50")
        XCTAssertEqual(model.totalMeters, 1500, accuracy: 0.5)

        model.setLengthMode(.laps)
        XCTAssertEqual(model.totalMeters, model.lapMeters, accuracy: 0.5)
    }

    func testLengthModeDisplayNames() {
        XCTAssertEqual(LengthMode.laps.displayName, "总圈数")
        XCTAssertEqual(LengthMode.kilometers.displayName, "公里数")
    }
}
