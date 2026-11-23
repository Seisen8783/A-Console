import Foundation

/// 应用版本号。
///
/// 版本号存在仓库里的 `VERSION` 文件，构建脚本每次构建前 +0.0.01 并写回。
/// 这样版本号与代码同源，不会出现「打包说 2.0.13、实际是 2.0.9」这类错位。
enum AppVersion {
    /// 版本文件在包内的位置。
    static let fileName = "VERSION"

    /// 当前版本号。
    static var current: Version {
        let text = load() ?? Version(major: 2, minor: 0, patch: 13)
        return text
    }

    /// 版本号的显示形式：V2.0.13
    static func display(_ version: Version) -> String {
        version.display
    }

    /// 安装包以 Info.plist 为准，开发构建再回退到版本资源。
    private static func load() -> Version? {
        if let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
           let parsed = Version(text: version) { return parsed }
        if let url = Bundle.module.url(forResource: fileName, withExtension: nil),
           let text = try? String(contentsOf: url, encoding: .utf8) {
            return Version(text: text)
        }
        // 开发期：包资源可能还没生成，回退到仓库根的 VERSION 文件。
        var directory = URL(fileURLWithPath: Bundle.module.bundlePath)
        for _ in 0..<5 {
            let candidate = directory.appendingPathComponent(fileName)
            if let text = try? String(contentsOf: candidate, encoding: .utf8) {
                return Version(text: text)
            }
            directory = directory.deletingLastPathComponent()
        }
        return nil
    }
}

extension AppVersion {
    struct Version: Comparable, CustomStringConvertible {
        var major: Int
        var minor: Int
        var patch: Int

        init(major: Int, minor: Int, patch: Int) {
            self.major = major
            self.minor = minor
            self.patch = patch
        }

        /// 解析 "2.0.13" 或 "v2.0.13"。
        init?(text: String) {
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
                .trimmingCharacters(in: CharacterSet(charactersIn: "vV"))
            let parts = trimmed.split(separator: ".").map(String.init)
            guard parts.count == 3,
                  let major = Int(parts[0]), let minor = Int(parts[1]), let patch = Int(parts[2]) else {
                return nil
            }
            self.init(major: major, minor: minor, patch: patch)
        }

        var display: String { "V\(major).\(minor).\(patch)" }
        var description: String { display }

        static func < (lhs: Version, rhs: Version) -> Bool {
            (lhs.major, lhs.minor, lhs.patch) < (rhs.major, rhs.minor, rhs.patch)
        }
    }
}
