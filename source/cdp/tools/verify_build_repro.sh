#!/usr/bin/env bash
# 构件可复现性：把源码快照干净拷一份 → 补 local.properties → 构建 → 与交付构件逐条比对 zip 条目
#
# 判据：**zip 条目的 名称/CRC/大小 逐条一致**。
# 整体 sha256 不可能一样（打包层带签名与时间戳），所以不比整体哈希。
#
# 用法：bash tools/verify_build_repro.sh [交付构件路径]
set -uo pipefail

export JAVA_HOME=${JAVA_HOME:-/home/lwgat/tools/jdk-17.0.2}
export ANDROID_HOME=${ANDROID_HOME:-/vol1/1000/aicache/tools/android-sdk}
export PATH="$JAVA_HOME/bin:/home/lwgat/tools/gradle-8.7/bin:$ANDROID_HOME/platform-tools:$PATH"

SRC="$(cd "$(dirname "$0")/.." && pwd)"
SHIPPED="${1:-/vol1/1000/airesults/cdp/apk/cdp-debug.apk}"
TMP=/vol1/1000/aicache/buildtmp/cdp-repro
LOG=$TMP/build.log

echo "== 1/5 腾内存：构建与模拟器不并行 =="
docker rm -f notifbridge-emu >/dev/null 2>&1
sleep 2
free -m | awk '/^Mem:/{print "   可用 "$7"MB"}'

echo "== 2/5 干净拷贝源码快照（排除构建产物）=="
rm -rf "$TMP"; mkdir -p "$TMP"
tar -C "$SRC" --exclude='./app/build' --exclude='./build' --exclude='./.gradle' \
    --exclude='./local.properties' -cf - . | tar -C "$TMP" -xf -
echo "   快照文件数: $(find "$TMP" -type f | wc -l)"

echo "== 3/5 补本机路径配置并构建 =="
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$TMP/local.properties"
( cd "$TMP" && gradle --no-daemon --offline :app:assembleDebug \
    -Pkotlin.compiler.execution.strategy=in-process ) > "$LOG" 2>&1
RC=$?
echo "   gradle 退出码=$RC"
if [ "$RC" != "0" ]; then
  echo "   构建失败，下面是要点："; grep -E '^e: |FAILURE|error:' "$LOG" | head -10
  exit 2
fi
NEW="$TMP/app/build/outputs/apk/debug/app-debug.apk"
echo "   新构建: $(stat -c%s "$NEW") 字节"
echo "   交付件: $(stat -c%s "$SHIPPED") 字节"

echo "== 4/5 逐条比对 zip 条目（名称/CRC/大小）=="
entries() { unzip -v "$1" | awk 'NR>3 && NF>=8 {print $8, $7, $1}' | sort; }
entries "$NEW" > "$TMP/new.txt"
entries "$SHIPPED" > "$TMP/shipped.txt"
COUNT=$(wc -l < "$TMP/shipped.txt")
DIFF=$(diff "$TMP/new.txt" "$TMP/shipped.txt" | wc -l)
echo "   条目数: 新构建 $(wc -l < "$TMP/new.txt")，交付件 $COUNT，差异行 $DIFF"
if [ "$DIFF" = "0" ]; then
  echo "   PASS 逐条一致：$COUNT 个条目名称/CRC/大小全同 → 源码快照能干净复现这个构件"
else
  echo "   FAIL 有差异，前 20 行："; diff "$TMP/new.txt" "$TMP/shipped.txt" | head -20
fi

echo "== 5/5 收尾 =="
rm -rf "$TMP/app/build" "$TMP/build" "$TMP/.gradle"
echo "   临时构建产物已清（保留源码副本 $TMP 供人工复核）"
[ "$DIFF" = "0" ]
