# NoReal 定位接管：技术总览与交接

> **一句话**：把"改系统定位源"换成"改应用读到的定位对象"——不去掉 mock 标记，而是给一个本来就没有标记的坐标。
>
> 本文档整合了方案设计、微信链路分析、实测记录、踩坑清单与交接事项，是这条技术路线的唯一入口文档。
> NoReal 是定位测试工具，所有用法仅供自测与研究。

---

## 目录

1. [摘要](#1-摘要)
2. [问题定义](#2-问题定义)
3. [关键技术事实（源码级）](#3-关键技术事实源码级)
4. [方案：三条通道](#4-方案三条通道)
5. [微信定位链路的五层结构](#5-微信定位链路的五层结构)
6. [卡点分析](#6-卡点分析)
7. [实测记录](#7-实测记录)
8. [落地路径（四选一）](#8-落地路径四选一)
9. [踩坑清单](#9-踩坑清单)
10. [当前状态与下一步](#10-当前状态与下一步)
11. [代码地图](#11-代码地图)
12. [附录](#12-附录)

---

## 1. 摘要

**目标**：让微信（含小程序）读到的位置是一条按配速展开的连续轨迹，且不带系统打上的模拟定位标记。

**核心结论**：

| 议题 | 结论 |
| --- | --- |
| 写系统定位源能骗过微信吗 | **不能**。框架强制给测试 provider 产出的坐标打 `isMock`，腾讯 SDK 命中即静默丢弃 |
| 有开关能关掉这个标记吗 | **没有**。`cmd location` 只有测试 provider 一条通道，无 `--is-mock` 参数 |
| 换个注入路径行不行 | **行**。自己 `new Location(...)` 的对象 `isMock` 默认是 false |
| 现在卡在哪 | **两层**：工程层（模块没加载，缺 Shizuku）+ 架构层（微信可能 native 直连、腾讯多源交叉验证） |
| 真机上能彻底解决吗 | 能，但要换环境（容器/模拟器）或上硬件（GNSS 欺骗器），单点软件 hook 不够 |

**一句话**：问题不在坐标，在环境。

---

## 2. 问题定义

### 2.1 验收标准

必须同时满足：

1. 应用读到的 `Location` 对象 `isMock == false`（Android 12+）/ `isFromMockProvider == false`（旧版本）
2. 坐标随轨迹连续推进，速度与方位角与设定配速一致
3. 不依赖 root，不破坏用户主号微信的本地数据
4. 全程在手机本地完成，不走外部网络

### 2.2 为什么不满足于"系统级模拟"

NoReal 原有的能力是把轨迹写成系统定位：`addTestProvider` + `setTestProviderLocation`，
其他应用（高德、地图、大部分 App）都能读到。但微信读不到。

表现很诡异：系统层坐标在推进、微信位置共享的绿点 25 秒不动，**且不报任何错**。
这不是工具失效，是微信主动丢弃了数据。

---

## 3. 关键技术事实（源码级）

三条事实决定了整个技术路线，全部经过源码核实（AOSP android13 / android14 / android15 三个分支）。

### 3.1 mock 标记是 provider 级的，打在框架里

```java
// frameworks/base/services/core/java/com/android/server/location/provider/LocationProviderManager.java
@Override
public void onReportLocation(LocationResult locationResult) {
    ...
    if (mProvider.isMock()) {         // dumpsys 里那个 [mock] 就是它
        ...
    }
}

// MockLocationProvider.setProviderLocation
Location location = new Location(l);
location.setIsFromMockProvider(true);  // 标记在这里被打死
mLocation = location;
reportLocation(LocationResult.wrap(location).validate());
```

推论：

- 只要坐标从 mock provider 出来，`Location.isMock()` 必为 true
- 应用层**没有任何 API** 能清除这个标记（`setMock` 是 @hide 且被框架覆盖）
- 换 provider 名字没用（标记是 provider 级的，不是按名字判断）

### 3.2 `cmd location` 没有关闭标记的参数

`LocationShellCommand.java` 里能做的只有：

```
providers add-test-provider <name>
providers set-test-provider-enabled <name> true|false
providers set-test-provider-location --location <lat>,<lon> [--accuracy <m>] [--time <ms>]
providers remove-test-provider <name>
```

**没有** `--is-mock` 之类的开关。也就是说 adb 这条路也关不掉标记。

### 3.3 唯一不带标记的官方通道被门禁锁着

AOSP 有一个 ADAS GNSS 注入通道（位置直接进 GNSS HAL，按正常 gps provider 交付给应用），
但整段命令注册包在条件里：

```java
if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
    pw.println("  is-adas-gnss-location-enabled [--user <USER_ID>]");
    pw.println("  set-adas-gnss-location-enabled true|false [--user <USER_ID>]");
    ...
}
```

非车机设备 `cmd location help` 里连这些命令都不显示。手机上不可用。

**结论：纯写入端这条路已经封死。**

---

## 4. 方案：三条通道

### 4.1 通道对比

| 通道 | 机制 | 坐标带 mock 标记 | 状态 | 备注 |
| --- | --- | --- | --- | --- |
| **A** 系统测试定位源 | `addTestProvider` + `setTestProviderLocation` | **带** | 已实现，稳定 | 微信读不到；其他应用正常 |
| **B** 接管模式 + LocBridge | 本机回环 UDP 广播坐标 + 模块在目标进程自造 `Location` | **不带** | 链路已通，hook 待验证 | **主线** |
| **C** 模拟器 GPS 硬件 | `adb emu geo fix` / `geo nmea` | **不带** | 坐标注入有效，但通道不稳 | 仅作对照实验 |

### 4.2 通道 B 的完整链路

```
NoReal（手机本地）
  轨迹 + 配速 + 圈数 → 每秒一帧坐标
  接管模式：UDP 广播到 127.0.0.1:38765（只走回环，不出设备）
        │
        ▼
LocBridge（Xposed 模块，免 root 注入目标进程）
  订阅 127.0.0.1:38765 拿坐标
  hook android.location.LocationManager 三条读取路径：
    · getLastKnownLocation(String)
    · getCurrentLocation(String, CancellationSignal, Executor, Consumer)
    · requestLocationUpdates(...) 全部重载（参数里的 LocationListener 换代理）
  交给应用的是 new Location(...) → isMock 天然 false
  兜底：hook Location.isMock() / isFromMockProvider() → false
        │
        ▼
目标应用（微信 / 探针 / 任意包名）读到连续轨迹
```

**设计要点**：不是"把标记去掉"，而是"给一个本来就没有标记的坐标"。
所以下游无论做多少条判据的交叉验证，`isMock` 这一条都拦不住。

### 4.3 本机坐标协议

NoReal 每秒广播一帧 JSON（回环地址，不出手机）：

```json
{"lat":37.123456,"lon":118.123456,"spd":2.78,"brg":52.7,
 "acc":3.0,"ts":1763620000000,"ert":88123456789,"lap":1}
```

| 字段 | 含义 | 说明 |
| --- | --- | --- |
| `lat` / `lon` | WGS84 坐标 | 微信内部转 GCJ-02，注入 WGS84 即正确 |
| `spd` | 速度 m/s | 由配速推出（5:00/km → 3.33 m/s） |
| `brg` | 方位角（度） | 由轨迹切线方向推出 |
| `acc` | 水平精度（米） | 固定 3.0，合理区间 |
| `ts` | 墙上时钟（毫秒） | 填 `Location.time` |
| `ert` | 单调时钟（纳秒） | 填 `Location.elapsedRealtimeNanos` |
| `lap` | 当前圈数 | 多圈回放时用 |

握手：客户端连上后发 `SUB`（4 字节），NoReal 记住来源地址后每秒回推；
客户端 2 秒没收到就重发 `SUB` 保活。

---

## 5. 微信定位链路的五层结构

先把链路摊平，才知道每一刀能切在哪。

```
┌─ 第 1 层：小程序 JS ────────────────────────────────────────┐
│  wx.getLocation({type:'gcj02'})                              │
│  运行在微信的小程序逻辑层进程（:appbrand0 之类）              │
└───────────────────────┬──────────────────────────────────────┘
                        │ JSAPI → 微信内部 JSBridge
┌─ 第 2 层：微信 Java 层 ─────────────────────────────────────┐
│  微信自己的 LocationService / 权限校验 / 缓存                │
│  可能拆在多个进程：主进程、:appbrand、:tools                  │
└───────────────────────┬──────────────────────────────────────┘
                        │ ① Java 调用   或   ② native 直连
┌─ 第 3 层：定位入口 ─────────────────────────────────────────┐
│  ① android.location.LocationManager（Java API）              │
│  ② libwechatapp.so / 腾讯位置服务 .so 直接 binder transact    │
│     拿到 ILocationManager 代理，绕过 Java 层                  │
└───────────────────────┬──────────────────────────────────────┘
                        │ Binder IPC（ILocationManager）
┌─ 第 4 层：system_server ────────────────────────────────────┐
│  LocationManagerService                                      │
│  → LocationProviderManager（真实 gps/network/fused）          │
│  → 给每个 ProviderRequest 打 mock 标记（若来自测试 provider） │
└───────────────────────┬──────────────────────────────────────┘
                        │ Location 对象（含 isMock 字段）
┌─ 第 5 层：腾讯位置服务 SDK ─────────────────────────────────┐
│  坐标到手后做多源交叉验证：                                   │
│  · GnssStatus：卫星数、信噪比、仰角方位角                     │
│  · WifiManager.getScanResults()：周边 BSSID                  │
│  · TelephonyManager：LAC/CID 基站                            │
│  · 轨迹合理性：速度、加速度、瞬移                             │
│  · 出口 IP 归属地、账号历史位置分布（服务端）                  │
│  · Location.isMock()                                         │
└─────────────────────────────────────────────────────────────┘
```

**关键认知**：`Location.isMock()` 只是第 5 层众多判据里的**一条**。
通道 B 只能打掉这一条，其余判据在真机上天然为真。

---

## 6. 卡点分析

"微信过不去"不是 bug，是**两个层面的阻塞叠在一起**，必须分开看。

### 6.1 卡点 A（工程层）：模块根本没被加载

**现场证据**：探针进程（已 patch 并传入 LocBridge 模块）启动日志：

```
E locbridge.prob: Not starting debugger since process cannot load the jdwp agent.
E ActivityThread: Failed to find provider info for top.nkbe.npatch.manager.provider.config
E locbridge.prob: expected 3 dex files but found 1
E ActivityThread: Failed to find provider info for com.noreal.locbridge.provider
```

**没有任何 `[LocBridge] ...` 日志**，而 `handleLoadPackage()` 第一件事就是打日志：

```kotlin
XposedBridge.log("[LocBridge] hooked ${lpparam.packageName} in ${lpparam.processName}")
```

一条都没有 → `handleLoadPackage` 没被调用 → **模块没被加载**，谈不上 hook 失败。

**三个原因**：

1. **模块没在管理器里启用作用域**
   LSPatch/NPatch 的 CLI 把模块 APK 塞进打补丁后的包，只是"随包携带"，
   还要在管理器里给目标应用显式启用。这一步没做，模块不会被加载。

2. **嵌入模式对系统框架类的能力受限（根本原因）**
   要 hook 的 `android.location.LocationManager` 属于 **boot classpath 类**，
   由 `BootClassLoader` 加载，不是应用自己的类。

   | 模式 | 原理 | hook 应用自身代码 | hook 系统框架类 | 前置条件 |
   | --- | --- | --- | --- | --- |
   | 嵌入模式（当前） | loader dex + native 库塞进目标 APK，同进程加载 hook 引擎（LSPlant inline hook ArtMethod） | ✅ | ⚠️ 受限 | 无 |
   | Shizuku 模式 | 借 shell 权限（uid 2000）启动特权进程向目标注入 | ✅ | ✅ | Shizuku 服务在跑 |

   LSPatch 管理器首页明确提示：`Shizuku 服务未连接，部分功能不可用`。
   "部分功能"就包括系统框架作用域——而这正是我们要的。

3. **Android 11+ 包可见性（已修，但暴露风险）**
   targetSdk 30+ 若不在 manifest 声明 `<queries>`，就看不到别的包的 provider：
   - 探针无法注册成 LocBridge 目标（已在探针 manifest 加 `queries` 修掉）
   - ⚠️ **微信的 manifest 里不可能有这些声明**，patch 微信时这条风险依然存在

**补法**：

```bash
# ① 装 Shizuku 并拉起（shell 权限，不需要 root）
adb install -r ~/Downloads/noreal-handoff/shizuku-v13.6.0.r1086.2650830c-release.apk
# 手机上先打开一次 Shizuku 完成初始化
adb shell sh /storage/emulated/0/Android/data/com.rikka.shizuku.privileged.api/start.sh
adb shell ps -A | grep shizuku

# ② LSPatch（org.lsposed.lspatch）设置里切到 Shizuku 模式
# ③ 应用 → LocProbe → 模块 → 勾选 LocBridge → 启用 → 强制重启
# ④ 判定
adb logcat -c && adb shell am force-stop com.noreal.locbridge.probe
adb shell am start -n com.noreal.locbridge.probe/.LocProbeActivity
sleep 6
adb logcat -d | grep -E "LocBridge|hooks installed"
```

**判定标准**：

1. 出现 `[LocBridge] LocationManager hooks installed` → 模块加载成功、hook 装上了
2. 探针界面「系统定位」变成 NoReal 的轨迹坐标且 `isMock=false` → **端到端通了**

如果 1 出现但 2 不成立 → 进入卡点 B。

### 6.2 卡点 B（架构层）：两道关

#### 第一道：微信可能根本不走 Java 层

`LocationManager` 只是 Java API。native 代码可以直接拿 `ILocationManager` 的 binder 代理：

```cpp
// 伪代码：native 直连，完全绕过 Java 层
sp<IServiceManager> sm = defaultServiceManager();
sp<IBinder> binder = sm->getService(String16("location"));
sp<ILocationManager> lm = interface_cast<ILocationManager>(binder);
lm->requestLocationUpdates(request, listener, packageName, attributionTag);  // 直接 IPC
```

腾讯这类厂商的 SDK 在 native 层直连系统服务是常规做法——绕过 Java 权限包装、降延迟，
顺带躲开 Java 层 hook。

**如果微信走这条路，Java 层替换 `Location` 就是空转**：`requestLocationUpdates` 一次都不会被调用。

**判断方法**（关键观测值）：

```bash
# LocBridge 界面里的"命中 N 次"，或看 dumpsys 的注册来源
adb shell dumpsys location | grep -A3 "registration com.tencent.mm"
```

- `hits > 0` 且微信仍读真实位置 → 走 Java 层，但被第 5 层判据拦了
- `hits == 0` 且微信能定位 → **native 直连**，必须换 hook 点

**若确认 native 直连，改法**：

| 方案 | 做法 | 需要 |
| --- | --- | --- |
| hook system_server | 在 `system_server` 里 hook `LocationManagerService` / `LocationProviderManager.onReportLocation`，按 uid 过滤只替换微信的请求 | root 或 Shizuku + 系统框架作用域 |
| hook native 函数 | inline hook `IServiceManager::getService` / `IPCThreadState::transact` | Frida（root）或自写 PLT hook |
| 换容器 | 让微信跑在容器里，容器整体提供定位 | 见 §8 |

⚠️ hook `system_server` 影响面大（全机共享 `LocationManagerService`），
必须按 `ProviderRequest` 的 uid 精确过滤，否则会把整机定位改掉。

#### 第二道：第 5 层的多源交叉验证

这是"再真一点"也没用的原因。真机上腾讯拿到的判据全是真值：

| 判据 | 真机上的值 | 我们能否伪造 |
| --- | --- | --- |
| `Location.isMock` | 可被 hook 改 false | ✅ |
| 轨迹合理性（速度/方位/加速度） | 按配速展开，**达标** | ✅ |
| GnssStatus 卫星数/信噪比 | 头顶真实卫星 | ❌ 未伪造（要 hook `GnssStatus`，构造隐藏类） |
| WiFi BSSID 列表 | 家里/学校真实 AP | ❌ 未伪造（要 hook `WifiManager.getScanResults`） |
| 基站 LAC/CID | 真实基站 | ❌ 未伪造（要 hook `TelephonyManager`） |
| 出口 IP 归属地 | 运营商真实 IP | ❌ 未处理（要 VPN 且与坐标城市一致） |
| 服务端账号画像 | 账号历史位置分布 | ❌ 客户端无解 |

**数学上讲**：要同时让 5–6 条判据互相作证，其中至少两条（IP、服务端画像）
**不在客户端可控范围**。

### 6.3 为什么模拟器/云手机能过

反过来看就清楚了：模拟器里**整套环境都是假的**，交叉验证自洽。

| 判据 | 模拟器里的值 | 结果 |
| --- | --- | --- |
| 坐标 | `adb emu geo fix` 注入虚拟 GPS 硬件 | 无 mock 标记（**已实测**） |
| 卫星 | 模拟器 GNSS HAL 生成 | 与"室内"场景自洽 |
| WiFi | 模拟器虚拟 WiFi（固定 AP） | 与坐标不矛盾 |
| 基站 | 模拟器虚拟基站 | 同上 |
| IP | 宿主机 IP | ⚠️ 唯一可能露馅的点 |
| 设备指纹 | 模拟器指纹 | ⚠️ 微信有模拟器检测 |

这就是"环境自洽"的价值——**单点伪造赢不了，整体伪造才有机会**。

---

## 7. 实测记录

### 7.1 写系统定位源被过滤（真机，已证）

- 临时把轨迹换成北京天安门环线，`dumpsys location` 确认 `gps provider [mock]` 给出的坐标在北京
- 微信位置共享绿点始终停在滨州真实位置，25 秒不动，小程序打卡里程为 0
- 结论：微信（腾讯位置服务）按 `isMock` 过滤了测试定位源

### 7.2 模拟器对照实验（证明"无标记坐标应用会收"）

探针读到：

```
provider=gps lat=39.9087 lon=116.3974 isMock=false
```

再把 NoReal 轨迹经 `geo fix` 推入模拟器 GPS 硬件通道：

```
[17:35:42] geo fix 117.984335 37.388420
探针: system update provider=gps lat=37.38842 lon=117.984335 isMock=false
```

**探针读到的系统定位就是 NoReal 的轨迹坐标，且无 mock 标记。这就是微信将要读到的值。**

### 7.3 真机接管模式广播链路（vivo Y33s，✅）

探针界面实测：

```
【系统定位】
[lastKnown] provider=gps lat=37.388522 lon=117.981327 acc=550.0 spd=0.25 brg=0.2 isMock=false
【本机坐标流】
lat=37.387647 lon=117.985121 spd=3.33 brg=46.3 lap=1 ts=1795169647819
```

- 坐标流 = NoReal 轨迹坐标，速度 3.33 m/s 正好对应 5:00 配速 ✅
- 系统定位 = 真实位置（accuracy 550 m 的粗定位），**接管模式没污染系统定位** ✅

### 7.4 免 root 注入框架可用性（真机，✅/❌）

| 框架 | 版本 | 结果 |
| --- | --- | --- |
| NPatch | 1.1.0 (857) | ❌ `Fatal signal 4 (SIGILL) in NPatch-LogFlush`，native 库在这台机器上执行非法指令 |
| LSPatch | 0.6 (398) | ✅ 正常，管理器可用，模块被识别为 Legacy API |
| VirtualXposed | 0.22.0 (2022) | ⚠️ 无 release 资产，需自行构建，未尝试 |
| Shizuku | 13.6.0 (1086) | 已下载，未安装 |

**LSPatch CLI 用法**：

```bash
JAVA="/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java"   # 需要 JDK 21+
"$JAVA" -jar jar-v0.6-398-release.jar <target.apk> <module.apk> [...]
# 输出 <target>-398-lspatched.apk
```

注意：**模块自身也要 patch 一份**（`locbridge.apk` 同样要 patch），否则模块不会被加载。

---

## 8. 落地路径（四选一）

前提都是**不动主号微信数据**。LSPatch/NPatch 都不支持覆盖安装（patch 后签名变了），
patch 微信必须卸载原版再装。

### 甲：容器承载微信（推荐）

```
手机
├─ NoReal（宿主）── 回环 UDP 127.0.0.1:38765 ──┐
└─ 容器（VirtualApp / 黑盒 / 多开类）             │
    └─ 微信（容器内）← LocBridge 模块 ←──────────┘
        容器提供：虚拟定位 + 虚拟 WiFi + 虚拟基站 + 虚拟设备指纹
```

- 容器本身就是"环境整体为假"的缩微版：主流多开容器会伪造 IMEI、AndroidID、WiFi 信息
- 微信装在容器里，**主号零风险**（数据完全隔离），容器内重装不涉及主号卸载
- 链路与现在完全一致：宿主 NoReal 广播 → 容器内 LocBridge 订阅（回环地址同机共享）
- 待验证：容器内能否注入（LSPatch patch 容器 APK，或容器内二次 patch 微信）

### 乙：小号 patch 重装微信

- 流程：`LSPatch jar → 微信 APK → patched APK → 卸载原版 → 安装 → 小号登录`
- 主号不受影响（或者干脆用小号）
- 风险：微信有签名自校验与改包检测，可能闪退或触发风控
- 必须在 **Shizuku 模式**下 patch 才有系统框架作用域

### 丙：硬件 GNSS 欺骗器（¥100–300）

- 蓝牙/USB 形态的 NMEA 发生器，手机把它当外部 GNSS 接收机
- 第 3、4 层完全绕过（驱动层收到就是"真"卫星数据）→ **无 mock 标记**
- 第 5 层的卫星判据自然满足（NMEA 里带卫星数、信噪比、仰角）
- 局限：多数模块只支持定点，连续轨迹要 SDR（HackRF + gps-sdr-sim，¥1500+）
- **真机上唯一能同时满足"无标记 + 卫星数据自洽"的软硬件组合**

### 丁：root + 全套 hook（不推荐）

- 需要 root（这台 vivo Y33s 无 root，2017 年机型官方解锁渠道已关）
- 要 hook 的面：`LocationManager`、`Location.isMock`、`GnssStatus`、
  `WifiManager.getScanResults`、`TelephonyManager.getAllCellInfo`、`SystemProperties`（指纹）
- 再加 IP 处理（VPN 出口与坐标城市一致）
- 工程量大，且服务端画像仍可能判定

### 对比

| 方案 | 主号风险 | 成本 | 环境自洽 | 轨迹可控 | 可行性 |
| --- | --- | --- | --- | --- | --- |
| 甲 容器 | 无 | 零（下载容器） | 较好 | 中 | 待验证注入 |
| 乙 小号 patch | 无 | 零 | 中 | 好 | 中（有风控） |
| 丙 硬件 GNSS | 无 | ¥100–300 | 好（含卫星） | 定点为主 | 高 |
| 丁 root 全套 | 无 | 解锁成本 | 最好 | 好 | 低（本机无 root） |

---

## 9. 踩坑清单

全部踩过，别再踩。

1. **UDP 的 ICMP 陷阱**（最隐蔽）
   `connect()` 过的 UDP socket，在对端没监听时会收到 ICMP port-unreachable，
   之后收发全部失败、线程静默死亡。模块总是比 NoReal 先启动，这条路径原本必然失效。
   → 订阅端一律**不 connect**，目标地址写进 `DatagramPacket(buf, len, target)`，定时重发 `SUB`。

2. **Android 的 `DatagramSocket` 没有 `send(packet, addr)` 重载**
   → 目标地址要写进 `DatagramPacket` 本身。

3. **包可见性（Android 11+）**
   targetSdk 30+ 若不声明 `<queries>`，找不到 NPatch 的配置 provider（模块注入读不到配置）
   和 LocBridge 的 provider（注册目标失败），日志是 `Failed to find provider info for ...`。
   探针 manifest 已加；**微信的 manifest 不会有，patch 微信时是已知风险**。

4. **Android 8+ 隐式广播不再送达 manifest receiver**
   → 必须用显式组件：`am broadcast -n com.noreal.android/.DebugReceiver -a <action>`

5. **manifest 上的 `android:permission="android.permission.DUMP"` 会让 adb shell 发不出去**
   → 改成运行时校验 `Binder.getCallingUid()`（只接受 shell/root）。

6. **Android 12 后台启动前台服务受限**
   → 先 `am start` 把 NoReal 拉到前台，再发广播。

7. **logcat buffer 会被设备上的 GMS 日志冲掉**
   → 自动化脚本必须**流式抓**（`logcat > file &`），事后 `logcat -d` 会漏事件。

8. **模拟器 geo fix 通道不稳**
   API 37 镜像下 `adb emu geo fix` 返回 OK 但坐标不变
   （`Brokered program policy check unavailable`），`-gpu host` 与 swiftshader 都一样。

9. **模拟器进程会被工具会话回收**
   → 用 `python3 -c "import os; os.setsid(); os.execv(...)"` 启动才活得久。

10. **vivo 的 `adb shell input tap` 可用，但 uiautomator 看不到 Compose 的 Switch**
    → 界面控件定位要靠截图 + 比例换算（截图 470 宽 → 真实 720 宽，比例 1.532）。

---

## 10. 当前状态与下一步

### 10.1 已完成（有实证）

| 项 | 证据 |
| --- | --- |
| 写系统定位源会被打标记 | AOSP 源码 + 实测 dumpsys `[mock]` |
| `cmd location` 无关闭标记的参数 | android13/14/15 三分支 grep |
| 无标记坐标应用会收 | 模拟器探针 `isMock=false` |
| 真机接管模式广播链路通 | 探针收到 `lat=37.387647 lon=117.985121 spd=3.33 brg=46.3` |
| 接管模式不污染系统定位 | 探针同时读到真实位置 `acc=550.0` |
| NPatch 在本机不可用 | `Fatal signal 4 (SIGILL) in NPatch-LogFlush` |
| LSPatch v0.6 可用 | 管理器正常、模块被识别为 Legacy API |
| 自动测试脚本可用 | `tools/autotest/run.sh` 四项断言 |

### 10.2 未完成（下一步，按顺序）

1. **装 Shizuku + adb 拉起服务**（命令见 §6.1）
2. **LSPatch 切 Shizuku 模式**
3. **管理器里给 LocProbe 启用 LocBridge 模块**
4. **验证 hook 生效**（判据见 §6.1）
5. **观察 `hits` 计数与 `dumpsys location` 的 registration**，判断微信走 Java 层还是 native 直连
6. **按 §8 选一条落地**（甲/乙/丙），取决于对主号数据风险的接受度

### 10.3 手机上当前的安装状态

| 包名 | 状态 |
| --- | --- |
| `com.noreal.android` | NoReal 最新版（带接管模式 + DebugReceiver），未 patch |
| `com.noreal.locbridge` | LocBridge 模块，**已 patch（LSPatch 0.6）** |
| `com.noreal.locbridge.probe` | 探针，**已 patch（LSPatch 0.6）** |
| `org.lsposed.lspatch` | LSPatch manager 0.6 (398) |
| `top.nkbe.npatch` | NPatch 已卸载（SIGILL） |
| 微信 | **未 patch，主号数据完好** |

工具备份：`~/Downloads/noreal-handoff/`（Shizuku APK + LSPatch jar）。

### 10.4 一句话

**卡住不是技术死路，是"工具链少一环（Shizuku）+ 架构上还有两道关没验证"。**
先补齐 Shizuku 把模块加载跑通，拿到 `hits` 计数这个关键观测值，
才知道微信是走 Java 层（可改）还是 native 直连（要换 hook 点或换容器）。
在此之前，"微信过不去"还不能算定论。

---

## 11. 代码地图

```
android/app/                     NoReal 主程序
  LocalBroadcaster.kt            接管模式的回环 UDP 广播
  LocationSessionService.kt      接管 / 普通两条分支
  SessionState.kt                Mock 增加 intercept / subscribers 字段
  MainActivity.kt                「接管模式」开关 + 订阅者数量反馈
  DebugReceiver.kt               shell 可控的启动入口（只接受 shell/root）
  MockLocationOutput.kt          系统测试定位源（通道 A）
  TrackStore.kt / TrackFiles.kt  SQLite 轨迹库 + 文件导入
  TrackMapView.kt                轨迹地图
  core/kotlin/com/noreal/core/
    RouteCore.kt                 轨迹展开、配速、圈数、校园路网 Dijkstra

android/locbridge/               Xposed 模块（通道 B 的读取端）
  LocBridge.kt                   入口（IXposedHookLoadPackage）
  LocationBridge.kt              三条读取路径 + isMock 兜底 hook
  StreamClient.kt                坐标流订阅（不 connect 版）
  FixSource.kt                   当前假坐标 + rewrite + 命中计数
  BridgeConfig.kt                目标包名 / 端口配置
  BridgeProvider.kt              跨进程传递配置与状态
  MainActivity.kt                模块配置页
  src/stub/java/de/robv/…        Xposed 经典 API 编译期占位
                                 （Gradle 任务用 javac 编成 jar，不引外部依赖）

android/locprobe/                定位探针（独立模块，包名 com.noreal.locbridge.probe）
  LocProbeActivity.kt            订阅坐标流 + 读系统定位，全部打进 logcat

tools/autotest/run.sh            接管模式链路自动测试（4 项断言）
tools/emu-track.sh               坐标流 → adb emu geo fix（模拟器对照用）

docs/
  NoReal-定位接管技术总览.md      本文档
```

---

## 12. 附录

### 12.1 AOSP 关键源码位置

| 内容 | 路径 |
| --- | --- |
| mock 标记设置 | `frameworks/base/services/core/java/com/android/server/location/provider/LocationProviderManager.java` |
| 测试 provider 打标 | `MockLocationProvider.setProviderLocation()` → `setIsFromMockProvider(true)` |
| shell 命令面 | `frameworks/base/services/core/java/com/android/server/location/LocationShellCommand.java` |
| ADAS 门禁 | `LocationShellCommand.onHelp()` 里的 `hasSystemFeature(FEATURE_AUTOMOTIVE)` |
| 我们 hook 的三条路径 | `android/locbridge/.../LocationBridge.kt` |
| 命中计数（判断走哪条路） | `android/locbridge/.../FixSource.kt` 的 `hits` |

### 12.2 诊断命令速查

```bash
# 模块是否加载
adb logcat -d | grep -E "LocBridge|hooks installed"

# 系统里有没有 mock provider（接管模式应为空）
adb shell dumpsys location | grep -E "\[mock\]|mock provider"

# 微信的定位注册（看间隔、provider、是否 HIGH_ACCURACY）
adb shell dumpsys location | grep -A3 "registration com.tencent.mm"

# 定位时间戳是否推进（判断是否真在写）
adb shell dumpsys location | grep "last location=Location\[gps"

# 本机坐标流端口（38765 = 0x976D）
adb shell cat /proc/net/udp /proc/net/udp6 | grep -i 976d

# 自动测试
tools/autotest/run.sh <设备序列号>

# 模拟器对照实验
tools/emu-track.sh <设备序列号>
```

### 12.3 提交历史

```
fe9bfcf  新增技术分析：微信定位链路的五层结构与两个卡点
fdce57b  新增交接文档：手机端让微信小程序读到干净轨迹坐标
b5c894a  修复 UDP 订阅的 ICMP 陷阱，补包可见性声明；记录手机端免 root 注入实测
e1d9b71  新增自动测试脚本与定位探针
3fcab29  新增接管模式与 LocBridge 模块
7dc7446  README 补充微信生态的已知限制与自查方法
```

未推送 GitHub（历史上 push 需要 `git config http.version HTTP/1.1`）。
