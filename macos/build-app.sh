#!/usr/bin/env bash
set -euo pipefail

package_dir=$(cd "$(dirname "$0")" && pwd)
repo_dir=$(cd "$package_dir/.." && pwd)
version_file="$repo_dir/VERSION"

# 版本号：每次构建 +0.0.01，写回仓库的 VERSION 文件。
# 脚本放在仓库根的 tools/ 下也能找到同一个 VERSION 文件。
if [ ! -f "$version_file" ]; then
    printf '2.0.13\n' > "$version_file"
fi

current_version=$(tr -d '[:space:]' < "$version_file")
if [[ ! "$current_version" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
    printf 'VERSION 文件格式不对：%s\n' "$current_version" >&2
    exit 1
fi
major="${BASH_REMATCH[1]}"
minor="${BASH_REMATCH[2]}"
patch="${BASH_REMATCH[3]}"
next_patch=$((patch + 1))
next_version="$major.$minor.$next_patch"
printf '%s\n' "$next_version" > "$version_file"
printf '版本号 %s → %s\n' "$current_version" "$next_version" >&2

# SwiftPM 自带的沙箱在受限终端（CI、容器、代理会话）里会因 sandbox_apply 失败。
# 先按默认方式构建，失败则退回无沙箱模式。
if ! swift build --package-path "$package_dir" --configuration release 2>/tmp/unreal-build.log; then
    if grep -q "sandbox_apply\|Operation not permitted" /tmp/unreal-build.log; then
        printf 'SwiftPM 沙箱不可用，改用 --disable-sandbox 重试。\n' >&2
        swift build --package-path "$package_dir" --configuration release --disable-sandbox
    else
        cat /tmp/unreal-build.log >&2
        exit 1
    fi
fi
bin_dir=$(swift build --package-path "$package_dir" --configuration release --disable-sandbox --show-bin-path)
app="$package_dir/build/UnReal Console.app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
install -m 755 "$bin_dir/UnRealConsole" "$app/Contents/MacOS/UnRealConsole"
ditto "$bin_dir/UnRealConsole_UnRealConsole.bundle" "$app/Contents/Resources/UnRealConsole_UnRealConsole.bundle"

# 应用图标：白底黑色定位针。缺失或生成失败时不阻断构建。
icon_source="$repo_dir/tools/icon"
icns="$app/Contents/Resources/UnRealConsole.icns"
if [ -f "$icon_source/make_icon.swift" ]; then
    icon_work="$icon_source/build"
    if swift "$icon_source/make_icon.swift" "$icon_work" >/dev/null 2>&1; then
        iconset="$icon_work/UnRealConsole.iconset"
        rm -rf "$iconset"
        mkdir -p "$iconset"
        cp "$icon_work"/*.png "$iconset/"
        if iconutil -c icns "$iconset" -o "$icns" 2>/dev/null; then
            printf '图标已更新\n' >&2
        else
            printf '图标合成失败，跳过。\n' >&2
        fi
    else
        printf '图标生成失败，跳过。\n' >&2
    fi
fi

# 版本号随资源打进 bundle，界面读的就是它。
printf '%s\n' "$current_version" > "$app/Contents/Resources/VERSION"

cat > "$app/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key><string>UnReal Console</string>
  <key>CFBundleDisplayName</key><string>UnReal Console</string>
  <key>CFBundleIdentifier</key><string>com.unreal.console</string>
  <key>CFBundleExecutable</key><string>UnRealConsole</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>$current_version</string>
  <key>CFBundleVersion</key><string>$next_patch</string>
  <key>CFBundleIconFile</key><string>UnRealConsole</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>NSHighResolutionCapable</key><true/>
</dict>
</plist>
PLIST

codesign --force --sign - "$app"
codesign --verify --strict "$app"
printf '%s\n' "$app"
