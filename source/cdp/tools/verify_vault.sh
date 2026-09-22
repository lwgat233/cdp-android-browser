#!/usr/bin/env bash
# 密码库端到端验证（第 4 条）：设一个测试锁屏 PIN -> 解锁 -> 存 -> 读 -> 验证落盘是密文 -> 再锁上。
# 说明：这里用 adb 给模拟器设了一个测试 PIN（1234），跑完会清掉；真机上的锁屏指纹/人脸不在验证范围内。
set -uo pipefail
cd /vol1/1000/airesults/cdp/source/cdp
. tools/_lib.sh

require_device
start_http
wait_app

API="$API"
ok()   { OKCNT=$((OKCNT+1)); printf '  \033[32mPASS\033[0m %s\n' "$*"; }
bad()  { BADCNT=$((BADCNT+1)); printf '  \033[31mFAIL\033[0m %s\n' "$*"; }
note() { printf '  %s\n' "$*"; }

echo "──── 0. 给模拟器设测试锁屏 PIN（1234）────"
$ADB shell locksettings set-pin 1234 >/dev/null 2>&1
sleep 2
sec=$($ADB shell "locksettings get-disabled" 2>/dev/null | tr -d '\r')
note "锁屏已设（get-disabled=$sec）"

echo "──── 1. 未解锁：看/存都该被拒 ────"
before=$(api "/api/vault/list")
assert_json "没解锁时列条目被拒" '
import json,sys
d=json.load(sys.stdin)
assert d.get("ok") is False and d.get("needUnlock") is True, d
' "$before"

echo "──── 2. 解锁（真的走系统锁屏验证）────"
u=$(api "/api/vault/unlock")
note "unlock -> $(printf '%s' "$u" | head -c 160)"
sleep 3
# 系统验证界面上输入 PIN 并回车
$ADB shell input text 1234 >/dev/null 2>&1
sleep 1
$ADB shell input keyevent 66 >/dev/null 2>&1
sleep 4
st=$(api "/api/vault")
assert_json "解锁后状态变成已解锁" '
import json,sys
d=json.load(sys.stdin)
print("    -> unlocked=%s 剩余 %ss" % (d.get("unlocked"), d.get("secondsLeft")))
assert d.get("unlocked") is True, d
' "$st"

echo "──── 3. 存一条，再读回来 ────"
sv=$(api "/api/vault/save?site=$(urlenc example.com)&user=$(urlenc alice)&pass=$(urlenc "S3cret!Pass")")
vid=$(printf '%s' "$sv" | jqv "['id']")
note "save -> $(printf '%s' "$sv" | head -c 140)"
if [ -n "${vid:-}" ]; then ok "存进去了（id=$vid）"; else bad "存失败：$(printf '%s' "$sv" | head -c 160)"; fi
ls_=$(api "/api/vault/list")
assert_json "列表里能看到这条，但**不回密码**" '
import json,sys
d=json.load(sys.stdin)
l=d.get("list") or []
print("    -> %s 条，第一条字段 %s" % (len(l), sorted(l[0].keys()) if l else []))
assert d.get("ok") and len(l) >= 1, d
assert "pass" not in (l[0].keys() if l else []), l[:1]
' "$ls_"
gv=$(api "/api/vault/get?id=$vid")
assert_json "单条 get 能拿到密码（此时已解锁）" '
import json,sys
d=json.load(sys.stdin)
assert d.get("ok") and (d.get("item") or {}).get("pass") == "S3cret!Pass", d
' "$gv"

echo "──── 4. 落盘的是密文（明文密码不许出现在文件里）────"
hex=$(timeout 60 $ADB shell run-as dev.cdp sh -c 'cat files/vault.enc' 2>/dev/null | od -c | head -3)
# 先确认文件真的存在且不为空——空文件会让 grep 假绿（踩过这种假阳性）
vsize=$(timeout 60 $ADB shell run-as dev.cdp wc -c files/vault.enc 2>/dev/null | tr -d '\r' | awk '{print $1}')
if [ -z "${vsize:-}" ] || [ "${vsize:-0}" -lt 100 ]; then
  bad "落盘文件不存在或太小（${vsize:-空} 字节）——这条不能算通过"
else
  note "落盘文件 ${vsize} 字节"
  # 不用管道（run-as 的 sh -c 管道会偶发拿不到输入）；而且读到空要重试——
  # 空输出是 adb/设备抖动，不能说成"文件里有明文"（这是把环境问题说成应用缺陷）
  cnt=""
  for i in 1 2 3; do
    cnt=$(timeout 60 $ADB shell run-as dev.cdp grep -c S3cret files/vault.enc 2>/dev/null | tr -d '\r' | tr -dc '0-9')
    [ -n "$cnt" ] && break
    sleep 1
  done
  if [ -z "$cnt" ]; then
    bad "读不到落盘文件（adb/设备抖动，不是应用问题）"
  elif [ "$cnt" = "0" ]; then
    ok "落盘文件里搜不到明文密码（${vsize} 字节密文，AES/GCM）"
  else
    bad "落盘文件里出现了明文密码（命中 ${cnt} 次）"
  fi
fi
note "文件头（octal dump）: $(printf '%s' "$hex" | head -2 | tr '\n' ' ' | cut -c1-100)"

echo "──── 5. 锁上以后又看不到 ────"
api "/api/vault/lock" >/dev/null
lk=$(api "/api/vault/list")
assert_json "lock 之后列条目又被拒" '
import json,sys
d=json.load(sys.stdin)
assert d.get("ok") is False and d.get("needUnlock") is True, d
' "$lk"

echo "──── 6. 清掉测试用的锁屏 PIN ────"
$ADB shell locksettings clear --old 1234 >/dev/null 2>&1
sleep 2
note "已清（跑完不影响后续测试）"

printf '\n密码库小结：通过 %s 项 / 失败 %s 项\n' "$OKCNT" "$BADCNT"
exit $(( BADCNT > 0 ? 1 : 0 ))
