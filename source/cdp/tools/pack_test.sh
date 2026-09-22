#!/usr/bin/env bash
# 打一个"专门测试某个功能"的 APK，放进项目里的固定文件夹，并写明：项目名 / 测试版本 / 测试的功能。
#
# 用户的要求（2026-09-21）：这种专门测某个功能的 APK，放在当前项目文件的特定文件夹里，
# 并给出项目名、测试版本、测试的功能这些说明。
#
# 产物：
#   apk/测试版/<项目名>-<功能>-<版本>-<日期>.apk
#   apk/测试版/<同名>.txt            ← 说明书（项目名/测试版本/测试的功能/构件哈希/怎么装怎么跑/该跑哪些验收）
#   app/src/main/assets/build-info.json  ← 打进 APK 里，界面与验收都能读到"这是哪个测试版"
#
# 用法：
#   tools/pack_test.sh 首页小app网格            # 功能名（中文可以）
#   tools/pack_test.sh 首页小app网格 --id apps  # 顺便绑定功能 id（用来挑验收脚本）
#   tools/pack_test.sh 樱花动漫嗅探 --no-install
set -u
cd "$(dirname "$0")/.." || exit 2
export JAVA_HOME=/home/lwgat/tools/jdk-17.0.2
export ANDROID_HOME=/home/lwgat/tools/android-sdk
export PATH=/home/lwgat/.local/bin:/home/lwgat/tools/android-sdk/platform-tools:$PATH

FEATURE=""
FEAT_ID=""
INSTALL=1
while [ $# -gt 0 ]; do
  case "$1" in
    --id) FEAT_ID="$2"; shift 2 ;;
    --no-install) INSTALL=0; shift ;;
    *) FEATURE="$1"; shift ;;
  esac
done
[ -z "$FEATURE" ] && { echo "用法：tools/pack_test.sh <要测的功能名> [--id 功能id] [--no-install]"; exit 2; }

PROJECT="cdp"
DATE=$(date +%Y%m%d-%H%M)
VERSION="测试-${DATE}-01"
[ -d "../../apk/测试版" ] && VERSION="测试-${DATE}-$(printf '%02d' $(( $(ls ../../apk/测试版/*.apk 2>/dev/null | wc -l) + 1 )))"
OUT_DIR="../../apk/测试版"
mkdir -p "$OUT_DIR"
SAFE=$(echo "$FEATURE" | tr ' /' '__')
BASE="${PROJECT}-${SAFE}-${VERSION}"

# 把"这是哪个测试版"写进构件里（界面/验收都能读到）
python3 - "$PROJECT" "$VERSION" "$FEATURE" "$FEAT_ID" <<'PY'
import json, os, subprocess, sys, time
proj, ver, feat, fid = sys.argv[1:5]
info = {"project": proj, "testVersion": ver, "feature": feat, "featureId": fid,
        "builtAt": time.strftime("%Y-%m-%d %H:%M:%S"),
        "tool": "tools/pack_test.sh",
        "note": "这是专门测试上面这个功能的测试版构件"}
os.makedirs("app/src/main/assets", exist_ok=True)
open("app/src/main/assets/build-info.json", "w").write(json.dumps(info, ensure_ascii=False, indent=1))
print("已写入 app/src/main/assets/build-info.json：", json.dumps(info, ensure_ascii=False))
PY

echo "== 构建测试版 APK =="
/home/lwgat/tools/gradle-8.7/bin/gradle --offline --no-daemon :app:assembleDebug \
    -Pkotlin.compiler.execution.strategy=in-process > /tmp/pack_test.log 2>&1
rc=$?
grep -E "^e: |BUILD " /tmp/pack_test.log | head -8
[ $rc != 0 ] && { echo "构建失败，没产物"; exit 1; }

SRC_APK="app/build/outputs/apk/debug/app-debug.apk"
DEST="$OUT_DIR/${BASE}.apk"
cp "$SRC_APK" "$DEST"
# 主归档也同步成同一份：不然下一次环境体检会报"构建产物与归档不一致"（这个缝踩过）
cp "$DEST" "../../apk/cdp-debug.apk"
SHA=$(sha256sum "$DEST" | awk '{print $1}')
SIZE=$(stat -c%s "$DEST")

# 这个功能该跑哪些验收（test_map）
TESTS="（没绑定功能 id，未挑脚本）"
if [ -n "$FEAT_ID" ]; then
  TESTS=$(python3 tools/pick_tests.py --feature "$FEAT_ID" 2>/dev/null | grep "要跑" || echo "（这个功能还没配测试）")
fi

cat > "$OUT_DIR/${BASE}.txt" <<TXT
项目名　　：${PROJECT}（安卓 WebView 浏览器 / 控制台）
测试版本　：${VERSION}
测试的功能：${FEATURE}$([ -n "$FEAT_ID" ] && echo "（功能 id：${FEAT_ID}）")
打包时间　：$(date '+%Y-%m-%d %H:%M:%S')
构件哈希　：sha256 ${SHA}
构件大小　：${SIZE} 字节
APK 路径　：$(cd "$(dirname "$DEST")" && pwd)/$(basename "$DEST")
源码快照　：source/cdp（app/src/main/assets/build-info.json 里写着同一个测试版本，装上去能对得上）
包名/入口 ：dev.cdp / dev.cdp.MainActivity

怎么装：
  adb install -r ${BASE}.apk
怎么确认装的是这一版：
  curl -s http://127.0.0.1:8848/api/status          # 看版本与 build-info
  adb shell 'cat /data/data/dev.cdp/files/... '     # 或界面「介绍 ▸ 版本」
该跑哪些验收：
  ${TESTS}
  （按用户要求：只测这个功能，不跑全量；先 tools/env_health.py 确认环境 READY）

说明：
  这是"只测一个功能"的测试版构件，放在 apk/测试版/ 下，文件名自带功能与版本；
  同一份说明书与本 APK 一一对应（哈希可核）。
TXT

echo "== 产物 =="
echo "  APK ：$DEST"
echo "  说明：$OUT_DIR/${BASE}.txt"
ls -la "$OUT_DIR" | tail -4

if [ $INSTALL = 1 ]; then
  echo "== 装到设备并启动 =="
  adb -s emulator-5554 install -r "$DEST" 2>&1 | tail -1
  adb -s emulator-5554 shell am start -n dev.cdp/.MainActivity >/dev/null 2>&1
  sleep 6
  PID=$(adb -s emulator-5554 shell pidof dev.cdp | tr -d '\r')
  adb -s emulator-5554 forward --remove-all >/dev/null
  adb -s emulator-5554 forward tcp:9222 localabstract:webview_devtools_remote_${PID} >/dev/null
  adb -s emulator-5554 forward tcp:8848 tcp:8848 >/dev/null
  echo "  已启动（pid ${PID}），端口转发已重建"
fi
