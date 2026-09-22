#!/usr/bin/env bash
# 构建 → 装包 → 跑全部验收。宿主内存小，重活按内存水位串行：
#   可用内存 < 2500MB 就先停模拟器腾出来，构建完再起（模拟器单体约 3GB 且没有 cgroup 上限）
set -uo pipefail
export JAVA_HOME=/home/lwgat/tools/jdk-17.0.2
export ANDROID_HOME=/home/lwgat/tools/android-sdk
export PATH=$JAVA_HOME/bin:/home/lwgat/tools/gradle-8.7/bin:$ANDROID_HOME/platform-tools:$PATH
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# 本机路径配置不入档：缺 local.properties 时自己补一个（Android SDK 位置）
if [ ! -f "$ROOT/local.properties" ]; then
  echo "sdk.dir=${ANDROID_HOME:-/vol1/1000/aicache/tools/android-sdk}" > "$ROOT/local.properties"
  echo "已补 local.properties（本机路径配置，不入档）"
fi

avail() { free -m | awk '/^Mem:/{print $7}'; }

echo "===== 1/6 内存水位 $(avail)MB ====="
STOPPED=0
# 两个条件任一成立就重建：内存不够（构建与模拟器不能并行），或者压根没有设备（模拟器没在跑）
if [ "$(avail)" -lt 2500 ] || ! adb devices 2>/dev/null | grep -q emulator-5554; then
  if [ "$(avail)" -lt 2500 ]; then echo "内存不够，先停模拟器腾地方"; else echo "没有在线设备，准备起模拟器"; fi
  docker rm -f notifbridge-emu >/dev/null 2>&1
  STOPPED=1
  sleep 3
  echo "腾出来之后：$(avail)MB"
else
  echo "内存够用且设备在线，模拟器保持运行"
fi

echo "===== 2/6 构建（低堆单 JVM）====="
gradle --no-daemon --offline :app:assembleDebug -Pkotlin.compiler.execution.strategy=in-process > /tmp/cdp_build.log 2>&1
BUILD_RC=$?
grep -E '^e:|BUILD ' /tmp/cdp_build.log | head -20
if [ "$BUILD_RC" != "0" ]; then
  echo "构建失败（gradle 退出码 $BUILD_RC）→ 中止，绝不拿上一轮的旧包去测"
  grep -E '^e: ' /tmp/cdp_build.log | head -10
  exit 1
fi
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "构建没产物，中止"; exit 1; }
ls -l "$APK"; sha256sum "$APK"

if [ "$STOPPED" = "1" ]; then
  echo "===== 3/6 起模拟器 ====="
  bash /vol1/1000/aicache/docker/emu.sh start 2>&1 | tail -3
fi

echo "===== 4/6 装包并启动 ====="
adb -s emulator-5554 install -r "$APK" 2>&1 | tail -2
# install -r 本身就会结束旧进程，不用再补一次 force-stop（少一次启动抖动）
adb -s emulator-5554 shell am start -n dev.cdp/.MainActivity >/dev/null 2>&1
sleep 6
PID=$(adb -s emulator-5554 shell pidof dev.cdp | tr -d '\r')
echo "PID=$PID"
adb -s emulator-5554 forward tcp:9222 localabstract:webview_devtools_remote_$PID >/dev/null
adb -s emulator-5554 forward tcp:8848 tcp:8848 >/dev/null
# 等 App 的控制口 + 页面都起来再动手（别用固定 sleep 赌）
for i in $(seq 1 40); do
  U=$(timeout 15 curl -s --max-time 12 "http://127.0.0.1:8848/api/status" | python3 -c 'import sys,json;print(json.load(sys.stdin)["browser"]["url"])' 2>/dev/null)
  if [ -n "$U" ]; then echo "App 就绪（第 $i 次探测，当前页 $U）"; break; fi
  sleep 2
done

echo "===== 5/5 跑全部验收组（输出同时存证到 evidence/）====="
bash tools/verify_all.sh
RC=$?
echo
echo "验收汇总：RC=$RC（0=七组全过）"
echo "内存收尾：$(avail)MB 可用"
exit $RC
