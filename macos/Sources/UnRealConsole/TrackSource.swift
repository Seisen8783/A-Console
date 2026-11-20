import Foundation

/// 轨迹来源。
enum TrackSource: String, CaseIterable, Identifiable {
    /// 内置默认轨迹（校园跑走廊），坐标基准固定为 WGS-84。
    case bundled
    /// 本地导入，坐标基准由用户选择。
    case localFile

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .bundled: return "默认路径"
        case .localFile: return "本地导入"
        }
    }

    /// 内置默认轨迹的资源名（打包进 App 的 Resources）。
    static let bundledFileName = "default-track"
}
