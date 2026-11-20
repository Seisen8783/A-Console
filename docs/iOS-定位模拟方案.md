# iOS 定位模拟方案（免越狱 / 免开发者账号 / 支持 GPX 轨迹）

> 记录时间：2026-11-20 19:40
> 目标设备：iPhone（iOS 17 / 18 / 26+ 均已验证可行）
> 配套：NoReal 轨迹引擎（负责生成 GPX）

---

## 0. 结论先行

| 常见误解 | 实际情况 |
| --- | --- |
| iOS 要 $99 开发者账号 | **不需要**。设备信任 + 开发者模式即可 |
| iOS 要越狱 | **不需要** |
| iOS 要装一个 App 才能改定位 | **不需要**，注入从 Mac 侧发起 |
| iOS 只能模拟单个 App 的定位 | **不是**，注入是**整机级**的，CoreLocation 输出被整体替换 |
| 只能定点不能轨迹 | **能轨迹**，直接喂 GPX，按时间戳回放 |

**一句话**：Mac + 一根数据线 + `pymobiledevice3` + 一个 GPX 文件，就能让整台 iPhone 沿着轨迹移动。

---

## 1. 技术原理

### 1.1 iOS 的定位入口只有一条

Android 上我们吃了大亏的地方（Java `LocationManager` / native binder 直连 / fused provider 多路径），
在 iOS 上不存在：**所有 App 都只能通过 CoreLocation 拿定位**（`CLLocationManager`）。

所以只要 CoreLocation 的输出被替换，所有 App（含微信）拿到的都是假坐标。

### 1.2 注入走的是开发者服务，不是"模拟 App"

Xcode / Instruments 调试真机时允许模拟位置，这套能力来自设备上的 **DVT（Developer Tools）服务**，
其中包含 `LocationSimulation` 通道。`pymobiledevice3` 直接调用这个通道：

```
Mac ──USB/lockdown──▶ iPhone
      ◀── RemoteXPC tunnel（iOS 17+ 加密通道）──▶
                         │
                         ▼
              DVT LocationSimulation
                         │
                         ▼
                CoreLocation（整机输出）
```

关键差异（对比 Android）：

| | Android test provider | iOS DVT 注入 |
| --- | --- | --- |
| 作用域 | 注册了 provider 名字的坐标源 | **整机** CoreLocation 输出 |
| 标记机制 | 强制打 `isMock = true` | 位置对象上无 "mock" 字段，只有 iOS 15+ 的 `sourceInformation` |
| 需要权限 | 模拟位置应用授权（免 root） | 设备信任 + 开发者模式（免开发者账号） |
| App 侧能否检测 | 一条 API `Location.isMock()` | `CLLocationSourceInformation.isSimulatedBySoftware` |

### 1.3 唯一的检测点

iOS 15 起，`CLLocation` 带 `sourceInformation`：

```swift
location.sourceInformation?.isSimulatedBySoftware   // 软件模拟 → true
location.sourceInformation?.isProducedByAccessory   // 外部配件（如车机 GPS）→ true
```

**微信是否会检查这一项，是这条路唯一的未知数**，必须实测（见 §8）。
好消息是：Android 上微信明确过滤了 `isMock`，而 iOS 上历史上各类虚拟定位工具对微信长期有效。

---

## 2. 三条路线对比

| 路线 | 工具 | 需要 | 可脚本化 | 成本 | 推荐度 |
| --- | --- | --- | --- | --- | --- |
| **A** | `pymobiledevice3`（开源 Python） | Mac + USB + 开发者模式 | ✅ 完全可脚本化 | 0 | ★★★★★ |
| B | Xcode → Debug → Simulate Location | Mac + Xcode | ❌ 纯 GUI 操作 | 0 | ★★★ |
| C | iTools / 爱思助手 | Mac/Windows + 数据线 | ❌ 闭源 GUI | 0（可能有广告） | ★★★（最快验证） |

**建议**：先用 C 花 10 分钟验证"微信买不买账"，通了再上 A 做自动化。
B 的价值在于它是官方通道，遇到 A 的兼容问题时可用来对照。

---

## 3. 路线 A 详细步骤

### 3.1 Mac 侧环境

```bash
# Python 3.11+ 即可（macOS 自带 3.13 也可以）
python3 -m venv ~/.venv-ios && source ~/.venv-ios/bin/activate
pip install -U pymobiledevice3
pymobiledevice3 version
```

