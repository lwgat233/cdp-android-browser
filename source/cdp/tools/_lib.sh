#!/usr/bin/env bash
# 验收脚本公共部分：被各 verify_*.sh source
#
# 三个坑写在这里，免得每次重踩：
#   ① 后台 shell 的 PATH 里没有 ~/.local/bin（node 会 command not found，所有页面读数变成空 —— 
#      现象看起来像「应用坏了 / target 是僵尸的」，实则工具根本没跑起来）→ 这里显式补 PATH
#   ② python 取值走 sys.argv 传参，不要在 -c 的双引号串里塞单引号（会被 shell 吃掉 → 静默取空）
#   ③ 计数器用 OKCNT/BADCNT，绝不用 PASS/FAIL —— 环境里可能已有同名变量，计数会直接失效
set -uo pipefail

export PATH="$HOME/.local/bin:$PATH"
export PATH="/vol1/1000/aicache/tools/android-sdk/platform-tools:$PATH"
NODE="$(command -v node || echo "$HOME/.local/bin/node")"
ADB_BIN="$(command -v adb || echo /vol1/1000/aicache/tools/android-sdk/platform-tools/adb)"

ADB="$ADB_BIN -s emulator-5554"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
API="http://127.0.0.1:8848"
HARNESS="https://appassets.androidplatform.net/test/harness.html"
OKCNT=0; BADCNT=0

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()   { OKCNT=$((OKCNT+1)); printf '  \033[32mPASS\033[0m %s\n' "$*"; }
bad()  { BADCNT=$((BADCNT+1)); printf '  \033[31mFAIL\033[0m %s\n' "$*"; }
note() { printf '  %s\n' "$*"; }

api()   { timeout 40 curl -s --max-time 35 "$API$1"; }
urlenc() { python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))' "$1"; }
# 取 JSON 字段：jqv "['ok']" / "['el']['box']['cx']"
jqv()   { python3 -c 'import sys,json;d=json.load(sys.stdin);print(eval("d"+sys.argv[1]))' "$1" 2>/dev/null; }

# 页面里跑一段 JS 并取回结果 —— 走 App 自己的接口（比外部 CDP 少一层「谁连上了」的不确定性）
page() { api "/api/eval?js=$(urlenc "$1")" | jqv "['raw']"; }
# 需要外部 CDP 时（真实手势注入、外部驱动能力）用这个
cdp()  { timeout 40 "$NODE" "$ROOT/tools/cdp.mjs" "$@"; }

assert_json() { # $1 = 断言名, $2 = python 代码（stdin 读 JSON，exit 0 = 通过）, $3 = JSON 字符串
  # 注意：不要写成 `echo "$json" | assert_json ...` —— 函数在管道里跑在子 shell，
  # 计数与失败都不会影响主壳，整个验收会假绿（踩过）
  local label="$1" code="$2" json="$3"
  if printf '%s' "$json" | python3 -c "$code" 2>&1; then ok "$label"; else bad "$label"; fi
}

assert_eq_s() { # $1 = 断言名, $2 = 实际值, $3 = 期望值（都在主壳里比，计数才算数）
  local label="$1" got="$2" want="$3"
  if [ "$got" = "$want" ]; then ok "$label（$got）"; else bad "$label：实际「$got」≠ 期望「$want」"; fi
}

# 用「真手指」按 CSS 坐标点页面（合成注入进不了 iframe，真手指能 —— 用户就是这么点的）
# 需要 uiautomator 里的 WebView 位置来换算设备坐标
webtap_css() { # $1=cssX $2=cssY $3=cssW(默认393) $4=cssH(默认732)
  local cx="$1" cy="$2" cw="${3:-393}" ch="${4:-732}" xml vx vy vw vh dx dy
  # 窗口焦点可能被别的窗口抢走（模拟器自带的 Launcher 被判 ANR 时会这样），
  # dump 里就看不到我们的 WebView —— 把 App 拉到前台再试，最多 3 轮。
  local attempt
  for attempt in 1 2 3; do
    timeout 40 $ADB shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
    xml=$(timeout 40 $ADB shell cat /sdcard/u.xml 2>/dev/null)
    printf '%s' "$xml" | grep -q "dev.cdp" && break
    # 抢焦点的一般是"应用无响应"弹窗（模拟器自带 Launcher 被判 ANR 时）：
    # 先按 BACK 把它按掉，再把 App 拉回前台，然后再 dump
    # 实测：抢焦点的就是模拟器自带 Launcher 的"应用无响应"窗口，按 BACK 按不掉，
    # 把那个 Launcher force-stop 掉窗口才会消失（不这么做，dump 里永远看不到我们的 WebView）
    note "dump 里没看到我们的窗口（第 $attempt 次）：清掉抢焦点的 ANR 窗口 + 把 App 拉回前台"
    timeout 20 $ADB shell am force-stop com.google.android.apps.nexuslauncher >/dev/null 2>&1
    timeout 20 $ADB shell input keyevent KEYCODE_BACK >/dev/null 2>&1
    sleep 0.8
    timeout 30 $ADB shell am start -n dev.cdp/.MainActivity >/dev/null 2>&1
    sleep 2
  done
  read -r vx vy vw vh <<<"$(printf '%s' "$xml" | python3 -c '
import sys, re
x = sys.stdin.read()
for m in re.finditer(r"<node ([^>]+?)/?>", x):
    a = dict(re.findall(r"([a-zA-Z\-]+)=\"([^\"]*)\"", m.group(1)))
    if a.get("class", "").endswith("WebView") and a.get("bounds"):
        n = re.findall(r"-?\d+", a["bounds"])
        if len(n) == 4 and (int(n[3]) - int(n[1])) > 500:
            print(n[0], n[1], int(n[2]) - int(n[0]), int(n[3]) - int(n[1])); break
')"
  [ -n "${vw:-}" ] || { bad "取不到 WebView 位置"; return 1; }
  # 注意：y 不要用 vh/ch —— 页面视口高（cssH）会变，旧值会让换算整体偏掉（实测点到了视频区域）。
  # dpr 是均匀的，统一用宽度比换算最稳。
  dx=$(python3 -c "print(int($vx + $cx*($vw/$cw)))")
  dy=$(python3 -c "print(int($vy + $cy*($vw/$cw)))")
  note "真手指点 css($cx,$cy) → 设备($dx,$dy)"
  timeout 30 $ADB shell input tap "$dx" "$dy"; sleep 1.2
}

