# A Console Windows

目标平台为原生 Windows 11 x64 和支持 USB 开发者服务的 iOS 17.4 或以上版本。无需 Xcode。安装 Apple 官网的 Windows 版 iTunes 以获得 Apple Mobile Device Service。通过数据线连接 iPhone，解锁、信任电脑，并在「设置 → 隐私与安全性」启用开发者模式。若没有开关，可以先用上游的 [reveal-developer-mode](https://doronz88.github.io/pymobiledevice3/latest/cli/amfi/) 显示开关。首次准备设备开发者镜像可能需要联网。

## 从源码运行

从仓库根目录在 PowerShell 执行以下命令。

```powershell
py -3.14 -m venv .venv-windows
.\.venv-windows\Scripts\python.exe -m pip install -r windows/requirements.txt
.\.venv-windows\Scripts\python.exe windows/console.py
```

导入 GPX、KML 或 KMZ 后，可在离线画布查看轨迹并选择 USB 设备。GPX 需要完整时间戳才能回放。KML/KMZ 没有时间戳时按输入配速生成时间。回放结束返回轨迹起点。使用完毕后点「恢复真实定位」，再在手机上确认。

## 构建和验收

GitHub Actions 的 **Windows console** 工作流运行轨迹和桥接测试、构建安装包并生成 SHA-256。也可在 Windows 开发机进入 `windows` 目录，使用仓库同一虚拟环境依次执行 `python -m PyInstaller --noconfirm bridge.spec`、`python -m PyInstaller --noconfirm --windowed --paths .. --icon a-console.ico --add-data "a-console.ico:." --name "A Console" console.py` 和 `ISCC.exe Installer.iss`。

发布前先在普通权限下，用干净 Windows 和真实 iPhone 验证打包后的桥接可枚举设备、定位到与真实位置明显不同的测试点、回放样例并恢复真实位置。随后使用最终安装包验证首次配对、开发者镜像准备、三种格式、暂停与跳转、自然结束、两台设备时的目标绑定、拔线后继续或清除、正常退出和强退后的恢复。安装机须没有 Python 和 uv。记录手机型号、iOS 与 Windows 版本，再将安装包和 SHA-256 上传 Release。当前尚未完成 Windows 真机验收。