> 建议用虚拟环境，避免和系统 Python 的依赖打架。

### 3.2 iPhone 侧准备

1. **开启开发者模式**：设置 → 隐私与安全性 → **开发者模式** → 打开 → **重启手机** → 重启后确认
   （iOS 16+ 才有这个开关；设备受 MDM/家长控制时可能被禁）
2. USB 连接 Mac → 手机上点「**信任此电脑**」→ 输入锁屏密码
3. 保持解锁状态（锁屏后部分服务会断开）

### 3.3 首次挂载 DDI（只需一次）

```bash
sudo pymobiledevice3 mounter auto-mount
```

DDI（Developer Disk Image）是开发者服务运行所需的镜像，首次使用必须挂载。
失败时先确认设备已信任、已解锁，再重试。

### 3.4 建立 tunnel（iOS 17+ 必须）

iOS 17 起开发者服务走 RemoteXPC 加密通道，需要单独起一个 tunnel 服务：

```bash
sudo pymobiledevice3 remote tunneld
# 保持这个终端不关，会输出：
#   INFO: Uvicorn running on http://127.0.0.1:49151
```

或者手动起 tunnel 拿 RSD 地址：

```bash
sudo pymobiledevice3 lockdown start-tunnel
# 输出示例：
#   RSD Address: fd25:5d47:c482::1
#   RSD Port: 50594
# 之后的命令带上：--rsd fd25:5d47:c482::1 50594
```

> iOS 16 及以下不需要 tunnel，直接用 `developer simulate-location` 即可。

### 3.5 注入坐标

```bash
# ① 定点（注意顺序是 纬度 经度）
pymobiledevice3 developer dvt simulate-location set -- 39.9087 116.3974

# ② 播放 GPX 轨迹（核心能力）
pymobiledevice3 developer dvt simulate-location play route.gpx

# ③ 加随机时间噪声，让移动更像真人（±500ms）
pymobiledevice3 developer dvt simulate-location play route.gpx 500

# ④ 结束并恢复真实定位（务必执行）
pymobiledevice3 developer dvt simulate-location clear
```

⚠️ **三个必须注意的点**：

1. 坐标顺序是 **`lat lon`**（纬度在前），写反了会跑到地球另一边
2. `--` 不能省（防止负经度被当成命令行参数）
3. **结束一定执行 `clear`**；异常退出时如果定位卡住，**重启手机**即可恢复

### 3.6 一条命令的封装（建议做成脚本）

```bash
tools/ios-track.sh route.gpx        # 起 tunnel → 播放 → Ctrl-C 时自动 clear
```

---

## 4. GPX 格式规范（NoReal 要导出的目标格式）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="NoReal"
     xmlns="http://www.topografix.com/GPX/1/1">
  <metadata><name>校园跑 · 5:00/km · 2 圈</name></metadata>
  <trk>
    <name>校园跑</name>
    <trkseg>
      <trkpt lat="37.388522" lon="117.981327">
        <ele>0</ele>
        <time>2026-11-20T11:34:39Z</time>
      </trkpt>
      <trkpt lat="37.388510" lon="117.981340">
        <ele>0</ele>
        <time>2026-11-20T11:34:40Z</time>
      </trkpt>
    </trkseg>
  </trk>