# 反复执行一段 JS 直到条件成立（对付 evaluateJavascript 偶发不生效）
page_until() { # $1 = 要执行的 JS, $2 = 判据 JS（返回真值即成功）, $3 = 次数(默认 5)
  local js="$1" check="$2" tries="${3:-5}" i
  for i in $(seq 1 "$tries"); do
    page "$js" >/dev/null
    sleep 0.4
    if [ -n "$(page "$check")" ] && [ "$(page "$check")" != "false" ] && [ "$(page "$check")" != "null" ]; then
      return 0
    fi
    sleep 0.6
  done
  return 1
}

wait_app() { # 等 App 的控制口和页面都就绪
  local i
  for i in $(seq 1 30); do
    if [ -n "$(api /api/status | jqv "['browser']['url']")" ]; then return 0; fi
    sleep 2
  done
  return 1
}

# 设备还在不在：模拟器挂掉时必须明确中止并说清是环境问题，
# 否则后面每一条断言都会失败，把「环境崩了」伪装成「应用有 30 个 bug」
require_device() {
  if ! timeout 20 $ADB get-state >/dev/null 2>&1; then
    echo
    echo "!! 环境问题：模拟器不在线（容器可能已崩溃）。"
    timeout 20 docker ps -a --filter name=notifbridge-emu --format '   容器状态: {{.Status}}' 2>/dev/null
    echo "   处理：bash /vol1/1000/aicache/docker/emu.sh start 后重跑本组"
    exit 3
  fi
}


ensure_harness() {
  require_device
  local i u h
  # 只认 URL 不够：刚装完包/冷启动时页面可能还没真的跑起来（window.H 还没有），
  # 这时候放过去，后面每条断言都会失败，看着像应用坏了。URL + window.H 双双成立才算就位。
  for i in $(seq 1 8); do
    api "/api/goto?url=$(urlenc "$HARNESS")" >/dev/null
    sleep 3
    u=$(page "location.href")
    h=$(page "typeof window.H")
    # 判据用 URL 不用标题：装了用户脚本时标题正是脚本会改的东西
    case "$u" in
      *harness.html*) if [ "$h" = "object" ]; then return 0; fi ;;
    esac
    sleep 2
  done
  echo "页面没到位（当前 URL：$u，typeof window.H：$h）"
  api /api/status | head -c 400; echo
  exit 1
}


# 等控制台页面就位（9222 转发 + 控制台 WebView 真的能 eval）。
# 冷启动/分段跑时界面栏目还没开出来，UI 类断言会拿到空值，
# 看起来像"界面全坏了"——所以 UI 断言前必须先等这个。
wait_console() {
  require_device
  local i pid
  # 冷启动后控制台 WebView 要过一阵才建出来（-gpu off 更慢），所以这里耐心等 + 每几轮推一把
  local rounds="${CDP_WAIT_CONSOLE_ROUNDS:-60}"   # 每轮约 2-3 秒，默认最多等 ~2-3 分钟
  for i in $(seq 1 "$rounds"); do
    api "/api/ui/open" >/dev/null
    # 注意：**不要**在这里调 /api/ui/close —— 它会把控制台关掉，等它的人就永远等不到（踩过）
    # 模拟器重启后 9222 转发会失效 -> 每轮都重建
    pid=$(timeout 20 $ADB shell pidof dev.cdp 2>/dev/null | tr -d '\r')
    if [ -n "$pid" ]; then
      timeout 20 $ADB forward tcp:9222 localabstract:webview_devtools_remote_$pid >/dev/null 2>&1
    fi
    sleep 5
    if cdp eval --target ui/index.html --expr "'rdy'" 2>/dev/null | grep -q rdy; then return 0; fi
  done
  echo "!! 控制台页面没就位（9222 转发或页面 eval 不通）——UI 断言先别跑，否则会全部假红"
  return 1
}

start_http() {
  # 冷启动时序：刚装完包/刚起 App 时控制口可能还在绑端口，这里最多试 3 轮
  local i
  for i in $(seq 1 3); do
    if [ "$(api /api/status | jqv "['http']['running']")" = "True" ]; then return 0; fi
    timeout 20 $ADB shell am broadcast -n dev.cdp/.CmdReceiver -a dev.cdp.CMD --es cmd http --es text start >/dev/null 2>&1
    sleep 3
  done
}
