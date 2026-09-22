#!/usr/bin/env bash
# 分段跑第七组 + 回归第一～六组（长跑会把模拟器跑崩，所以每段之间重启模拟器；断言完全一样）
set -uo pipefail
cd /vol1/1000/airesults/cdp/source/cdp
unset ANDROID_SDK_ROOT
export JAVA_HOME=/home/lwgat/tools/jdk-17.0.2
export ANDROID_HOME=/vol1/1000/aicache/tools/android-sdk
export PATH=$JAVA_HOME/bin:/home/lwgat/tools/gradle-8.7/bin:$ANDROID_HOME/platform-tools:$HOME/.local/bin:$PATH
# 渲染后端：swiftshader_indirect 控制台浮层才起得来（-gpu off 时控制台 WebView 建不出来）
export CDP_GPU="${CDP_GPU:-swiftshader_indirect}"

echo "════════ 构建 ════════"
gradle --no-daemon --offline :app:assembleDebug -Pkotlin.compiler.execution.strategy=in-process > /tmp/cdp_build_g1.log 2>&1
RC=$?
echo "构建退出码=$RC"
if [ $RC -ne 0 ]; then grep -E "^e: " /tmp/cdp_build_g1.log | head -14; exit 1; fi
grep -E "BUILD " /tmp/cdp_build_g1.log | tail -1

boot_emu() {
  bash /vol1/1000/aicache/docker/emu.sh stop >/dev/null 2>&1; sleep 5
  bash /vol1/1000/aicache/docker/emu.sh start 2>&1 | tail -1
  adb wait-for-device
  for i in $(seq 1 50); do B=$(adb -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r'); [ "$B" = "1" ] && break; sleep 6; done
  adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk 2>&1 | tail -1
  # 卫生检查：模拟器要是被锁屏（有些验证会设测试 PIN），应用起不到前台，整组会假红
  adb -s emulator-5554 shell locksettings clear --old 1234 >/dev/null 2>&1
  adb -s emulator-5554 shell input keyevent 224 >/dev/null 2>&1
  adb -s emulator-5554 shell input keyevent 82 >/dev/null 2>&1
  sleep 2
  adb -s emulator-5554 shell am start -n dev.cdp/.MainActivity >/dev/null 2>&1
  # APK 现在 32MB（带 ffmpeg），装完第一次起要解原生库：多等一会儿，并等控制口真的通
  sleep 25
  for _w in $(seq 1 20); do
    if curl -s --max-time 5 http://127.0.0.1:8848/api/status | grep -q '"ok":true'; then break; fi
    sleep 3
  done
  PID=$(adb -s emulator-5554 shell pidof dev.cdp | tr -d '\r')
  adb -s emulator-5554 forward tcp:9222 localabstract:webview_devtools_remote_$PID >/dev/null
  adb -s emulator-5554 forward tcp:8848 tcp:8848 >/dev/null; sleep 2
}

run_one() { # $1=脚本  $2=日志  $3=要不要重启模拟器
  [ "$3" = "yes" ] && boot_emu
  bash "$1" > "/tmp/$2" 2>&1
  echo "退出码=$?" >> "/tmp/$2"
  sed 's/\x1b\[[0-9;]*m//g' "/tmp/$2" | grep -aE "FAIL|小结|通过 " | tail -8
}

bash tools/split_suite.sh 1 16 /tmp/cdp_part_a.sh >/dev/null
bash tools/split_suite.sh 17 39 /tmp/cdp_part_b.sh >/dev/null

echo "════════ 第七组 上半（①–⑯） ════════"
run_one /tmp/cdp_part_a.sh cdp_g7_paP0.log yes
echo "════════ 第七组 下半（⑰–㉝） ════════"
run_one /tmp/cdp_part_b.sh cdp_g7_pbP0.log yes
if [ "${SKIP_REGRESS:-0}" = "1" ]; then
  echo "（跳过第一～六组回归，SKIP_REGRESS=0）"
else
echo "════════ 回归第一～六组 ════════"
run_one tools/verify_click.sh      cdp_reg_g1.log yes
run_one tools/verify_record.sh     cdp_reg_g2.log no
run_one tools/verify_scripts.sh    cdp_reg_g3.log no
run_one tools/verify_cdp.sh        cdp_reg_g4.log no
run_one tools/verify_waitcond.sh   cdp_reg_g5.log no
run_one tools/verify_ui.sh         cdp_reg_g6.log no
fi

echo "════════ 密码库端到端 ════════"
run_one tools/verify_vault.sh cdp_vault_final.log no

echo "════════ 产物 ════════"
stat -c 'APK 字节: %s' app/build/outputs/apk/debug/app-debug.apk
sha256sum app/build/outputs/apk/debug/app-debug.apk | awk '{print "sha256:",$1}'
echo "容器: $(docker ps -a --filter name=emu --format '{{.Status}}' | head -1)"