</gpx>
```

**规范要点**（决定回放是否平滑）：

| 项 | 要求 | 原因 |
| --- | --- | --- |
| 采样间隔 | **1 秒一个点** | pymobiledevice3 按 `<time>` 回放，间隔决定速度；点太稀会跳 |
| `<time>` 格式 | UTC ISO 8601，带 `Z` | 本地时区会被当成 UTC，导致回放速度异常 |
| 坐标精度 | 6 位小数（约 0.1 m） | 够用，且避免浮点噪音 |
| 圈数 | 把 `<trkseg>` 重复 N 遍，时间连续递增 | 单圈播完会停 |
| 起终点 | 首尾尽量接近（<30 m） | 否则循环处会瞬移 |
| 点数上限 | 建议 < 20000 | 太多会拖慢回放 |

NoReal 的轨迹引擎已经有 `Route.sample(elapsedSeconds, durationSeconds)`，
按 1 秒步长采样、加上圈数循环，就能直接生成合规 GPX（约 100 行代码）。

---

## 5. 开发状态

### 5.1 GPX 导出（已完成）

```
轨迹 → 按配速展开（Route.sample()）→ 1 秒采样 → 写 trkpt + time → GPX
```

落在 `core/kotlin/com/noreal/core/GpxWriter.kt`，与平台无关：

- 复用 `Route.sample()`，无需新算法
- 圈数在外层重复，时间戳连续累加
- 单文件上限 20000 点，超出自动降采样
- `loopGapMeters()` 用来判断循环处会不会跳变

> 导出后需手工把文件交给 Mac 侧（AirDrop / 微信传文件 / 存本地），目前没有做系统分享入口。

### 5.2 Mac 侧推送器（已完成，见 `macos/`）

Swift 控制台 + Python 设备桥接（`device_bridge.py`，包 `pymobiledevice3`）：

```
选择 GPX/KMZ/KML → 自动起 tunnel → 播放 → 显示当前坐标/进度 → 停止时自动 clear
```

控制台还负责定位到起点、时间条跳转、暂停保持当前位置、恢复定位。

现成参考实现（都是 MIT）：
- `JRTTF/PikminBloom`：Python GUI，支持路线/定点/收藏/速度预设，iOS 17/18/26+
- `BiancoChiu/iOSEasyRun`：route.txt + config.yaml 生成 GPX 并播放，iOS 18.3.1 实测

### 5.3 一条命令的闭环（未做）

```bash
noreal push route.gpx        # NoReal 生成的 GPX 直接推给 iPhone
```

---

## 6. 为什么 iOS 可能比 Android 更容易

| 维度 | Android | iOS |
| --- | --- | --- |
| 定位入口 | 多路径（Java / native binder / fused） | **只有 CoreLocation** |
| 需要的权限 | root 或容器或改 APK | 设备信任 + 开发者模式 |
| 标记机制 | 强制 `isMock`，已实测被微信过滤 | 无 mock 字段，只有 `isSimulatedBySoftware` |
| 轨迹回放 | 要自己写推送（已实现） | **原生支持 GPX** |
| 主号风险 | patch 微信要卸载重装 | **零风险**（不装任何东西） |
| 环境自洽 | WiFi/基站/卫星都是真值 → 交叉验证过不去 | 同样存在，但注入是整机级，微信拿到的就是"系统定位" |

**逻辑上**：Android 上的失败是"框架给坐标打了标记 + 微信读的是带标记的对象"。
iOS 上这个机制不存在——微信拿到的 `CLLocation` 来自被整体替换的 CoreLocation，
和真实定位在结构上无法区分（除了 `sourceInformation` 这个可选字段）。

---

## 7. 风险与限制

| 风险 | 说明 | 应对 |
| --- | --- | --- |
| `isSimulatedBySoftware` 检测 | iOS 15+ 的字段，若腾讯 SDK 检查会露馅 | **必须实测**；这是唯一的关键未知数 |
| 依赖连接 | 拔线/停止 tunnel 后立即恢复真实定位 | 跑轨迹期间保持 USB 连接（可用长线/延长线） |
| 开发者模式 | 设备受 MDM/家长控制时可能无法开启 | 用个人设备 |
| DDI 挂载失败 | 首次常见 | 确认解锁 + 信任 + 重试；必要时用 Xcode 打开一次设备 |
| tunnel 起不来 | iOS 17+ 需要管理员权限、端口占用 | `sudo`，检查 49151 端口 |
| 异常退出卡住定位 | 没执行 `clear` | 重启手机即可恢复 |
| 微信风控 | 位置突变可能触发异常标签 | 轨迹起点设在真实位置附近，慢慢"走"过去；不要跨城瞬移 |
| 平台条款 | 模拟位置可能违反某些应用的服务条款 | 仅用于自测，别用于打卡作弊等场景 |

---

## 8. 10 分钟验证清单（先做这个，再谈开发）

```bash
# 1. 环境
python3 -m venv ~/.venv-ios && source ~/.venv-ios/bin/activate
pip install -U pymobiledevice3

# 2. iPhone 开开发者模式 + 信任电脑 + 解锁

# 3. 挂 DDI（首次）
sudo pymobiledevice3 mounter auto-mount

# 4. 起 tunnel（iOS 17+，另开一个终端保持）
sudo pymobiledevice3 remote tunneld

# 5. 偏移一个点：把定位推到 500 米外
pymobiledevice3 developer dvt simulate-location set -- 37.3920 117.9850

