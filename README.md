# NoReal

定位开发测试工具。把一条轨迹按配速展开成连续的坐标序列，注入设备的系统定位通道，让设备沿这条轨迹移动。

当前主线是 **macOS 控制台 → iPhone**：Mac 通过 USB 连上测试机，走开发者服务里的 LocationSimulation 通道注入坐标，整机的 CoreLocation 输出随之被替换。手机上不用装任何 App，不用越狱，也不用开发者账号。

| 平台 | 状态 | 入口 |
| --- | --- | --- |
| macOS 14+（Apple 芯片）+ iOS 17+ | 当前主线 | [控制台使用说明](macos/README.md) · [下载 0.2.0](https://github.com/Seisen8783/NoReal/releases/tag/macos-v0.2.0) |
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

轨迹支持 GPX、KMZ、KML。带时间戳的轨迹按记录时间回放，只有坐标的按输入配速生成时间。控制台可以定位到起点、拖动时间条跳转、从起点重播，暂停时保持当前位置。

原理、路线对比与限制的完整推导见 [`docs/iOS-定位模拟方案.md`](docs/iOS-定位模拟方案.md)。

## 快速开始

1. 下载 `NoReal-Console-0.2.0-macos-arm64.zip`，把 `NoReal Console.app` 拖进「应用程序」；装好 [uv](https://docs.astral.sh/uv/getting-started/installation/)（首次启动设备桥接会用 `uvx` 拉起 Python 3.14 与 pymobiledevice3，需要联网）。
2. 用数据线连 iPhone，解锁并信任 Mac，手机开启[开发者模式](https://developer.apple.com/documentation/xcode/enabling-developer-mode-on-a-device)。
3. 控制台里刷新设备列表、选中 iPhone，导入轨迹，点「定位到起点」核对位置，再点「开始回放」。
4. 结束点「恢复定位」，否则手机会停在最后一个坐标上。

完整步骤见 [`macos/README.md`](macos/README.md)。

## 仓库结构

```
macos/    Swift 控制台 + Python 设备桥接 + Swift/Python 测试
core/     平台无关的轨迹引擎（Kotlin/JVM）、轨迹样本与校园路网数据
docs/     iOS 注入原理、macOS 控制台方案
```

## 构建与测试

```bash
./macos/build-app.sh                                  # 构建 NoReal Console.app
swift test --package-path macos                       # Swift 单元测试
uvx --from 'pymobiledevice3==11.61.0' --python 3.14 python -m unittest discover -s macos/Tests/Python -v
core/checks/run.sh                                    # 轨迹引擎回归检查（需要 JDK + kotlinc）
```

`core/checks/run.sh` 校验默认轨迹的 644 点长度与配速换算、反向规划、路网连通性、跨经线插值，并输出 327 个采样点。

## 轨迹引擎

`core/kotlin/com/noreal/core/` 是与平台无关的参考实现（纯 Kotlin，无 Android 依赖），macOS 端的 `Route.swift` / `KMLRoute.swift` / `LoopRoute.swift` 与它保持同一套采样语义：

- `Route.sample(elapsedSeconds, durationSeconds)`：按秒采样，多圈循环时首尾相接。
- `GpxWriter.build(route, options)`：把轨迹展开成合规 GPX——1 秒一点、时间戳单调递增、超过 20000 点自动降采样，供 iOS 开发者服务或 SDR 消费端回放。

`core/assets/` 存放共享样本：`default.csv` / `default.kml` / `default.kmz`（644 点走廊，Swift 测试直接引用 `default.kmz`）和 `campus.geojson`。

### 校园路网

`core/assets/campus.geojson` 是 WGS‑84 的 GeoJSON FeatureCollection，每条 LineString 代表一条双向步行边，需要连通的路口必须作为各相关道路的端点，并共享完全相同的坐标。更新数据包时应同时核对端点、拓扑和现场通行情况。

当前路网只有一条 644 点的走廊，多路口的连通与换乘只被合成图用例覆盖，接入真实网状数据前需要重新核对。

## 已知限制

- 模拟期间必须保持 USB 连接，拔线或异常退出会立即恢复真实定位。
- iOS 15+ 的 `CLLocation.sourceInformation.isSimulatedBySoftware` 是这条路上唯一的检测点，目标 App 是否检查它需要自行实测。
- 停止时必须发送恢复请求；没发出去就重启手机。
- 模拟位置可能违反某些应用的服务条款，只用于自测。

## 文档

- [`docs/iOS-定位模拟方案.md`](docs/iOS-定位模拟方案.md) — 免越狱、免开发者账号的注入原理，三条路线对比，风险清单与验证清单。
- [`docs/macOS-定位回放控制台方案.md`](docs/macOS-定位回放控制台方案.md) — 控制台界面与实现结构。

## 范围变更（2026-11-20）

仓库此前的主线是 Android 端：Kotlin App、Xposed 模块 `locbridge`、探针 `locprobe` 和配套的 adb 脚本。这些内容已从主分支移出，**代码仍完整保留在本地工作区**（见 `.gitignore` 中的忽略项），另有一份归档存在 `.backup/` 里。Android 版的历史构建仍可从 [Releases v1.0](https://github.com/Seisen8783/NoReal/releases/tag/v1.0) 下载。

历史提交与 tag 未做任何改写，需要时可用本地 tag `backup/pre-android-removal-20261120` 取回。

HarmonyOS 端此前已因平台限制移除（不向第三方开放定位注入，`ohos.permission.MOCK_LOCATION` 是系统权限），本次一并从工作区清出。

## 许可证

MIT，见 [LICENSE](LICENSE)。
