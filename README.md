# NoReal

NoReal 是一个定位开发测试工具，支持轨迹导入、配速设置与模拟回放。

| 平台 | 用法 |
| --- | --- |
| Android 7.0+ | 在手机上录制、规划和回放轨迹。 [Android 安装包](https://github.com/Seisen8783/NoReal/releases/tag/v1.0) |
| macOS 14+（Apple 芯片） | 通过 USB 在测试 iPhone 上回放 GPX、KMZ 或 KML。 [Mac 控制台使用说明](macos/README.md) · [Mac 下载](https://github.com/Seisen8783/NoReal/releases/tag/macos-v0.2.0) |

下面是 Android 版的使用说明。

## 系统模拟

1. 首次使用会引导打开「开发者选项 → 选择模拟位置信息应用」，选中 NoReal。
2. 选择轨迹，设定配速（或总用时），再用「圈数」设定要跑几圈。
3. 点「开始系统模拟」，通知栏显示当前会话，点「停止」结束。

圈数让轨迹循环：单圈播完立刻接回起点继续，总时长 = 单圈时长 × 圈数，总距离 = 单圈长度 × 圈数。轨迹首尾落差超过 30 米时会提示循环处会出现跳变。「按用时设置」里填的是**总用时**，配速会按总距离换算。

运行时由前台服务驱动，每秒向 GPS、网络定位和 Google FLP 写入一次坐标，速度、方位角与时间戳随点变化。停止时会移除测试定位源并关闭 FLP 的 mock 模式；异常退出后会记录待清理状态，下次启动自动恢复，不会污染系统定位。

跨应用是否读到模拟值，取决于对方的定位源选择、权限和处理方式。

### 已知限制：微信生态读不到

微信（含小程序、位置共享）**不会采用**这里的模拟位置，且不会给出任何提示。

实测方法：把轨迹换成北京天安门环线，`dumpsys location` 确认 `gps provider [mock]` 给出的坐标在北京、速度持续变化，但微信位置共享的绿点始终停在滨州真实位置，25 秒不动；小程序打卡同样里程为 0、无异常提示。

原因是 Android 会给 `setTestProviderLocation` 写入的每个坐标强制打上 `isMock = true`，微信客户端（腾讯位置服务）检测到后静默丢弃。该标记没有公开 API 可以去除。

**不要在这种情况下怀疑是本工具失效**。判断方法：连续两次执行

```
adb shell dumpsys location | grep "last location=Location\[gps"
```

看 `et=` 时间戳是否在推进。在推进就说明工具工作正常，是目标应用自己拒绝了数据。

要突破这层限制只能靠 root + LSPosed hook `Location.isMock()`，属于本项目的范围之外。

### 绕过办法：接管模式 + LocBridge 模块

仓库里的 `locbridge` 模块就是干这个的，走的是**换注入路径**而不是**去标记**：

- NoReal 打开「接管模式」后，不再写系统定位源，改为每秒把一帧坐标广播到本机回环地址
  `127.0.0.1:38765`。
- LocBridge 通过 Xposed 注入微信的**全部进程**（主进程和 `:appbrand` 小程序逻辑层），
  把 `LocationManager` 的三条读取路径（`getLastKnownLocation` / `getCurrentLocation` /
  `requestLocationUpdates`）换掉，交给应用的是自己 `new Location(...)` 造出来的对象——
  它的 `isMock` 默认就是 `false`，腾讯位置服务拿它没办法。
- 模块另外还 hook 了 `Location.isMock()` 作为兜底，配合普通模式也能把系统打上的标记擦掉。

这套方案是手机本地运行的：坐标流只走回环地址，不出设备，也不需要外部网络。

详见 [`docs/NoReal-定位接管技术总览.md`](docs/NoReal-定位接管技术总览.md)，里面写了原理、协议、微信定位链路的五层分析、卡点、实测记录、落地路径与踩坑清单。

### 兼容层与鸿蒙

鸿蒙（包括卓易通这类安卓容器）的设置里没有「选择模拟位置信息应用」，那是 AOSP 的入口，纯血鸿蒙已经移除，不是没找到。

- 容器如果自带 Android 设置，需要在那里找同名开关。
- 也可以用 `adb shell appops set com.noreal.android android:mock_location allow` 直接授予。
- 即使授予成功，模拟只影响该容器内的安卓应用，不会改变鸿蒙原生应用读到的定位。

未授权时弹窗提供「仍然尝试」：NoReal 会跳过 app op 检查，由 `addTestProvider` 的真实结果决定成败。部分兼容层的 `checkOpNoThrow` 会误报，这个出口用于排除误判；真没权限时会被系统拒绝并提示「没有 mock location 权限」。

## 录制

使用系统定位采集真实轨迹，由前台服务承载，锁屏后继续。精度超过 25 m、被标记为 mock、时间戳回退的定位会被丢弃；相邻点间隔 15 秒以上或位移异常时自动断开为新的轨迹分段。采样点先追加写入 NDJSON 草稿，中断后可以恢复。

## 校园规划

内置路网覆盖默认 KMZ 中的这条走廊，地图上可以吸附到已知路段。在已覆盖道路 20 米内依次标点（最多 7 个），用 Dijkstra 在路段图上求最短路，标点顺序即回放顺序。

## 存储

录制、导入和规划结果保存在本机 SQLite（`tracks` / `points` 两张表）。暂停或定位中断形成的录制分段可以单独打开。

## 构建

用 Android Studio 打开 `android`。安装 Android SDK 36 后，可在该目录运行 `./gradlew :app:assembleDebug`。Gradle JVM 和 Java 17 toolchain 需可用。

## 回归检查

运行 `core/checks/run.sh`，需要 JDK 与 Kotlin 编译器（可用 `KOTLINC` 指定路径）。检查默认轨迹的距离、配速换算、反向规划、路网连通性、跨经线插值，并输出 327 个采样点。

## 更新校园路网

`core/assets/campus.geojson` 使用 WGS‑84 的 GeoJSON FeatureCollection。每条 LineString 代表一条双向步行边。需要连通的路口必须作为各相关道路的端点，并共享完全相同的坐标。更新数据包时应同时核对端点、拓扑和现场通行情况。

当前路网只有一条 644 点的走廊，多路口的连通与换乘只被合成图用例覆盖，接入真实网状数据前需要重新核对。
