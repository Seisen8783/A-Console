# 发布桌面安装包

Windows 和 macOS 的每个安装包版本都通过同一个命令发布到 GitHub Releases 和 Gitee 的 `installers/`。在发布机准备 Python 3、已登录的 GitHub CLI（`gh auth login`）、可以推送 Gitee 仓库的 SSH 配置及 Git 提交身份。

1. Windows 使用 **Windows console** 工作流构建的 EXE。macOS 使用 `./macos/build-dmg.sh` 生成的 DMG。完成对应平台的发布验证。
2. 在 GitHub 为实际构建的源码提交创建并推送版本标签。Windows 使用 `windows-v<版本>`，macOS 使用 `macos-v<版本>`。
3. 从仓库根目录运行对应的命令，替换版本号和安装包路径。

   ```bash
   python3 tools/publish_installer.py windows-v0.1.1 /path/to/A-Console-0.1.1-Windows-x64.exe
   python3 tools/publish_installer.py macos-v2.0.43 /path/to/A-Console-2.0.43-macOS.dmg
   ```

   版本已有 GitHub Release 时，可以省略本地安装包路径。脚本会下载对应的 Release 附件并复用现有发布说明。新建的 GitHub Release 默认为预发布。
4. 使用脚本输出的 GitHub 和 Gitee 安装包直链更新首页的下载链接和版本号。Gitee 的安装包直链需要登录，免登录 ZIP 链接会从 `main` 提供最新归档。

遇到网络或并发推送失败，可以用同一个版本和安装包重跑。若同名附件的校验值不同，使用新的版本号发布改过的安装包。
