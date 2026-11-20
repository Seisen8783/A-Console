import Foundation

enum ReplayPace {
    static func parse(_ text: String) -> Int? {
        let parts = text.trimmingCharacters(in: .whitespacesAndNewlines)
            .split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count == 2, (1...2).contains(parts[0].count), parts[1].count == 2,
              parts.allSatisfy({ $0.utf8.allSatisfy { (48...57).contains($0) } }),
              let minutes = Int(parts[0]), let seconds = Int(parts[1]), seconds < 60 else { return nil }
        let result = minutes * 60 + seconds
        return result > 0 ? result : nil
    }

    static func format(_ seconds: Int) -> String {
        String(format: "%d:%02d", seconds / 60, seconds % 60)
    }
}

struct TrackPoint: Encodable {
    let latitude: Double
    let longitude: Double
    let offset: Double
}

struct TrackRoute {
    let name: String
    let points: [TrackPoint]
    let segmentStarts: Set<Int>
    let timingIssue: String?
    var untimedSegments: [[TrackPoint]]? = nil
    var duration: Double { points.last?.offset ?? 0 }

    static func load(_ url: URL) throws -> TrackRoute {
        switch url.pathExtension.lowercased() {
        case "kmz", "kml": return try KMLRoute.load(url)
        default: return try loadGPX(url)
        }
    }

    private static func loadGPX(_ url: URL) throws -> TrackRoute {
        let file = try FileHandle(forReadingFrom: url)
        defer { try? file.close() }
        let limit = 20 * 1024 * 1024
        let data = try file.read(upToCount: limit + 1) ?? Data()
        guard data.count <= limit else { throw TrackError("GPX 文件超过 20 MB。") }
        let reader = TrackReader()
        let parser = XMLParser(data: data)
        parser.shouldProcessNamespaces = true
        parser.shouldResolveExternalEntities = false
        parser.delegate = reader
        let parsed = parser.parse()
        if let error = reader.error { throw error }
        guard parsed else { throw TrackError("无法读取 GPX XML。") }
        guard reader.points.count >= 2 else { throw TrackError("GPX 至少需要两个轨迹点。") }

        let first = reader.points[0].time
        var previous: Date?
        var timingIssue: String?
        let points = reader.points.map { point in
            if let time = point.time {
                if let previous, previous > time {
                    timingIssue = "时间戳倒序。请修正 GPX 后重新导入。"
                }
                previous = time
            } else {
                timingIssue = "有轨迹点缺少有效的时间戳。请修正 GPX 后重新导入。"
            }
            let offset = point.time.flatMap { time in first.map { time.timeIntervalSince($0) } } ?? 0
            return TrackPoint(latitude: point.latitude, longitude: point.longitude, offset: offset)
        }
        return TrackRoute(name: url.lastPathComponent, points: points, segmentStarts: reader.segmentStarts,
                          timingIssue: timingIssue)
    }
}

struct TrackError: LocalizedError {
    let errorDescription: String?
    init(_ message: String) { errorDescription = message }
}

private final class TrackReader: NSObject, XMLParserDelegate {
    private static let path = ["gpx", "trk", "trkseg", "trkpt"]
    private static let timePath = path + ["time"]
    private let fractional = ISO8601DateFormatter()
    private let seconds = ISO8601DateFormatter()
    private var elements: [String] = []
    private var text = ""
    private var coordinate: (Double, Double)?
    private var time: Date?
    private var segmentPending = false
    var points: [(latitude: Double, longitude: Double, time: Date?)] = []
    var segmentStarts: Set<Int> = []
    var error: TrackError?

    override init() {
        super.init()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    }

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
                qualifiedName: String?, attributes: [String: String]) {
        elements.append(elementName)
        if elements.count == 1 && elementName != "gpx" {
            error = TrackError("请选择 GPX 轨迹文件。")
            parser.abortParsing()
        }
        if elements == Array(Self.path.dropLast()) { segmentPending = true }
        if elements == Self.path {
            guard points.count < 100_000,
                  let latText = attributes["lat"], let lonText = attributes["lon"],
                  let lat = Double(latText), let lon = Double(lonText),
                  (-90...90).contains(lat), (-180...180).contains(lon) else {
                error = TrackError("轨迹点超过 10 万个，或文件含无效经纬度。")
                parser.abortParsing()
                return
            }
            coordinate = (lat, lon)
            time = nil
        }
        if elements == Self.timePath { text = "" }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        if elements == Self.timePath { text += string }
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?,
                qualifiedName: String?) {
        if elements == Self.timePath {
            let value = text.trimmingCharacters(in: .whitespacesAndNewlines)
            time = fractional.date(from: value) ?? seconds.date(from: value)
        }
        if elements == Self.path, let coordinate {
            if segmentPending {
                segmentStarts.insert(points.count)
                segmentPending = false
            }
            points.append((coordinate.0, coordinate.1, time))
        }
        elements.removeLast()
    }

    func parser(_ parser: XMLParser, foundInternalEntityDeclarationWithName name: String, value: String?) {
        error = TrackError("GPX 含不支持的 XML 实体声明。")
        parser.abortParsing()
    }

    func parser(_ parser: XMLParser, foundExternalEntityDeclarationWithName name: String, publicID: String?,
                systemID: String?) {
        error = TrackError("GPX 含不支持的 XML 实体声明。")
        parser.abortParsing()
    }
}

enum ReplayTimeline {
    static func lap(at index: Int, pointsPerLap: Int, totalLaps: Int) -> Int {
        guard pointsPerLap > 1 else { return 1 }
        return min(totalLaps, max(0, index) / (pointsPerLap - 1) + 1)
    }

    static func build(_ route: TrackRoute, laps: Int, scale: Double = 1,
                      keeping history: ArraySlice<TrackPoint> = []) -> [TrackPoint] {
        guard let first = route.points.first else { return [] }
        let steps = route.points.count - 1
        guard steps > 0 else { return [first] }
        let lastIndex = laps * steps
        let pausedIndex = history.count - 1
        guard pausedIndex <= lastIndex else { return [] }

        func sample(at index: Int) -> (point: TrackPoint, seconds: Double) {
            let lap = index == 0 ? 0 : (index - 1) / steps
            let point = route.points[index - lap * steps]
            return (point, Double(lap) * route.duration + point.offset)
        }

        let reference = max(0, pausedIndex)
        let referenceSeconds = sample(at: reference).seconds
        let elapsed = history.last?.offset ?? 0
        var timeline = Array(history)
        timeline.reserveCapacity(lastIndex + 1)
        for index in history.count..<(lastIndex + 1) {
            let next = sample(at: index)
            let offset = elapsed + (next.seconds - referenceSeconds) * scale
            timeline.append(TrackPoint(latitude: next.point.latitude, longitude: next.point.longitude, offset: offset))
        }
        return timeline
    }
}
