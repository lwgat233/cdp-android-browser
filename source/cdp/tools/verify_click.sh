#!/usr/bin/env bash
# CDP 项目验收 · 第一组：点击链路
#   ① 真实触摸注入能不能点着、坐标准不准（与页面自记的几何逐像素核对）
#   ② 不是 <a>、只认 pointerup 的 div 按键（学习通那类）能不能点着
#   ③ 被浮层盖住的按钮：诊断要说清是谁盖的，且不能假装点成功
. "$(dirname "$0")/_lib.sh"

say "0. 前置：HTTP 控制口 + 测试页"
start_http
ST=$(api /api/status)
note "控制口: $(echo "$ST" | jqv "['http']")"
[ "$(echo "$ST" | jqv "['http']['running']")" = "True" ] && ok "控制口在跑" || bad "控制口没起来"
# 干净起点：把上一轮留下的用户脚本清掉（否则它们会改页面，让验收判据漂移）
note "清掉旧脚本：$(api /api/scripts/clear)"
ensure_harness
note "页面: $(page "JSON.stringify({t:document.title,vw:innerWidth,vh:innerHeight,dpr:devicePixelRatio,scrollY:Math.round(scrollY)})")"
note "计数: $(page "JSON.stringify(window.H.counts)")"

say "1. 对照组：普通 <button> —— 真实触摸注入 + 坐标精度"
R=$(api "/api/click?selector=%23easyBtn")
note "App 结果: $(echo "$R" | head -c 320)"
[ "$(echo "$R" | jqv "['ok']")" = "True" ] && ok "点着了（via=$(echo "$R" | jqv "['via']")）" || bad "没点着: $(echo "$R" | head -c 200)"
[ "$(echo "$R" | jqv "['trusted']")" = "True" ] && ok "isTrusted=true（真实触摸事件，不是 JS 合成）" || bad "isTrusted=$(echo "$R" | jqv "['trusted']")"
[ "$(page "window.H.counts.easy")" = "1" ] && ok "页面侧计数 easy=1" || bad "页面侧计数=$(page "window.H.counts.easy")"

GEO=$(page "JSON.stringify(window.H.lastGeo)")
note "页面自记几何: $GEO"
assert_json "命中点落在元素内部且基本居中（±4px）" '
import sys, json
g = json.load(sys.stdin)
dx = abs(g["localX"] - g["elW"]/2); dy = abs(g["localY"] - g["elH"]/2)
print("    -> 相对元素左上角 (%s,%s)，元素 %sx%s，偏差 (%.1f,%.1f)，inside=%s"
      % (g["localX"], g["localY"], g["elW"], g["elH"], dx, dy, g["inside"]))
sys.exit(0 if (g["inside"] and dx <= 4 and dy <= 4) else 1)
' "$GEO"

say "2. 关键场景：不是 <a>、只认 pointerup 的 div 按键"
note "先证明「JS 直接 click() 打不动它」——这正是这类按键的痛点"
B=$(page "window.H.counts.div")
page "(function(){document.getElementById('divBtn').click();return 'ok';})" >/dev/null
sleep 1
A=$(page "window.H.counts.div")
[ "$B" = "$A" ] && ok "对照组成立：el.click() 之后计数仍是 $A（所以必须靠真实事件注入）" || bad "el.click() 竟然触发了（$B→$A），测试页不具代表性"

R=$(api "/api/click?selector=%23divBtn")
note "App 结果: $(echo "$R" | head -c 320)"
[ "$(echo "$R" | jqv "['ok']")" = "True" ] && ok "点着了（via=$(echo "$R" | jqv "['via']")，isTrusted=$(echo "$R" | jqv "['trusted']")）" || bad "没点着"
[ "$(page "window.H.counts.div")" = "1" ] && ok "页面侧计数 div=1（真的触发了它的 pointerup 处理器）" || bad "页面侧计数=$(page "window.H.counts.div")"

say "3. 被浮层盖住的按钮：诊断要说清原因，不能假装成功"
R=$(api "/api/click?selector=%23coveredBtn")
note "App 结果: $(echo "$R" | head -c 320)"
D=$(api "/api/diag?selector=%23coveredBtn")
note "诊断: $(echo "$D" | head -c 400)"
assert_json "诊断指出中心点被具体元素盖住" '
import sys, json
g = (json.load(sys.stdin).get("diag") or {})
print("    -> hitSelf=%s coveredBy=%s clickableAncestor=%s" % (g.get("hitSelf"), g.get("coveredBy"), g.get("clickableAncestor")))
sys.exit(0 if (g.get("hitSelf") is False and (g.get("coveredBy") or "") != "") else 1)
' "$D"
CV=$(page "window.H.counts.cover"); CB=$(page "window.H.counts.covered")
CV=${CV:-0}; CB=${CB:-0}
note "页面侧：浮层被命中 $CV 次，被盖按钮被命中 $CB 次"
[ "$CV" -ge 1 ] && [ "$CB" = "0" ] && ok "与真实手指一致：触摸落在浮层上，被盖按钮没被误触" || bad "浮层=$CV 按钮=$CB（预期浮层≥1、按钮=0）"

say "小结：通过 $OKCNT 项 / 失败 $BADCNT 项"
[ "$BADCNT" = "0" ]
