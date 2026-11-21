# UnReal macOS 控制台

通过 USB 连接自己的测试 iPhone，在 Mac 上预览和回放 GPX、KMZ 或 KML。控制台可以定位到起点、拖动时间条跳转、从起点重播，并在暂停时保持当前位置。

## 下载与安装

1. 从 [macOS 发布页](https://github.com/Seisen8783/UnReal/releases/tag/macos-v0.2.0) 下载 `NoReal-Console-0.2.0-macos-arm64.zip`。安装包面向 Apple 芯片 Mac，最低系统版本为 macOS 14。
2. 解压后把 `NoReal Console.app` 拖入「应用程序」。该发布版早于改名，显示名仍是「NoReal 控制台」；从当前源码构建出来的 App 名为 `UnReal Console.app`。安装包使用临时签名，尚未经过 Apple 公证。若 macOS 显示验证提示，可查看 [Apple 的打开方式](https://support.apple.com/zh-cn/102445)，在确认下载来源后决定是否打开。
3. [安装 uv](https://docs.astral.sh/uv/getting-started/installation/)。如果已有 Homebrew，可以运行 `brew install uv`。控制台首次启动设备桥接时会用 `uvx` 准备 Python 3.14 和 `pymobiledevice3 11.61.0`，初次下载需要联网。

## 回放轨迹

1. 用 USB 数据线连接 iPhone，解锁并信任 Mac。iPhone 需要运行 iOS 17 或更新版本并开启[开发者模式](https://developer.apple.com/documentation/xcode/enabling-developer-mode-on-a-device)。如果设置里没有该开关，可先在 Xcode 的设备窗口确认配对，再回到手机检查。
2. 在控制台刷新设备列表并选中 iPhone。侧边栏「轨迹来源」选**默认路径**（内置的校园跑走廊，启动时自动载入）或**本地导入**（唤起文件选择器）。也可以把文件直接拖进预览区。 [20 秒测试轨迹](Tests/Fixtures/sample.gpx)
3. 点击「定位到起点」可先在手机上核对位置，再点「开始回放」。播放中拖动时间条可跳到最接近的采样点。
4. 点击「暂停」会保持当前位置。点「继续回放」从暂停点继续。**回放自然结束后设备位置会退回轨迹起点并保持**，要恢复真实定位点「恢复定位」。若拔线后出现「待恢复」，重新连接同一台设备，再点「恢复定位」。

## 配速和圈数

- 带完整时间戳的 GPX 或 KML `gx:Track` 按记录时间回放。只有坐标的 KML 或 KMZ 按输入配速生成时间，默认 **7:29 分/公里**。
- 从多圈轨迹识别出闭合路线后，可以启用「使用单圈中线」并填写总圈数。右上角显示当前圈和总圈数。
- **长度计量二选一**：一个下拉框选「总圈数 / 公里数」，右侧填对应数值。
  - 切到「公里数」时，若输入框还是空的，会按当前总长度自动预填（如 `0.45`），免得面对空框。
  - 切换轨迹后，若公里数是自动预填的（没手动改过），会按新轨迹重算。
- 右上角除圈数外还会显示米数进度 `111.2m/400.0m`。
- 暂停时可调整无原始时间戳轨迹的配速和单圈中线的总圈数。续播时应用修改，已回放进度与暂停位置保留。总圈数至少要等于当前圈。

## 坐标基准与标定偏移

设备只接受 WGS-84。导入的轨迹若来自腾讯/高德/百度地图，基准就不是 WGS-84，需要在「坐标基准」里选对，否则整条轨迹会平移数百米。「标定偏移」用于基准相同但原点不同的情况，按米（东/北）平移。

**内置默认轨迹的基准固定为 WGS-84**（它与 `core/assets/campus.geojson` 逐点一致，后者已声明为 WGS-84），不随「坐标基准」的选择改变；只有本地导入的轨迹才按你选的基准解读。

归一化与偏移的顺序不能反：基准转换是非线性变换，必须先做；标定偏移是米制平移，必须在经纬度空间做。单圈中线也在归一化之后才拟合，否则会与点集错开一个基准。

**内置默认轨迹的基准固定为 WGS-84**，不随「坐标基准」选择改变——选本地导入后才能改基准。
该文件与 `core/assets/campus.geojson` 逐点一致，而后者在 README 中声明为 WGS-84；
若按 GCJ-02 解读会整体平移约 475 米。

### 上报口径

「校园跑」这类小程序用 `wx.startLocationUpdateBackground` 取定位（未指定 `type`，微信默认 **gcj02**），
把设备交付的 WGS-84 转成 GCJ-02 后**原样上报**给服务端做围栏判定。

服务端围栏若是 WGS-84（从 GPS/测绘数据导入的常见做法），两者就差一个基准偏移量——滨州一带约 475 米。
结果就是：明明在操场上跑，判定却是「非校园区域」，里程显示 `--km`、配速 `0'00"`（服务端未采信这段），
但时长有值（客户端自己记的）。

「上报口径」选**回退一个基准**，就是预先做一次 GCJ-02 反解，让微信转回来正好等于文件原值。
内置默认轨迹已按这个口径预置。

判断基准与计算偏移：

```bash
python3 tools/coordinate_probe.py 轨迹.kmz --probe 37.387769,117.982438   # 用已知地物判读
python3 tools/coordinate_probe.py 轨迹.kmz --target 37.387769,117.982438  # 算所需偏移
```

iOS 的定位模拟用于开发测试。其他 App 是否显示模拟坐标、如何计算里程或配速，取决于各自的处理逻辑。

## 从源码构建

在仓库根目录运行以下命令。也可以用 Xcode 打开 [Package.swift](Package.swift)。

```bash
./macos/build-app.sh
swift test --package-path macos --disable-sandbox
uvx --from 'pymobiledevice3==11.61.0' --python 3.14 python -m unittest discover -s macos/Tests/Python -v
```

受限终端里 SwiftPM 自带的沙箱会因 `sandbox_apply` 失败，需要加 `--disable-sandbox`；
`build-app.sh` 检测到会自动退回无沙箱模式重试。

构建产物位于 `macos/build/UnReal Console.app`。

## 版本号

版本号存在仓库根的 `VERSION` 文件里。**每次构建自动 +0.0.01 并写回**，
构建出来的包用的是递增前的版本号，所以界面上显示的版本与安装包始终一致。

界面左上角产品名右侧会显示当前版本。要指定版本，改 `VERSION` 文件即可。

图标由 `tools/icon/make_icon.swift` 生成（白底 + 蓝色圆 + 深色导航箭头），构建时自动合成 `.icns` 打进包里。
