#!/usr/bin/env bash
# 把 NoReal 的轨迹注入到 Android 模拟器的 GPS 硬件通道
#
# 为什么走这条路：模拟器的 geo fix 注入是「虚拟 GNSS 硬件」，
# 系统把它当真实卫星定位上报，坐标对象上不带 isMock 标记——
# 这是实测验证过的（探针读到 isMock=false）。
# 微信、腾讯位置服务会检查这个标记，带标记的坐标会被静默丢弃。
#
# 链路：NoReal 轨迹引擎 → 回环 UDP → 探针订阅 → logcat → 本脚本 → adb emu geo fix → 模拟器 GPS
#
# 用法：
#   tools/emu-track.sh emulator-5554        # 跟随探针推送
#   tools/emu-track.sh emulator-5554 --demo # 不跟探针，直接用固定演示轨迹

set -uo pipefail

SERIAL="${1:-emulator-5554}"
MODE="${2:-follow}"
ADB="adb -s $SERIAL"

if ! $ADB get-state >/dev/null 2>&1; then
  echo "设备 $SERIAL 不可用" >&2
  exit 1
fi

if [ "$MODE" = "--demo" ]; then
  # 北京天安门 → 上海人民广场 → 杭州西湖，单点轮转，30 秒一圈
  points=("116.397400 39.908700" "121.473700 31.230400" "120.155000 30.274100")
  echo "演示模式：每 2 秒推一个点"
  i=0
  while true; do
    set -- ${points[$((i % ${#points[@]}))]}
    $ADB emu geo fix "$1" "$2" >/dev/null
    i=$((i + 1))
    sleep 2
  done
fi

echo "跟随探针的坐标流推送（Ctrl-C 停止）"
TMP=$(mktemp -t emutrack.XXXXXX)
trap 'rm -f "$TMP"; pkill -P $$ logcat 2>/dev/null; exit 0' INT TERM

# logcat 落到文件再 tail -f：循环里直接跑 adb 会抢 stdin 把日志流卡住。
$ADB logcat -v brief -s LocProbe:I >"$TMP" 2>&1 &
LOGCAT_PID=$!

tail -f "$TMP" | while IFS= read -r line; do
  case "$line" in
    *"stream lat="*)
      payload="${line#*stream }"
      lon=$(printf '%s' "$payload" | sed -n 's/.*lon=\([-0-9.]*\).*/\1/p')
      lat=$(printf '%s' "$payload" | sed -n 's/.*lat=\([-0-9.]*\).*/\1/p')
      if [ -n "$lon" ] && [ -n "$lat" ]; then
        if $ADB emu geo fix "$lon" "$lat" </dev/null >/dev/null 2>&1; then
          printf '[%s] geo fix %s %s\n' "$(date +%H:%M:%S)" "$lon" "$lat"
        fi
      fi
      ;;
  esac
done
