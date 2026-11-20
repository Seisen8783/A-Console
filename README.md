# UnReal macOS 控制台

通过 USB 连接自己的测试 iPhone，在 Mac 上预览和回放 GPX、KMZ 或 KML。控制台可以定位到起点、拖动时间条跳转、从起点重播，并在暂停时保持当前位置。

定位轨迹按配速展开成连续的坐标序列，注入设备的系统定位通道，让设备沿这条轨迹移动。手机上不用装任何 App，不用越狱，也不用开发者账号。

| 平台 | 状态 | 入口 |
| --- | --- | --- |
| macOS 14+（Apple 芯片）+ iOS 17+ | 当前主线 | [下载 0.2.0](https://github.com/Seisen8783/UnReal/releases/tag/macos-v0.2.0) |
| Android / HarmonyOS | 已移出仓库 | 代码留在本地工作区与历史提交中，见[范围变更](#范围变更2026-11-20) |

本工具仅供开发测试使用。

## 工作原理

```
Mac 控制台（Swift）
  └─ device_bridge.py（pymobiledevice3）
       └─ USB / lockdown → RemoteXPC tunnel（iOS 17+）
            └─ DVT LocationSimulation
                 └─ CoreLocation（整机输出被替换）
```

原理、路线对比与限制的完整推导见 [`docs/iOS-定位模拟方案.md`](docs/iOS-定位模拟方案.md)。

## 下载与安装

1. 从 [macOS 发布页](https://github.com/Seisen8783/UnReal/releases/tag/macos-v0.2.0) 下载 `NoReal-Console-0.2.0-macos-arm64.zip`。安装包面向 Apple 芯片 Mac，最低系统版本为 macOS 14。
2. 解压后把 `NoReal Console.app` 拖入「应用程序」。该发布版早于改名，显示名仍是「NoReal 控制台」；从当前源码构建出来的 App 名为 `UnReal Console.app`。安装包使用临时签名，尚未经过 Apple 公证。若 macOS 显示验证提示，可查看 [Apple 的打开方式](https://support.apple.com/zh-cn/102445)，在确认下载来源后决定是否打开。
3. [安装 uv](https://docs.astral.sh/uv/getting-started/installation/)。如果已有 Homebrew，可以运行 `brew install uv`。控制台首次启动设备桥接时会用 `uvx` 准备 Python 3.14 和 `pymobiledevice3 11.61.0`，初次下载需要联网。

## 回放轨迹

1. 用 USB 数据线连接 iPhone，解锁并信任 Mac。iPhone 需要运行 iOS 17 或更新版本并开启[开发者模式](https://developer.apple.com/documentation/xcode/enabling-developer-mode-on-a-device)。如果设置里没有该开关，可先在 Xcode 的设备窗口确认配对，再回到手机检查。
2. 在控制台刷新设备列表并选中 iPhone。侧边栏「轨迹来源」默认是**默认路径**，会载入内置的校园跑走廊；选**本地导入**可以自己挑文件，也可以直接把文件拖进预览区。 [20 秒测试轨迹](macos/Tests/Fixtures/sample.gpx)
3. 点击「定位到起点」可先在手机上核对位置，再点「开始回放」。播放中拖动时间条可跳到最接近的采样点。
4. 点击「暂停」会保持当前位置。点「继续回放」从暂停点继续。**回放自然结束后设备位置会退回轨迹起点并保持**，要恢复真实定位点「恢复定位」。若拔线后出现「待恢复」，重新连接同一台设备，再点「恢复定位」。

## 配速和圈数

- 带完整时间戳的 GPX 或 KML `gx:Track` 按记录时间回放。只有坐标的 KML 或 KMZ 按输入配速生成时间，默认 **7:29 分/公里**。
- 从多圈轨迹识别出闭合路线后，可以启用「使用单圈中线」。
- **长度计量二选一**：界面上是左侧带勾选框的「总圈数」和「公里数」两行，勾选哪一行就用哪种方式，勾选框在左侧。
  - 切到「公里数」时，若输入框还是空的，会按当前总长度自动预填（如 `0.45`），免得面对空框。
  - 切换轨迹后，若公里数是自动预填的（没手动改过），会按新轨迹重算。
- 按公里数模拟时（精确到小数点后 2 位），轨迹不足目标长度会自动循环拼接，超出则按弧长精确截断。
- 右上角除圈数外还会显示米数进度 `111.2m/400.0m`，超过一千米自动切换成 `1.23km/1.50km`。

拼接不是简单复制点列表——真实轨迹常常不闭合（末点与首点差十几米），直接拼接会在圈与圈之间留下跳变。实现按累计弧长均匀重采样，每圈的起点严格落在轨迹的同一位置，圈与圈之间自然连续。

- 暂停时可调整无原始时间戳轨迹的配速、长度计量方式和单圈中线的总圈数。续播时应用修改，已回放进度与暂停位置保留。总圈数至少要等于当前圈。

## 坐标基准与标定偏移

设备只接受 WGS-84。导入的轨迹若来自腾讯/高德/百度地图，基准就不是 WGS-84，需要在「坐标基准」里选对，否则整条轨迹会平移数百米。

| 选项 | 什么时候选它 |
| --- | --- |
| WGS-84（GPS / OSM 导出） | 轨迹来自 GPS 记录、OpenStreetMap、Garmin 等 |
| GCJ-02（腾讯 / 高德地图） | 在腾讯/高德地图上描点，或从这两家导出的数据 |
| BD-09（百度地图） | 百度地图导出 |

选错的代价是**整条轨迹平移一个基准偏移量**——国内通常数百米，滨州一带约 475 米。轨迹在地图上看着还在操场，实际已落在目标地点几百米之外。

**内置默认轨迹的基准固定为 WGS-84**（它与 `core/assets/campus.geojson` 逐点一致，后者已声明为 WGS-84），不随这个选择改变；选本地导入后才能改基准。

「标定偏移」是另一件事：基准相同但原点不同（服务端围栏按某个参考点标注，轨迹照着另一份地图描的）时，用它把整条轨迹平移到目标位置，单位是米（东/北）。

归一化与偏移的顺序不能反：基准转换是非线性变换，必须先做；标定偏移是米制平移，必须在经纬度空间做。单圈中线也在归一化之后才拟合，否则会与点集错开一个基准。

判断基准与计算偏移：

```bash
python3 tools/coordinate_probe.py 轨迹.kmz --probe 37.387769,117.982438   # 用已知地物判读
python3 tools/coordinate_probe.py 轨迹.kmz --target 37.387769,117.982438  # 算所需偏移
```

iOS 的定位模拟用于开发测试。其他 App 是否显示模拟坐标、如何计算里程或配速，取决于各自的处理逻辑。

## 从源码构建

在仓库根目录运行以下命令。也可以用 Xcode 打开 [macos/Package.swift](macos/Package.swift)。

```bash
./macos/build-app.sh
swift test --package-path macos --disable-sandbox
uvx --from 'pymobiledevice3==11.61.0' --python 3.14 python -m unittest discover -s macos/Tests/Python -v
core/checks/run.sh
```

受限终端里 SwiftPM 自带的沙箱会因 `sandbox_apply` 失败，需要加 `--disable-sandbox`；
`build-app.sh` 检测到会自动退回无沙箱模式重试。

构建产物位于 `macos/build/UnReal Console.app`。

## 仓库结构

```
macos/    Swift 控制台 + Python 设备桥接 + Swift/Python 测试
core/     平台无关的轨迹引擎（Kotlin/JVM）、轨迹样本与校园路网数据
tools/    坐标基准诊断脚本、图标生成
docs/     iOS 注入原理、macOS 控制台方案
```

`core/kotlin/com/unreal/core/` 是与平台无关的参考实现（纯 Kotlin，无 Android 依赖），macOS 端的 `Route.swift` / `KMLRoute.swift` / `LoopRoute.swift` 与它保持同一套采样语义：

- `Route.sample(elapsedSeconds, durationSeconds)`：按秒采样，多圈循环时首尾相接。
- `GpxWriter.build(route, options)`：把轨迹展开成合规 GPX——1 秒一点、时间戳单调递增、超过 20000 点自动降采样，供 iOS 开发者服务或 SDR 消费端回放。

`core/assets/campus.geojson` 是 WGS‑84 的 GeoJSON FeatureCollection，每条 LineString 代表一条双向步行边，需要连通的路口必须作为各相关道路的端点，并共享完全相同的坐标。当前路网只有一条 644 点的走廊，多路口的连通与换乘只被合成图用例覆盖，接入真实网状数据前需要重新核对。

`core/checks/run.sh` 校验默认轨迹的 644 点长度与配速换算、反向规划、路网连通性、跨经线插值，并输出 327 个采样点（需要 JDK + kotlinc）。

## 版本号

版本号存在仓库根的 `VERSION` 文件里。**每次构建自动 +0.0.01 并写回**，
构建出来的包用的是递增前的版本号，所以界面上显示的版本与安装包始终一致。

界面左上角产品名右侧会显示当前版本。要指定版本，改 `VERSION` 文件即可。

图标由 `tools/icon/make_icon.swift` 生成（白底 + 蓝色圆 + 深色导航箭头），构建时自动合成 `.icns` 打进包里。

## 已知限制

- 模拟期间必须保持 USB 连接，拔线或异常退出会立即恢复真实定位。
- iOS 15+ 的 `CLLocation.sourceInformation.isSimulatedBySoftware` 是这条路上唯一的检测点，目标 App 是否检查它需要自行实测。
- 停止时必须发送恢复请求；没发出去就重启手机。
- 模拟位置可能违反某些应用的服务条款，只用于自测。

## 文档

- [`docs/iOS-定位模拟方案.md`](docs/iOS-定位模拟方案.md) — 免越狱、免开发者账号的注入原理，三条路线对比，风险清单与验证清单。
- [`docs/macOS-定位回放控制台方案.md`](docs/macOS-定位回放控制台方案.md) — 控制台界面与实现结构。

## 范围变更（2026-11-20）

仓库此前的主线是 Android 端：Kotlin App、Xposed 模块 `locbridge`、探针 `locprobe` 和配套的 adb 脚本。这些内容已从主分支移出，**代码仍完整保留在本地工作区**（见 `.gitignore` 中的忽略项），另有一份归档存在 `.backup/` 里。Android 版的历史构建仍可从 [Releases v1.0](https://github.com/Seisen8783/UnReal/releases/tag/v1.0) 下载。

历史提交与 tag 未做任何改写，需要时可用本地 tag `backup/pre-android-removal-20261120` 取回。

HarmonyOS 端此前已因平台限制移除（不向第三方开放定位注入，`ohos.permission.MOCK_LOCATION` 是系统权限），本次一并从工作区清出。

## 许可证

MIT，见 [LICENSE](LICENSE)。
