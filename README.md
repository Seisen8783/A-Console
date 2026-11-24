# A Console macOS 控制台

通过 USB 连接自己的测试 iPhone，在 Mac 上预览和回放 GPX、KMZ 或 KML 轨迹。控制台支持定位到轨迹起点、暂停与继续、调整回放配速和圈数，也可以查看预计完成时间。

手机无需安装额外 App、越狱或开发者账号。本工具用于定位与轨迹的开发测试，其他 App 是否显示模拟位置取决于各自的处理方式。

## 一、下载 macOS 版本

- **历史 DMG（2.0.36）**：[下载历史 macOS 安装包](https://github.com/Seisen8783/A-Console/releases/download/macos-v2.0.36/UnReal-Console-2.0.36-macOS.dmg)
- **原版 ZIP（0.2.0）**：[查看原 macOS 发布页](https://github.com/Seisen8783/A-Console/releases/tag/macos-v0.2.0)

新版适用于 Apple 芯片 Mac、macOS 14 或以上，并需要一台运行 iOS 17 或以上的 iPhone。项目现名为 A Console，历史安装包使用 NoReal Console 或 UnReal Console 名称。

## 二、从安装到回放

### 1. 安装控制台

1. 点击上方的新版 DMG 链接，等待下载完成。
2. 在 Mac 的「下载」文件夹中双击 `UnReal-Console-2.0.36-macOS.dmg`。
3. 把窗口里的应用图标拖到 **Applications（应用程序）** 文件夹图标上。
4. 打开访达，在左侧点击「应用程序」，找到并打开已安装的应用。
5. 如果 Mac 提示无法验证开发者，这是因为安装包使用临时签名，尚未经过 Apple 公证。确认文件来自本仓库后，可按 [Apple 的说明](https://support.apple.com/zh-cn/102445)在系统设置中选择是否打开。

### 2. 安装设备连接工具

控制台需要 `uv` 来准备连接 iPhone 的程序。打开 Mac 的「终端」（按下 `command + 空格`，输入“终端”并回车），运行：

```bash
uv --version
```

如果显示版本号，说明已经安装，可以继续。如果提示找不到命令，请按照 [uv 官方安装说明](https://docs.astral.sh/uv/getting-started/installation/)安装；如果已经安装 Homebrew，也可以运行 `brew install uv`。安装后关闭并重新打开控制台。首次连接时需要联网下载依赖，请耐心等待。

### 3. 准备并连接 iPhone

1. 使用支持数据传输的 USB 数据线将 iPhone 连接到 Mac。
2. 解锁 iPhone。如果手机询问是否信任此电脑，点击「信任」并输入锁屏密码。
3. 在 iPhone 打开「设置 → 隐私与安全性 → 开发者模式」，开启后按提示重启并确认。若看不到此选项，请先在 Mac 的 Xcode 设备窗口确认手机已配对，详见 [Apple 开发者模式说明](https://developer.apple.com/documentation/xcode/enabling-developer-mode-on-a-device)。
4. 回到控制台，在「USB 设备」旁点击刷新按钮，然后选择你的 iPhone。

### 4. 选择并回放轨迹

1. 「轨迹来源」默认使用内置轨迹。要使用自己的轨迹，选择「本地导入」，然后选择 GPX、KML 或 KMZ 文件；也可以直接把文件拖入预览区域。
2. 默认坐标基准为 **WGS-84**，上报口径为 **原样**。如果你确定导入文件使用其他坐标基准，再在侧栏修改；不确定时先用默认值检查位置。
3. 点击「**接管定位权限**」，等待状态提示已定位到起点，再在手机上检查起点。
4. 根据需要设置配速和「总圈数」或「公里数」，点击「**开始回放**」。右侧显示轨迹进度和预计完成时间。
5. 回放中可点击「暂停」，需要时调整可编辑的配速或圈数，有效设置会自动同步并更新总时长，再点击「继续回放」；也可以拖动时间轴跳转。回放结束后，控制台会尝试把位置移回起点。
6. 使用完毕后，点击「**重置位置**」，向 iPhone 发送恢复真实定位的请求。如果连接中断或状态显示待确认，请重新连接同一台 iPhone 后再重试，并在手机上检查位置。

回放期间请保持 USB 连接。开发者服务连接短暂中断时，控制台会尝试重连同一台 iPhone，从中断处继续。如果重连失败，请检查数据线、解锁手机后重新连接。配速、轨迹数据和手机定位精度也可能影响实际显示。

## 更多信息

- [控制台详细说明](macos/README.md)
- [20 秒测试轨迹](macos/Tests/Fixtures/sample.gpx)
- [控制台方案与实现](docs/macOS-定位回放控制台方案.md)
- [iOS 定位模拟方案](docs/iOS-定位模拟方案.md)
- [历史 Android 版本](https://github.com/Seisen8783/A-Console/releases/tag/v1.0)

## 从源码构建

```bash
./macos/build-app.sh
swift test --package-path macos
uvx --from 'pymobiledevice3==11.61.0' --python 3.14 python -m unittest discover -s macos/Tests/Python -v
```

安装 uv 后，运行 `./macos/build-dmg.sh` 可生成带 Finder 布局的 DMG。构建产物位于 `macos/build/A Console.app`。每次构建会递增仓库根目录中的 `VERSION`。

## 许可证

MIT，见 [LICENSE](LICENSE)。

## Windows 控制台

Windows 版的源码运行方式、Apple USB 设备服务和真机验收流程见 [Windows 控制台说明](windows/README.md)。
