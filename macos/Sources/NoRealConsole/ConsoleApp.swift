import AppKit
import SwiftUI
import UniformTypeIdentifiers

@main
struct ConsoleApp: App {
    @NSApplicationDelegateAdaptor(ConsoleDelegate.self) private var delegate

    var body: some Scene {
        Window("NoReal 控制台", id: "console") {
            ConsoleView(model: .shared)
                .frame(minWidth: 780, minHeight: 510)
        }
        .defaultSize(width: 960, height: 590)
    }
}

final class ConsoleDelegate: NSObject, NSApplicationDelegate {
    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        if ConsoleModel.shared.prepareToQuit({ sender.reply(toApplicationShouldTerminate: true) }) {
            return .terminateLater
        }
        return .terminateNow
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }
}

private struct ConsoleView: View {
    @ObservedObject var model: ConsoleModel
    @State private var importing = false
    @State private var dragTarget = false
    @State private var scrubTime: Double?
    private let trackTypes = ["gpx", "kmz", "kml"].compactMap { UTType(filenameExtension: $0) } + [.xml]

    var body: some View {
        HStack(spacing: 0) {
            sidebar
            Divider()
            workspace
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: trackTypes) { result in
            if case let .success(url) = result { open(url) }
        }
        .task { model.refresh() }
    }

