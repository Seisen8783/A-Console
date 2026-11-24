#!/usr/bin/env bash
set -euo pipefail

macos_dir=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$macos_dir/.." && pwd)
"$macos_dir/build-app.sh"
app="$macos_dir/build/A Console.app"
version=$(/usr/libexec/PlistBuddy -c 'Print CFBundleShortVersionString' "$app/Contents/Info.plist")
stage=$(mktemp -d -t a-console-dmg)
link="$stage/GitHub Repository.webloc"
cat > "$link" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "https://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>URL</key><string>https://github.com/Seisen8783/A-Console</string>
</dict></plist>
PLIST
swift "$root/tools/icon/set_repository_icon.swift" "$link"
mkdir -p "$root/dist"
dmg="$root/dist/A-Console-$version-macOS.dmg"
uvx --from dmgbuild==1.6.7 dmgbuild -s "$macos_dir/dmg-settings.py" \
    -D "app=$app" -D "repository=$link" "A Console $version" "$dmg"
hdiutil verify "$dmg"
printf '%s\n' "$dmg"
