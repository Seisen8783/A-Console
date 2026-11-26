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
build_dir="$package_dir/build"
mkdir -p "$build_dir"
stage=$(mktemp -d "$build_dir/.app-stage.XXXXXX")
trap 'rm -rf "$stage"' EXIT
app="$stage/A Console.app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
install -m 755 "$bin_dir/UnRealConsole" "$app/Contents/MacOS/UnRealConsole"
ditto "$bin_dir/UnRealConsole_UnRealConsole.bundle" "$app/Contents/Resources/UnRealConsole_UnRealConsole.bundle"

# Use verified build tools for the app executable's architecture.
uv_version=0.13.10
case "$(lipo -archs "$app/Contents/MacOS/UnRealConsole")" in
    arm64)
        python_arch=aarch64
        uv_target=aarch64-apple-darwin
        uv_sha256=e419bf07533bdbf48795fc793601c834dd79db57a8c49ce8e25cfe3a8639958d
        ;;
    x86_64)
        python_arch=x86_64
        uv_target=x86_64-apple-darwin
        uv_sha256=153e03197588204c04fc4c81cc4bafbfa4280222a385e4ee36a87aeec0ae63b4
        ;;
    *)
        printf '无法识别 macOS 应用的架构。\n' >&2
        exit 1
        ;;
esac
uv_archive="uv-$uv_target.tar.gz"
curl -fsSL --retry 3 "https://github.com/astral-sh/uv/releases/download/$uv_version/$uv_archive" \
    -o "$stage/$uv_archive"
(cd "$stage" && printf '%s  %s\n' "$uv_sha256" "$uv_archive" | shasum -a 256 -c -)
tar -xzf "$stage/$uv_archive" -C "$stage" "uv-$uv_target/uv"
uv="$stage/uv-$uv_target/uv"

# Keep a private uv copy and its licenses available even without a system installation.
install -m 755 "$uv" "$app/Contents/MacOS/uv"
uv_licenses="$app/Contents/Resources/uv-licenses"
mkdir -p "$uv_licenses"
for license in LICENSE-APACHE LICENSE-MIT; do
    curl -fsSL --retry 3 "https://raw.githubusercontent.com/astral-sh/uv/$uv_version/$license" \
        -o "$uv_licenses/$license"
done
codesign --force --sign - "$app/Contents/MacOS/uv"

# Populate the app's own Python installation before signing it.
python_version=3.14.10
macos_minimum=14.0
"$uv" --no-config python install --install-dir "$stage/python" --no-bin "$python_version"
runtime="$app/Contents/Resources/python"
mv "$stage/python/cpython-$python_version-macos-$python_arch-none" "$runtime"
python="$runtime/bin/python3"
MACOSX_DEPLOYMENT_TARGET="$macos_minimum" "$uv" --no-config pip install --python "$python" \
    --python-platform "$uv_target" --no-python-downloads --link-mode copy --break-system-packages --strict \
    --require-hashes --requirements "$package_dir/bridge-requirements.txt"

# Build the signed app with the current brand icon.
icon_source="$repo_dir/tools/icon"
iconset="$icon_source/build/AConsole.iconset"
mkdir -p "$iconset"
swift "$icon_source/make_icon.swift" "$iconset"
iconutil -c icns "$iconset" -o "$app/Contents/Resources/AConsole.icns"

# Compile an explicit dark appearance on Xcode versions with Icon Composer support.
icon_name=''
if xcrun --find ictool >/dev/null 2>&1; then
    composer="$icon_source/build/AConsole.icon"
    composer_output="$icon_source/build/composer"
    mkdir -p "$composer/Assets" "$composer_output"
    cp "$icon_source/AConsole.icon/icon.json" "$composer/icon.json"
    cp "$iconset/icon_512x512@2x.png" "$composer/Assets/art.png"
    xcrun actool "$composer" --compile "$composer_output" --platform macosx \
        --minimum-deployment-target 14 --app-icon AConsole \
        --output-partial-info-plist "$composer_output/icons.plist"
    cp "$composer_output/Assets.car" "$app/Contents/Resources/Assets.car"
    icon_name='<key>CFBundleIconName</key><string>AConsole</string>'
else
    optional_catalog="$app/Contents/Resources/Assets.car"
    if [ -f "$optional_catalog" ]; then unlink "$optional_catalog"; fi
fi

# 版本号随资源打进 bundle，界面读的就是它。
printf '%s\n' "$current_version" > "$app/Contents/Resources/VERSION"

cat > "$app/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key><string>A Console</string>
  <key>CFBundleDisplayName</key><string>A Console</string>
  <key>CFBundleIdentifier</key><string>com.unreal.console</string>
  <key>CFBundleExecutable</key><string>UnRealConsole</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>$current_version</string>
  <key>CFBundleVersion</key><string>$next_patch</string>
  <key>CFBundleIconFile</key><string>AConsole</string>
  $icon_name
  <key>LSMinimumSystemVersion</key><string>$macos_minimum</string>
  <key>NSHighResolutionCapable</key><true/>
</dict>
</plist>
PLIST

codesign --force --sign - "$app"
codesign --verify --strict "$app"
install -m 755 "$uv" "$build_dir/uv"
final_app="$build_dir/A Console.app"
rm -rf "$final_app"
mv "$app" "$final_app"
printf '%s\n' "$final_app"
