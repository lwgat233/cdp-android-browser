#!/usr/bin/env bash
# 局部构建 + 局部验收（用户定的做法：模块解耦了，改哪儿就只编哪儿、只测哪儿，不整包）
#
# 为什么不是"只打包那一个文件"：APK 是一个整体，没法单独打一个源文件。
# 但"局部"这件事在工程上落成三件真事，比想象中快得多：
#   ① 只做**增量编译**（:app:compileDebugKotlin，几秒到十几秒），不跑 assembleDebug（约 60 秒）
#   ② UI 改动**连编译都不用**：tools/devpush.sh 推到 /sdcard/cdp-dev/，控制口优先读它（改一行 2 秒见效）
#   ③ 只跑**这个功能对应的验收脚本**（工具表 tools/test_map.json），不跑全量
#
# 用法：
#   tools/fastcheck.sh app/src/main/java/dev/cdp/NetTools.kt
#   tools/fastcheck.sh app/src/main/assets/ui/modules/appgrid.js
#   tools/fastcheck.sh --feature apps            # 只按功能 id 找脚本跑
#   tools/fastcheck.sh --publish                 # 发布才做：整包 assembleDebug + 装 + 归档（慢，一天几次）
set -u
cd "$(dirname "$0")/.." || exit 2
export JAVA_HOME=/home/lwgat/tools/jdk-17.0.2
export ANDROID_HOME=/home/lwgat/tools/android-sdk
export PATH=/home/lwgat/.local/bin:/home/lwgat/tools/android-sdk/platform-tools:$PATH

FEATURE=""
PUBLISH=0
FILES=()
while [ $# -gt 0 ]; do
  case "$1" in
    --feature) FEATURE="$2"; shift 2 ;;
    --publish) PUBLISH=1; shift ;;
    *) FILES+=("$1"); shift ;;
  esac
done

KOTLIN=0
UI=0
for f in "${FILES[@]:-}"; do
  case "$f" in
    *.kt|*.java) KOTLIN=1 ;;
    *.js|*.html|*.css) UI=1 ;;
  esac
done

T0=$(date +%s)
echo "== ① 改动范围 =="
echo "   原生改动：$([ $KOTLIN = 1 ] && echo 是 || echo 否)   界面改动：$([ $UI = 1 ] && echo 是 || echo 否)"

if [ $KOTLIN = 1 ]; then
  echo "== ② 增量编译（只编改动过的文件，不打包）=="
  S=$(date +%s)
  ./gradlew --offline --no-daemon :app:compileDebugKotlin \
      -Pkotlin.compiler.execution.strategy=in-process > /tmp/fc_kotlin.log 2>&1 \
    || /home/lwgat/tools/gradle-8.7/bin/gradle --offline --no-daemon :app:compileDebugKotlin \
      -Pkotlin.compiler.execution.strategy=in-process > /tmp/fc_kotlin.log 2>&1
  rc=$?
  echo "   编译 rc=$rc  用时 $(( $(date +%s) - S )) 秒"
  [ $rc != 0 ] && grep -E "^e: |error:" /tmp/fc_kotlin.log | head -8
  [ $rc != 0 ] && exit 1
fi

if [ $UI = 1 ]; then
  echo "== ③ 界面文件语法快查 + 推到设备（不重建 APK）=="
  for f in "${FILES[@]:-}"; do
    case "$f" in
      *.js) node --check "$f" >/dev/null 2>&1 && echo "   语法 OK  $f" || { echo "   语法错  $f"; node --check "$f"; exit 1; } ;;
    esac
  done
  if adb -s emulator-5554 get-state >/dev/null 2>&1; then
    bash tools/devpush.sh "${FILES[@]}" | tail -3
  else
    echo "   设备不在线，跳过推送"
  fi
fi

echo "== ④ 只跑这个功能对应的验收脚本（test_map.json）=="
FEAT_ARG=""
[ -n "$FEATURE" ] && FEAT_ARG="--feature $FEATURE"
if [ ${#FILES[@]} -gt 0 ] && [ -z "$FEATURE" ]; then
  FEAT_ARG="--files ${FILES[*]}"
fi
python3 tools/pick_tests.py $FEAT_ARG || true

echo "== 总用时 $(( $(date +%s) - T0 )) 秒（全量 assembleDebug 约 60 秒 + 全量验收更久）=="

if [ $PUBLISH = 1 ]; then
  echo "== 发布：整包构建 + 安装 + 归档（一天几次，不是每改一行）=="
  /home/lwgat/tools/gradle-8.7/bin/gradle --offline --no-daemon :app:assembleDebug \
      -Pkotlin.compiler.execution.strategy=in-process > /tmp/fc_publish.log 2>&1
  echo "   构建 rc=$?   $(grep -E 'BUILD ' /tmp/fc_publish.log | tail -1)"
  sha256sum app/build/outputs/apk/debug/app-debug.apk
  adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk 2>&1 | tail -1
fi