# 6. 观察
#    · 系统地图 App 的蓝点是否跳过去          → 验证注入成功
#    · 微信位置共享的绿点是否也跳过去          → 验证微信不检查 sourceInformation
#    · 打开目标小程序，看 wx.getLocation 输出  → 最终判据

# 7. 收尾
pymobiledevice3 developer dvt simulate-location clear
```

**判据**：

| 观察结果 | 结论 |
| --- | --- |
| 地图蓝点跳了，微信绿点也跳 | ✅ **这条路通，立刻转 iOS** |
| 地图蓝点跳了，微信绿点不动 | ❌ 微信检查了 `sourceInformation`；要越狱 + hook 才能过 |
| 地图蓝点不动 | 注入没成功，回去查 DDI / tunnel / 开发者模式 |

---

## 9. 如果验证通过，后续排期

| 阶段 | 内容 | 工作量 |
| --- | --- | --- |
| P1 | NoReal 加 GPX 导出（含圈数、配速、校园路网） | 半天 |
| P2 | `tools/ios-track.sh`：一条命令起 tunnel + 播放 + 自动 clear | 半天 |
| P3 | Mac 推送器 GUI（可选，选轨迹/调速度/看进度） | 1 天 |
| P4 | 轨迹设计器增强：地图上画路线、多段拼接、时间偏移 | 2 天 |

---

## 10. 附录

### 10.1 命令速查

```bash
# 环境
pip install -U pymobiledevice3
pymobiledevice3 version

# 设备信息
pymobiledevice3 usbmux list

# 首次挂载开发者镜像
sudo pymobiledevice3 mounter auto-mount

# tunnel（iOS 17+）
sudo pymobiledevice3 remote tunneld                 # 常驻服务
sudo pymobiledevice3 lockdown start-tunnel          # 手动拿 RSD 地址

# 定位注入
pymobiledevice3 developer dvt simulate-location set -- <lat> <lon>
pymobiledevice3 developer dvt simulate-location play <file.gpx> [noise_ms]
pymobiledevice3 developer dvt simulate-location clear

# 指定设备（多台时）
pymobiledevice3 --udid <UDID> developer dvt simulate-location play route.gpx
```

### 10.2 常见问题

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `DeveloperModeIsDisabled` | 开发者模式没开 | 设置 → 隐私与安全性 → 开发者模式 → 重启 |
| `MounterError` / DDI 挂载失败 | 未解锁 / 未信任 / 系统版本不匹配 | 解锁重试；用 Xcode 连一次设备让它自己挂 |
| `no such device` | 多设备或线材问题 | `pymobiledevice3 usbmux list` 确认；换原装线 |
| tunnel 端口占用 | 上次没退干净 | `lsof -i :49151` 杀掉 |
| 播放完位置停在终点 | 正常 | 执行 `clear` |
| 定位卡住不恢复 | 异常退出 | 重启手机 |

### 10.3 参考项目

| 项目 | 说明 |
| --- | --- |
| `doronz88/pymobiledevice3` | 本方案的核心工具，Python 实现 iDevice 协议栈 |
| `JRTTF/PikminBloom` | Python GUI，路线/定点/收藏/速度预设，iOS 17/18/26+ |
| `BiancoChiu/iOSEasyRun` | route.txt + config.yaml → GPX → 播放，iOS 18.3.1 实测 |
| AOSP `LocationShellCommand`（对照） | Android 侧，含 ADAS 门禁 |

---

## 11. 与 Android 方案的取舍

| | Android（已完成的部分） | iOS（本方案） |
| --- | --- | --- |
| 已投入 | 接管模式 + LocBridge + 探针 + 自动测试 | 0 |
| 卡点 | 模块加载（缺 Shizuku）+ native 直连 + 多源交叉验证 | `isSimulatedBySoftware` 检测（未知） |
| 成功率 | 中（取决于容器/硬件） | **待验证，但结构上更有利** |
| 附加成本 | 容器或硬件 ¥100–300 | 0 |

**建议**：先按 §8 花 10 分钟验证 iOS。如果通过，NoReal 的定位从"改系统定位"收敛为
「**轨迹设计器 + 多端推送器**」——GPX 一份文件，iPhone（pymobiledevice3）、
Android 模拟器（geo fix）、SDR（gps-sdr-sim）、BLE GNSS 欺骗器都能消费。
这一层抽象是复用的，不管底层走哪条路都不浪费。
