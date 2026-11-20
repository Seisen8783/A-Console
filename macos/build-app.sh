#!/usr/bin/env bash
set -euo pipefail

package_dir=$(cd "$(dirname "$0")" && pwd)
swift build --package-path "$package_dir" --configuration release
bin_dir=$(swift build --package-path "$package_dir" --configuration release --show-bin-path)
app="$package_dir/build/NoReal Console.app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
install -m 755 "$bin_dir/NoRealConsole" "$app/Contents/MacOS/NoRealConsole"
ditto "$bin_dir/NoRealConsole_NoRealConsole.bundle" "$app/Contents/Resources/NoRealConsole_NoRealConsole.bundle"

cat > "$app/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key><string>NoReal Console</string>
  <key>CFBundleDisplayName</key><string>NoReal 控制台</string>
  <key>CFBundleIdentifier</key><string>com.noreal.console</string>
  <key>CFBundleExecutable</key><string>NoRealConsole</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>0.2.0</string>
  <key>CFBundleVersion</key><string>2</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>NSHighResolutionCapable</key><true/>
</dict>
</plist>
PLIST

codesign --force --sign - "$app"
codesign --verify --strict "$app"
printf '%s\n' "$app"