    private var sidebar: some View {
        VStack(alignment: .leading, spacing: 22) {
            Label("NoReal", systemImage: "location.circle.fill")
                .font(.system(size: 27, weight: .semibold, design: .rounded))
                .foregroundStyle(Color.accentColor)

            VStack(alignment: .leading, spacing: 9) {
                HStack {
                    Text("USB 设备").font(.headline)
                    Spacer()
                    Button { model.refresh() } label: { Image(systemName: "arrow.clockwise") }
                        .help("刷新设备列表")
                        .accessibilityLabel("刷新设备列表")
                        .disabled(model.busy)
                }
                if model.devices.isEmpty {
                    Text("用数据线连接 iPhone，然后解锁并信任此 Mac。")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                } else {
                    Picker("iPhone", selection: $model.deviceID) {
                        ForEach(model.devices) { device in
                            Text(device.name + " · " + String(device.id.suffix(5))).tag(device.id)
                        }
                    }
                    .labelsHidden()
                    .disabled(model.busy)
                    if let device = model.devices.first(where: { $0.id == model.deviceID }) {
                        Text([device.version.isEmpty ? nil : "iOS " + device.version, device.detail]
                            .compactMap { $0 }.joined(separator: " · "))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            VStack(alignment: .leading, spacing: 9) {
                Text("轨迹文件").font(.headline)
                Button { importing = true } label: { Label("选择轨迹", systemImage: "folder") }
                    .disabled(model.busy)
                if let route = model.route {
                    Text(route.name)
                        .font(.callout)
                        .lineLimit(2)
                        .help(route.name)
                }
                if model.route?.untimedSegments != nil {
                    HStack {
                        Text("回放配速")
                        TextField("7:29", text: Binding(get: { model.paceInput }, set: { model.setPace($0) }))
                            .textFieldStyle(.roundedBorder)
                            .monospacedDigit()
                            .frame(width: 60)
                            .accessibilityLabel("回放配速，分秒")
                            .disabled(!model.canEditSettings)
                        Text("分/公里").foregroundStyle(.secondary)
                    }
                    if model.invalidPace {
                        Text("请输入分:秒，例如 7:29。")
                            .font(.caption)
                            .foregroundStyle(.orange)
                    }
                }
                if let loop = model.loop {
                    Toggle("使用单圈中线", isOn: Binding(get: { model.useCenterline }, set: { model.setCenterline($0) }))
                        .disabled(model.busy)
                    if model.useCenterline {
                        Text("由 \(loop.sourceLaps) 圈拟合 · 每圈约 \(Int(loop.meters.rounded())) 米")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        HStack {
                            Text("总圈数")
                            TextField("1", text: Binding(get: { model.lapsInput }, set: { model.setLaps($0) }))
                                .textFieldStyle(.roundedBorder)
                                .frame(width: 55)
                                .accessibilityLabel("总圈数")
                                .disabled(!model.canEditSettings)
                        }
                        if model.lapBelowCurrent {
                            Text("总圈数至少为当前第 \(model.currentLap) 圈。")
                                .font(.caption)
                                .foregroundStyle(.orange)
                        } else if model.lapCount == nil || model.lapLimitExceeded {
                            Text(model.lapLimitExceeded ? "轨迹过长，请减少圈数。" : "请输入 1 至 99 圈。")
                                .font(.caption)
                                .foregroundStyle(.orange)
                        }
                    }
                }
                if model.paused && model.settingsChanged && !model.invalidPace &&
                    model.lapCount != nil && !model.lapBelowCurrent && !model.lapLimitExceeded {
                    Text("继续回放时应用新设置")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if let issue = model.importError ?? model.route?.timingIssue {
                    Text(issue).font(.caption).foregroundStyle(.orange)
                }
            }

            Spacer()
            if let serial = model.pendingID, !model.busy {
                Label("设备 …\(serial.suffix(5)) 待恢复", systemImage: "exclamationmark.triangle")
                    .font(.callout)
                    .foregroundStyle(.orange)
            }
            Button { model.locateStart() } label: { Label("定位到起点", systemImage: "mappin.circle") }
                .disabled(!model.canAnchor)
            Button { model.restore() } label: { Label("恢复定位", systemImage: "location.slash") }
                .disabled(!model.canRestore)
        }
        .padding(22)
        .frame(width: 255)
        .frame(maxHeight: .infinity, alignment: .topLeading)
        .background(.regularMaterial)
    }

    private var workspace: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .firstTextBaseline) {
                Text("轨迹回放").font(.title2.weight(.semibold))
                Spacer()
                if let route = model.route {
                    if model.showsLap {
                        Text("第 \(model.currentLap) / \(model.timelineLaps) 圈")
                            .fontWeight(.semibold)
                    }
                    Text("\(model.timeline.count) 点")
                    if route.timingIssue == nil { Text(duration(model.totalDuration)) }
                }
            }
            .monospacedDigit()

            ZStack {
                RoundedRectangle(cornerRadius: 12).fill(Color(nsColor: .controlBackgroundColor))
                if let route = model.route {
                    RouteCanvas(route: route, activeIndex: model.previewIndex)
                        .accessibilityLabel("轨迹折线预览")
                } else {
                    VStack(spacing: 10) {
                        Image(systemName: "point.topleft.down.to.point.bottomright.curvepath")
                            .font(.system(size: 34))
                            .foregroundStyle(.secondary)
                        Text("将 GPX、KMZ 或 KML 拖到这里").font(.headline)
                        Button("选择文件") { importing = true }.disabled(model.busy)
                    }
                }
            }
            .overlay {
                RoundedRectangle(cornerRadius: 12)
                    .strokeBorder(dragTarget ? Color.accentColor : Color(nsColor: .separatorColor))
                    .allowsHitTesting(false)
            }
            .dropDestination(for: URL.self) { urls, _ in
                guard !model.busy, let url = urls.first, url.isFileURL else { return false }
                open(url)
                return true
            } isTargeted: { dragTarget = $0 && !model.busy }

            VStack(alignment: .leading, spacing: 7) {
                if !model.timeline.isEmpty {
                    let completed = model.pointIndex.map { $0 + 1 } ?? 0
                    Slider(value: Binding(get: { scrubTime ?? model.elapsed }, set: { value in
                        if scrubTime != nil { scrubTime = value }
                        else { model.seek(to: value) }
                    }), in: 0...max(0.001, model.totalDuration), onEditingChanged: { editing in
                        if editing {
                            scrubTime = model.elapsed
                        } else if let selected = scrubTime {
                            model.seek(to: selected)
                            scrubTime = nil
                        }
                    })
                    .disabled(!model.canSeek)
                    .accessibilityLabel("回放时间")
                    HStack {
                        Text("\(duration(scrubTime ?? model.elapsed)) / \(duration(model.totalDuration))")
                        Spacer()
                        if let index = model.pointIndex, model.timeline.indices.contains(index) {
                            let point = model.timeline[index]
                            Text(String(format: "%.6f, %.6f", point.latitude, point.longitude))
                        }
                        Text("\(completed) / \(model.timeline.count)")
                    }
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                }
                HStack {
                    if model.busy && model.pointIndex == nil {
                        ProgressView().controlSize(.small)
                    }
                    Text(model.status).font(.callout)
                    Spacer()
                    if model.playing && !model.paused {
                        Button("暂停") { model.pause() }.disabled(model.stopping)
                    }
                    Button("从起点重播") { model.restart() }
                        .disabled(!model.canRestart)
                    Button { model.start() } label: { Label(model.paused && model.hasStarted ? "继续回放" : "开始回放", systemImage: "play.fill") }
                        .buttonStyle(.borderedProminent)
                        .disabled(!model.canPlay)
                }
                if let error = model.error {
                    Text(error)
                        .font(.caption)
                        .foregroundStyle(.red)
                        .textSelection(.enabled)
                }
            }
        }
        .padding(24)
    }

    private func open(_ url: URL) {
        let access = url.startAccessingSecurityScopedResource()
        defer { if access { url.stopAccessingSecurityScopedResource() } }
        model.load(url)
    }

    private func duration(_ seconds: Double) -> String {
        let total = Int(max(0, seconds))
        return total >= 3600
            ? String(format: "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
            : String(format: "%02d:%02d", total / 60, total % 60)
    }
}

private struct RouteCanvas: View {
    let route: TrackRoute
    let activeIndex: Int?

    var body: some View {
        Canvas { context, size in
            let width = max(1, size.width - 60)
            let height = max(1, size.height - 60)
            let latitudes = route.points.map(\.latitude)
            let correction = max(0.01, cos((latitudes.min()! + latitudes.max()!) * .pi / 360))
            let firstLongitude = route.points[0].longitude
            let xs = route.points.map {
                (($0.longitude - firstLongitude + 540).truncatingRemainder(dividingBy: 360) - 180) * correction
            }
            let minX = xs.min()!, maxX = xs.max()!
            let minY = latitudes.min()!, maxY = latitudes.max()!
            let scale = min(width / max(0.000001, maxX - minX), height / max(0.000001, maxY - minY))
            let points = xs.indices.map { index in
                CGPoint(x: size.width / 2 + (xs[index] - (minX + maxX) / 2) * scale,
                        y: size.height / 2 - (latitudes[index] - (minY + maxY) / 2) * scale)
            }

            var grid = Path()
            for x in stride(from: 20.0, to: size.width, by: 24) {
                for y in stride(from: 20.0, to: size.height, by: 24) {
                    grid.addEllipse(in: CGRect(x: x, y: y, width: 2, height: 2))
                }
            }
            context.fill(grid, with: .color(.secondary.opacity(0.18)))

            var track = Path()
            var completed = Path()
            for index in points.indices {
                if route.segmentStarts.contains(index) {
                    track.move(to: points[index])
                    if index <= (activeIndex ?? -1) { completed.move(to: points[index]) }
                } else {
                    track.addLine(to: points[index])
                    if index <= (activeIndex ?? -1) { completed.addLine(to: points[index]) }
                }
            }
            let stroke = StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round)
            context.stroke(track, with: .color(.accentColor.opacity(0.38)), style: stroke)
            context.stroke(completed, with: .color(.accentColor), style: stroke)

            let markers: [(CGPoint, Color)] = [(points[0], .green), (points[points.count - 1], .orange)]
            for (point, color) in markers {
                context.fill(Path(ellipseIn: CGRect(x: point.x - 4, y: point.y - 4, width: 8, height: 8)),
                             with: .color(color))
            }
            if let activeIndex, points.indices.contains(activeIndex) {
                let point = points[activeIndex]
                let ring = Path(ellipseIn: CGRect(x: point.x - 7, y: point.y - 7, width: 14, height: 14))
                context.fill(ring, with: .color(.accentColor))
                context.stroke(ring, with: .color(.white), lineWidth: 2)
            }
        }
    }
}
