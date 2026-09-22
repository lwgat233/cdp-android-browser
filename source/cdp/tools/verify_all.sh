#!/usr/bin/env bash
# 跑全部验收组，输出同时存证到项目 evidence/ 目录
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
EV="$ROOT/../../evidence"
mkdir -p "$EV"
STAMP=$(date +%H:%M:%S)

run() { # $1 组名  $2 脚本  $3 存证文件名
  echo "===================== $1 ====================="
  bash "tools/$2" 2>&1 | tee "$EV/$3"
  local rc=${PIPESTATUS[0]}
  echo "---- $1 退出码 $rc ----"
  return $rc
}

R1=0; R2=0; R3=0; R4=0; R5=0; R6=0; R7=0
run "第一组 点击链路" verify_click.sh "01-点击链路.txt" || R1=1
run "第二组 录制与回放" verify_record.sh "02-录制与回放.txt" || R2=1
run "第三组 用户脚本与接口" verify_scripts.sh "03-用户脚本与接口.txt" || R3=1
run "第四组 外部CDP与资源" verify_cdp.sh "04-外部CDP与资源.txt" || R4=1
run "第五组 等待条件与导航" verify_waitcond.sh "05-等待条件与导航.txt" || R5=1
run "第六组 界面按键可用性" verify_ui.sh "07-界面按键可用性.txt" || R6=1
run "第七组 浏览器能力" verify_browser.sh "08-浏览器能力.txt" || R7=1
echo
echo "汇总（0=全过）：第一组=$R1 第二组=$R2 第三组=$R3 第四组=$R4 第五组=$R5 第六组=$R6 第七组=$R7"
exit $((R1+R2+R3+R4+R5+R6+R7))
