import XCTest
@testable import UnRealConsole

/// 版本号机制：格式解析、显示、递增。
final class AppVersionTests: XCTestCase {
    func testParsesPlainVersion() {
        let version = AppVersion.Version(text: "2.0.13")
        XCTAssertNotNil(version)
        XCTAssertEqual(version?.major, 2)
        XCTAssertEqual(version?.minor, 0)
        XCTAssertEqual(version?.patch, 13)
        XCTAssertEqual(version?.display, "V2.0.13")
    }

    func testParsesVersionWithPrefixAndWhitespace() {
        let version = AppVersion.Version(text: "  v2.0.13\n")
        XCTAssertNotNil(version)
        XCTAssertEqual(version?.display, "V2.0.13")
    }

    func testRejectsMalformedVersion() {
        XCTAssertNil(AppVersion.Version(text: "2.0"))
        XCTAssertNil(AppVersion.Version(text: "2.0.13.1"))
        XCTAssertNil(AppVersion.Version(text: "abc"))
        XCTAssertNil(AppVersion.Version(text: ""))
    }

    func testVersionOrdering() {
        let a = AppVersion.Version(text: "2.0.13")!
        let b = AppVersion.Version(text: "2.0.14")!
        let c = AppVersion.Version(text: "2.1.0")!
        let d = AppVersion.Version(text: "3.0.0")!
        XCTAssertLessThan(a, b)
        XCTAssertLessThan(b, c)
        XCTAssertLessThan(c, d)
    }

    func testCurrentVersionIsAvailable() {
        // 界面要显示版本号，运行时必须读得到。
        XCTAssertEqual(AppVersion.current.display.hasPrefix("V"), true)
        XCTAssertGreaterThan(AppVersion.current.major, 0)
    }
}
