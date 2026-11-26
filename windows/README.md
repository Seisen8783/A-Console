# A Console Windows

目标平台为原生 Windows 11 x64 和支持 USB 开发者服务的 iOS 17.4 或以上版本。无需 Xcode。

如果电脑还没有 Apple Mobile Device Service，A Console 安装向导会默认选中 **Install Apple device support with iTunes**。离线安装时，提前从 [Apple 下载此版本的 iTunes](https://secure-appldnld.apple.com/itunes12/142-28289-20261026-6acff6c8-72eb-4045-84eb-fd35239ac645/iTunes64Setup.exe)，将 `iTunes64Setup.exe` 放在 A Console 安装包旁边。向导会优先读取并校验同目录的安装文件。如未找到本地文件，向导会从 Apple 下载同一版本。安装 iTunes 时按提示授予管理员权限。安装包带有便携版 uv，系统 PATH 中没有 uv 时会把它放入 A Console 的 `tools\uv` 目录。

通过数据线连接 iPhone，解锁、信任电脑，并在「设置 → 隐私与安全性」启用开发者模式。若没有开关，可以先用上游的 [reveal-developer-mode](https://doronz88.github.io/pymobiledevice3/latest/cli/amfi/) 显示开关。首次准备设备开发者镜像可能需要联网。

定位模拟会优先使用设备上已启用的开发者服务。已有相容的 iOS 开发者镜像时，可以将 `A_CONSOLE_DDI_RESTORE_DIR` 指向包含 `BuildManifest.plist` 和对应载荷的 `Restore` 目录。应用包不含 Apple 镜像文件，设备如需重新个性化授权仍可能需要联网。

## 从源码运行

从仓库根目录在 PowerShell 执行以下命令。

```powershell
py -3.14 -m venv .venv-windows
.\.venv-windows\Scripts\python.exe -m pip install -r windows/requirements.txt
.\.venv-windows\Scripts\python.exe windows/console.py
```

启动后会自动预览内置默认轨迹，也可导入 GPX、KML 或 KMZ。导入后点击「默认轨迹」可切回内置路线。GPX 需要完整时间戳才能回放。KML/KMZ 没有时间戳时按输入配速生成时间。回放结束返回轨迹起点。使用完毕后点「恢复真实定位」，再在手机上确认。

## 构建和验收

GitHub Actions 的 **Windows console** 工作流运行轨迹和桥接测试、构建安装包并生成 SHA-256。也可在 Windows 开发机进入 `windows` 目录，使用仓库同一虚拟环境依次执行 `python -m PyInstaller --noconfirm bridge.spec`、`python -m PyInstaller --noconfirm --windowed --paths .. --icon a-console.ico --add-data "a-console.ico:." --name "A Console" console.py` 、`powershell -NoProfile -File stage-uv.ps1` 和 `ISCC.exe Installer.iss`。

发布前先在普通权限下，用干净 Windows 和真实 iPhone 验证打包后的桥接可枚举设备、定位到与真实位置明显不同的测试点、回放样例并恢复真实位置。随后使用最终安装包验证首次配对、开发者镜像准备、三种格式、暂停与跳转、自然结束、两台设备时的目标绑定、拔线后继续或清除、正常退出和强退后的恢复。安装机须没有 Python 和 uv。记录手机型号、iOS 与 Windows 版本，按[双平台发布流程](../docs/desktop-release.md)上传安装包和 SHA-256。当前尚未完成 Windows 真机验收。
