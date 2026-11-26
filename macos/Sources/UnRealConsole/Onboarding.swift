import SwiftUI

struct StartupView: View {
    let model: ConsoleModel
    @AppStorage("onboardingSeen") private var onboardingSeen = false

    var body: some View {
        if onboardingSeen || model.pendingID != nil {
            ConsoleView(model: model)
        } else {
            OnboardingView(firstLaunch: true) { onboardingSeen = true }
                .frame(maxWidth: 660)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

struct OnboardingView: View {
    private struct Step {
        let title: String
        let detail: String
        let labels: [String]
        let icons: [String]
        var help = ""
    }

    private let steps = [
        Step(title: "连接 iPhone", detail: "用数据线连接 iPhone。解锁手机，轻点「信任」。",
             labels: ["Mac", "USB", "信任此电脑"], icons: ["laptopcomputer", "cable.connector", "iphone"],
             help: "第一次使用定位模拟时，若需要准备 iOS 开发者镜像，请让 Mac 联网。"),
        Step(title: "开启开发者模式", detail: "开启开发者模式，按手机提示重启并确认。",
             labels: ["设置", "隐私与安全性", "开发者模式"], icons: ["gear", "lock.shield", "hammer"],
             help: "看不到开关时，可在 Xcode 的设备窗口确认 iPhone 已配对。"),
        Step(title: "选择设备和轨迹", detail: "从 USB 列表选择 iPhone。使用内置轨迹，或导入 GPX、KML、KMZ。",
             labels: ["选择 iPhone", "选择轨迹", "预览轨迹"], icons: ["iphone", "doc", "point.topleft.down.to.point.bottomright.curvepath"],
             help: "如果设备列表为空，解锁手机、检查 USB 和信任状态，然后刷新设备列表。"),
        Step(title: "核对起点，开始回放", detail: "点「接管定位权限」，在 iPhone 上核对起点后点「开始回放」。回放时保持 USB 连接。",
             labels: ["接管定位权限", "开始回放", "沿轨迹移动"], icons: ["mappin.circle", "play.fill", "location.fill"]),
        Step(title: "恢复真实定位", detail: "回放结束会返回轨迹起点。用完后点「重置位置」，再到 iPhone 上确认。",
             labels: ["回到起点", "重置位置", "在手机上确认"], icons: ["arrow.uturn.backward", "location.slash", "iphone"],
             help: "USB 已断开时，请重新连接原来的 iPhone，再恢复定位。")
    ]

    let firstLaunch: Bool
    let finish: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var index = 0
    @State private var showHelp = false
    @State private var progress = 0.0

    var body: some View {
        let step = steps[index]
        VStack(alignment: .leading, spacing: 16) {
            Text("\(index + 1) / \(steps.count)")
                .monospacedDigit()
                .foregroundStyle(.secondary)
            Text(step.title).font(.largeTitle.bold()).accessibilityAddTraits(.isHeader)
            illustration(step)
            Text(step.detail).textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
            if !step.help.isEmpty {
                DisclosureGroup("遇到问题？", isExpanded: $showHelp) {
                    Text(step.help).textSelection(.enabled)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 6)
                }
            }
            Spacer(minLength: 8)
            HStack {
                Button(firstLaunch ? "跳过引导" : "关闭引导", action: finish)
                    .keyboardShortcut(.cancelAction)
                Spacer()
                Button("上一步") { changePage(index - 1) }
                    .keyboardShortcut(.leftArrow, modifiers: [])
                    .disabled(index == 0)
                Button(primaryTitle) {
                    if index + 1 == steps.count { finish() } else { changePage(index + 1) }
                }
                .buttonStyle(.borderedProminent)
                .keyboardShortcut(.defaultAction)

            }
        }
        .padding(28)
        .frame(minHeight: 410)
        .focusable()
        .onKeyPress(.rightArrow) {
            if index + 1 == steps.count { finish() } else { changePage(index + 1) }
            return .handled
        }
        .task(id: index) {
            progress = reduceMotion ? 1 : 0
            if !reduceMotion {
                await Task.yield()
                guard !Task.isCancelled else { return }
                withAnimation(.easeInOut(duration: 1.85)) { progress = 1 }
            }
        }
    }

    private var primaryTitle: String {
        if index + 1 < steps.count { return "下一步" }
        return firstLaunch ? "进入控制台" : "完成"
    }

    private func changePage(_ page: Int) {
        showHelp = false
        index = page
    }

    private func illustration(_ step: Step) -> some View {
        VStack(spacing: 16) {
            HStack(spacing: 10) {
                ForEach(step.labels.indices, id: \.self) { position in
                    VStack(spacing: 8) {
                        Image(systemName: step.icons[position]).font(.title2)
                        Text(step.labels[position]).font(.callout.weight(.medium))
                    }
                    .frame(maxWidth: .infinity, minHeight: 70)
                    .background(.quaternary, in: RoundedRectangle(cornerRadius: 10))
                }
            }
            GuidePath(progress: progress, curved: index >= 2, returning: index == 4)
                .frame(height: 30)
        }
        .padding(16)
        .background(Color.accentColor.opacity(0.08), in: RoundedRectangle(cornerRadius: 16))
        .accessibilityHidden(true)
    }
}

private struct GuidePath: View, Animatable {
    var progress: Double
    let curved: Bool
    let returning: Bool

    var animatableData: Double {
        get { progress }
        set { progress = newValue }
    }

    var body: some View {
        Canvas { context, size in
            let position = returning ? max(0, 1 - 2 * progress) : progress
            func point(_ t: Double) -> CGPoint {
                CGPoint(x: 5 + max(0, size.width - 10) * t,
                        y: size.height / 2 + (curved ? sin(t * 2 * .pi) * 8 : 0))
            }

            var route = Path()
            route.move(to: point(0))
            for index in 1...60 { route.addLine(to: point(Double(index) / 60)) }
            context.stroke(route, with: .color(.accentColor.opacity(0.25)), lineWidth: 3)
            let marker = point(position)
            context.fill(Path(ellipseIn: CGRect(x: marker.x - 4, y: marker.y - 4, width: 8, height: 8)),
                         with: .color(.accentColor))
        }
    }
}
