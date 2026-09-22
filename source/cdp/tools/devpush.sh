#!/usr/bin/env bash
# 把改动过的界面文件推到设备 /sdcard/cdp-dev/，控制口会优先读它 —— **不用重建 APK**。
# 用 X-CDP-Source 头能证明"这次跑的是推上去那份"（=dev）还是 APK 里的（=apk）。
# 用法：
#   tools/devpush.sh                                  # 推整个 ui 目录
#   tools/devpush.sh app/src/main/assets/ui/modules/appgrid.js   # 只推改动的那几个文件
#   tools/devpush.sh --clear                          # 撤掉覆盖层，回到读 APK
set -u
cd "$(dirname "$0")/.." || exit 2
export PATH=/home/lwgat/.local/bin:/home/lwgat/tools/android-sdk/platform-tools:$PATH
ADB="adb -s emulator-5554"
# 覆盖层目录**从接口读**（应用自己的外部目录；写死路径踩过：/sdcard/cdp-dev 分区存储读不到）
DEV=$(curl -s --max-time 10 http://127.0.0.1:8848/api/dev/assets 2>/dev/null | python3 -c "import sys,json;print(json.load(sys.stdin).get('dir','/sdcard/cdp-dev'))" 2>/dev/null)
[ -z "$DEV" ] && DEV=/sdcard/Android/data/dev.cdp/files/cdp-dev

if [ "${1:-}" = "--clear" ]; then
  $ADB shell rm -rf $DEV
  echo "覆盖层已撤（回到读 APK 里的 ui）"
  exit 0
fi

$ADB shell mkdir -p $DEV/ui/modules >/dev/null 2>&1
if [ $# -eq 0 ]; then
  $ADB push app/src/main/assets/ui/. $DEV/ui/ >/dev/null && echo "整目录已推：$DEV/ui"
else
  n=0
  for f in "$@"; do
    case "$f" in
      *assets/ui/*)
        rel="${f#*assets/ui/}"
        mkdir -p "$(dirname "/tmp/devstage/ui/$rel")"
        cp "$f" "/tmp/devstage/ui/$rel" 2>/dev/null || { mkdir -p "/tmp/devstage/ui/$(dirname "$rel")"; cp "$f" "/tmp/devstage/ui/$rel"; }
        $ADB push "/tmp/devstage/ui/$rel" "$DEV/ui/$rel" >/dev/null && { echo "已推 $rel"; n=$((n+1)); }
        ;;
      *) echo "跳过（不在 ui 目录里）：$f" ;;
    esac
  done
  echo "共推 $n 个文件到 $DEV/ui（无需重建 APK）"
fi

# 推完必须让控制台页面**重载**一次：页面里已经加载的 JS/HTML 不会自己变（漏这步的坑踩过：
# 推了 index.html 却看不到新按键）。这里用 CDP 让那个页面自己 location.reload()。
node tools/reload_console.mjs >/dev/null 2>&1 && echo "已让控制台页面重载（新文件生效）" || echo "（控制台页面没重载成，手工 reload 一下）"
