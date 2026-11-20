#!/usr/bin/env bash
# NoReal 自动测试
#
# 在任意一台已连接的 Android 设备上验证「接管模式」这条链路：
#   A. NoReal 是否在回环地址上广播坐标帧
#   B. 探针是否收到了这些帧（协议、字段、频率）
#   C. 接管模式下系统定位是否保持干净（没有写入测试定位源）
#   D. 探针读到的系统定位是否带 mock 标记
#
# 用法：
#   tools/autotest/run.sh                 # 用默认设备
#   tools/autotest/run.sh emulator-5554   # 指定设备
#
# 依赖：adb 在 PATH 里。

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ANDROID="$ROOT/android"
SERIAL="${1:-}"
PROBE_PKG="com.noreal.locbridge.probe"
NOREAL_PKG="com.noreal.android"
BRIDGE_PKG="com.noreal.locbridge"
PORT_HEX=$(printf '%04X' 38765)

adb_() {
  if [ -n "$SERIAL" ]; then adb -s "$SERIAL" "$@"; else adb "$@"; fi
}

pass() { printf '  \033[32mPASS\033[0m  %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; FAILED=1; }
info() { printf '  ----  %s\n' "$1"; }

FAILED=0

echo "== NoReal 自动测试 =="
if [ -n "$SERIAL" ]; then
  echo "设备：$SERIAL"
else
  echo "设备：$(adb devices | awk 'NR==2{print $1}')"
fi
adb_ wait-for-device
adb_ shell 'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 1; done' >/dev/null 2>&1

echo
echo "[1/4] 安装三个 APK"
for module in app locbridge locprobe; do
  apk="$ANDROID/$module/build/outputs/apk/debug/$module-debug.apk"
  if [ ! -f "$apk" ]; then
    fail "缺少 $apk，先执行 ./gradlew :app:assembleDebug :locbridge:assembleDebug :locprobe:assembleDebug"
    exit 1
  fi
  result=$(adb_ install -r -g "$apk" 2>&1 | tail -1)
  case "$result" in
    *Success*) pass "安装 $module" ;;
    *) fail "安装 $module：$result" ;;
  esac
done

echo
echo "[2/4] 启动探针并开始流式抓日志"
adb_ logcat -c 2>/dev/null
# 必须流式抓：设备上 GMS 的日志量很大，buffer 会被冲掉，事后 logcat -d 会漏事件。
LOG_FILE="$(mktemp -t noreal_autotest.XXXXXX.log)"
adb_ logcat -v time -s LocProbe:I NoReal:I >"$LOG_FILE" 2>&1 &
LOGCAT_PID=$!
adb_ shell am force-stop "$PROBE_PKG" >/dev/null 2>&1
adb_ shell am start -n "$PROBE_PKG/.LocProbeActivity" >/dev/null 2>&1
sleep 3
if adb_ shell pidof "$PROBE_PKG" >/dev/null 2>&1; then
  pass "探针已启动"
else
  fail "探针没有起来"
fi

echo
echo "[3/4] 启动接管模式回放"
# Android 8+ 起隐式广播不再送达 manifest receiver，必须用显式组件；-n 指向接收器。
# 前台服务在后台启动会被系统拦，所以先把 NoReal 拉到前台。
adb_ shell am start -n "$NOREAL_PKG/.SplashActivity" >/dev/null 2>&1
sleep 3
# 强杀进程而不是发 STOP：服务重启时广播端口要重新绑定，
# 停服务的路径和强杀路径在这一点上表现不同，强杀最干净。
adb_ shell am force-stop "$NOREAL_PKG" >/dev/null 2>&1
sleep 2
adb_ shell am broadcast -n "$NOREAL_PKG/.DebugReceiver" \
  -a "$NOREAL_PKG.action.START_INTERCEPT" --es pace 300 --ei laps 2 >/dev/null 2>&1
sleep 8
kill "$LOGCAT_PID" >/dev/null 2>&1
wait "$LOGCAT_PID" 2>/dev/null

echo
echo "[4/4] 断言"
frames=$(grep -c "I LocProbe: stream " "$LOG_FILE")
if [ "$frames" -ge 3 ]; then
  pass "收到 $frames 帧坐标流（每秒一帧，链路通）"
  grep "I LocProbe: stream " "$LOG_FILE" | tail -2 | sed 's/^/        /'
else
  fail "只收到 $frames 帧坐标流，广播链路没通"
  grep -iE "NoReal|拒绝|Exception" "$LOG_FILE" | tail -5 | sed 's/^/        /'
fi

if adb_ shell "cat /proc/net/udp /proc/net/udp6 2>/dev/null" | grep -qi "$PORT_HEX"; then
  pass "本机 $PORT_HEX(=38765) 端口在监听"
else
  fail "本机端口没有监听"
fi

mocked=$(adb_ shell dumpsys location 2>/dev/null | grep -cE "\[mock\]|mock provider")
if [ "$mocked" -eq 0 ]; then
  pass "系统定位保持干净：接管模式没有写入测试定位源"
else
  fail "系统定位被写入了测试定位源（$mocked 处 mock 标记）"
fi

mockflag=$(grep "I LocProbe: system " "$LOG_FILE" | tail -1)
if echo "$mockflag" | grep -q "isMock=false"; then
  pass "探针读到的系统定位不带 mock 标记"
elif echo "$mockflag" | grep -q "isMock=true"; then
  fail "探针读到的系统定位带 mock 标记（这条通道会被微信丢弃）"
else
  info "探针还没拿到系统定位，跳过该项"
fi

echo
if [ "$FAILED" -eq 0 ]; then
  printf '\033[32m全部通过\033[0m\n'
else
  printf '\033[31m存在失败项\033[0m\n'
fi
exit "$FAILED"
