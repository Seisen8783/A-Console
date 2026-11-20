# NoReal macOS 控制台

通过 USB 连接自己的测试 iPhone，在 Mac 上预览和回放 GPX、KMZ 或 KML。控制台可以定位到起点、拖动时间条跳转、从起点重播，并在暂停时保持当前位置。

## 下载与安装

1. 从 [macOS 发布页](https://github.com/Seisen8783/NoReal/releases/tag/macos-v0.2.0) 下载 `NoReal-Console-0.2.0-macos-arm64.zip`。安装包面向 Apple 芯片 Mac，最低系统版本为 macOS 14。
2. 解压后把 `NoReal Console.app` 拖入「应用程序」。安装包使用临时签名，尚未经过 Apple 公证。若 macOS 显示验证提示，可查看 [Apple 的打开方式](https://support.apple.com/zh-cn/102445)，在确认下载来源后决定是否打开。
3. [安装 uv](https://docs.astral.sh/uv/getting-started/installation/)。如果已有 Homebrew，可以运行 `brew install uv`。控制台首次启动设备桥接时会用 `uvx` 准备 Python 3.14 和 `pymobiledevice3 11.61.0`，初次下载需要联网。

## 回放轨迹

1. 用 USB 数据线连接 iPhone，解锁并信任 Mac。iPhone 需要运行 iOS 17 或更新版本并开启[开发者模式](https://developer.apple.com/documentation/xcode/enabling-developer-mode-on-a-device)。如果设置里没有该开关，可先在 Xcode 的设备窗口确认配对，再回到手机检查。
2. 在控制台刷新设备列表并选中 iPhone。点击「选择轨迹」或把文件拖进预览区。 [20 秒测试轨迹](Tests/Fixtures/sample.gpx)
3. 点击「定位到起点」可先在手机上核对位置，再点「开始回放」。播放中拖动时间条可跳到最接近的采样点。
4. 点击「暂停」会保持当前位置。点「继续回放」从暂停点继续。点「恢复定位」可结束模拟，播完也会发送恢复请求。若拔线后出现「待恢复」，重新连接同一台设备，再点「恢复定位」。

## 配速和圈数

- 带完整时间戳的 GPX 或 KML `gx:Track` 按记录时间回放。只有坐标的 KML 或 KMZ 按输入配速生成时间，默认 **7:29 分/公里**。
- 从多圈轨迹识别出闭合路线后，可以启用「使用单圈中线」并填写总圈数。右上角显示当前圈和总圈数。
- 暂停时可调整无原始时间戳轨迹的配速和单圈中线的总圈数。续播时应用修改，已回放进度与暂停位置保留。总圈数至少要等于当前圈。

iOS 的定位模拟用于开发测试。其他 App 是否显示模拟坐标、如何计算里程或配速，取决于各自的处理逻辑。

## 从源码构建

在仓库根目录运行以下命令。也可以用 Xcode 打开 [Package.swift](Package.swift)。

```bash
./macos/build-app.sh
swift test --package-path macos
uvx --from 'pymobiledevice3==11.61.0' --python 3.14 python -m unittest discover -s macos/Tests/Python -v
```

构建产物位于 `macos/build/NoReal Console.app`。
