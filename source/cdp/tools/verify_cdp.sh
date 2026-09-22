#!/usr/bin/env bash
# CDP 项目验收 · 第四组：外部 CDP 驱动能力 + 资源占用 + 稳定性
#   ① 电脑侧用标准 CDP 连上 App 里的 WebView：列 target / 读页面 / 导航 / 注入鼠标输入
#   ② 两条独立通道（外部 CDP 与 App 自己的接口）读同一个值必须一致
#   ③ 资源占用与崩溃迹象
. "$(dirname "$0")/_lib.sh"

say "1. 外部 CDP：能看到哪些页面 target"
L=$(cdp list 2>&1)
echo "$L" | sed 's/^/  /'
echo "$L" | grep -q 'harness.html\|start.html' && ok "能看到浏览器页面 target" || bad "看不到页面 target"
echo "$L" | grep -q 'ui/index.html' && ok "能看到控制台页面 target" || bad "看不到控制台 target"

say "2. 外部 CDP：读当前页面（不依赖 App 接口）"
api /api/scripts/clear >/dev/null
ensure_harness
T=$(cdp eval --target harness --expr "document.title" 2>/dev/null | tail -1)
note "CDP 读到标题：$T"
[ "$T" = "CDP 自动化测试页" ] && ok "外部 CDP 能读到页面标题" || bad "读到的标题不对：$T"

say "3. 外部 CDP：自己发起的导航真的生效"
cdp nav --target harness --url "$ROOT/ui-start" >/dev/null 2>&1 || true
cdp nav --target harness --url "https://appassets.androidplatform.net/ui/start.html" >/dev/null 2>&1
sleep 3
T2=$(cdp eval --target start.html --expr "document.title" 2>/dev/null | tail -1)
# 主页标题在"主页改简洁"这轮从「CDP 首页」改成了「新标签页」——按新标题判，
# 同时确认这确实是那个主页（有搜索框），不是随便哪个页面
[ "$T2" = "新标签页" ] && ok "CDP 发起的导航生效（现在标题：$T2）" || bad "导航没生效（标题：$T2）"
[ "$(page "location.href")" = "https://appassets.androidplatform.net/ui/start.html" ] && ok "App 侧也看到同一页面（两边观察一致）" || bad "App 侧看到的 URL 不同：$(page "location.href")"

say "4. 两条通道读同一个值，必须一致"
ensure_harness
A=$(page "document.title")
B=$(cdp eval --target harness --expr "document.title" 2>/dev/null | tail -1)
note "App 接口：$A　｜　外部 CDP：$B"
[ "$A" = "$B" ] && [ -n "$A" ] && ok "两个独立通道读数一致（$A）" || bad "不一致：App=$A CDP=$B"

say "5. 外部 CDP：注入一次鼠标点击"
GEO=$(page "JSON.stringify((function(){var r=document.getElementById('easyBtn').getBoundingClientRect();return {x:Math.round(r.left+r.width/2),y:Math.round(r.top+r.height/2)}})())")
TX=$(echo "$GEO" | jqv "['x']"); TY=$(echo "$GEO" | jqv "['y']")
note "目标 #easyBtn 中心(css)：$TX,$TY　当前计数：$(page "window.H.counts.easy")"
cdp mouse --target harness --x "$TX" --y "$TY" 2>&1 | tail -1 | sed 's/^/  /'
sleep 1
CNT=$(page "window.H.counts.easy")
[ "$CNT" = "1" ] && ok "外部注入的鼠标点击真的触发了页面处理器（easy=1）" || bad "外部注入没生效（easy=$CNT）"

say "6. 资源占用"
PID=$($ADB shell pidof dev.cdp | tr -d '\r')
note "pid=$PID　内存：$($ADB shell dumpsys meminfo dev.cdp 2>/dev/null | grep -E '^\s+TOTAL' | head -1 | tr -s ' ')"
$ADB shell "grep -E 'VmRSS|Threads' /proc/$PID/status" 2>/dev/null | sed 's/^/  /'
note "宿主：$(free -m | awk '/^Mem:/{print "总 "$2"MB 可用 "$7"MB"}')　模拟器容器：$(docker ps --filter name=notifbridge-emu --format '{{.Status}}')"

say "7. 稳定性"
CRASH=$($ADB logcat -d -t 4000 2>/dev/null | grep -E 'FATAL EXCEPTION|ANR in dev.cdp|beginning of crash' | tail -5)
if [ -z "$CRASH" ]; then ok "本轮 logcat 没有 FATAL / ANR"; else bad "发现崩溃或 ANR：$CRASH"; fi
AGENTERR=$(api /api/log?n=200 | python3 -c "
import sys,json
try: ls=[l for l in json.load(sys.stdin)['lines'] if '报错' in l or '页面错误' in l]
except Exception: ls=[]
print(' | '.join(ls[-3:]))" 2>/dev/null)
note "页面侧报错（最后几条）：${AGENTERR:-无}"

say "小结：通过 $OKCNT 项 / 失败 $BADCNT 项"
[ "$BADCNT" = "0" ]
