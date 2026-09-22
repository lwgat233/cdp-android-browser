#!/usr/bin/env bash
# CDP 项目验收 · 第二组：录制 → 回放（用户的核心用例）
#   ① 在页面上真实点一下「不是 <a>、只认 pointerup」的按键 → App 记下这一步
#      （位置按「距底部/距顶部」+ DOM 指纹 + 可点击性诊断一起记）
#   ② 回放：指纹完好时走 DOM 指纹；指纹被破坏后，退到「位置锚点」仍然点得中
#   ③ 每一步都报：定位策略 / 注入坐标 / 注入前后页面点击计数 / 是否 isTrusted
. "$(dirname "$0")/_lib.sh"

say "1. 回到测试页，确认计数干净"
api /api/scripts/clear >/dev/null   # 清掉上一轮的用户脚本，避免它改页面影响判据
ensure_harness
note "计数: $(page "JSON.stringify(window.H.counts)")"

say "2. 开始录制，然后用「真实手势」点一下那个 div 按键（等价于用户手点）"
R=$(api "/api/record?action=start&name=%E5%AD%A6%E4%B9%A0%E9%80%9A-%E4%B8%8B%E4%B8%80%E7%AB%A0")
[ "$(echo "$R" | jqv "['ok']")" = "True" ] && ok "录制已开始" || bad "开不了录制: $R"

GEO=$(page "JSON.stringify((function(){var r=document.getElementById('divBtn').getBoundingClientRect();return {x:Math.round(r.left+r.width/2),y:Math.round(r.top+r.height/2),w:Math.round(r.width),h:Math.round(r.height)}})())")
note "目标几何(css): $GEO"
TX=$(echo "$GEO" | jqv "['x']"); TY=$(echo "$GEO" | jqv "['y']")
# 用浏览器级鼠标事件序列当「用户手点」：pointerdown→pointerup→click 都会产生，
# 正是这个 div 唯一认的那条路（Input.synthesizeTapGesture 在 WebView 上不派发）
cdp mouse --target harness --x "$TX" --y "$TY" >/dev/null 2>&1
sleep 1
DIV=$(page "window.H.counts.div")
[ "$DIV" = "1" ] && ok "真实手势点中了（页面侧计数=1）" || bad "页面侧计数=$DIV"

say "3. 停止录制，检查记下来的内容"
R=$(api "/api/record?action=stop")
ID=$(echo "$R" | jqv "['id']")
[ "$(echo "$R" | jqv "['steps']")" = "1" ] && ok "录到 1 步，脚本 id=$ID" || bad "步数不对: $R"

S=$(api "/api/scripts/get?id=$ID")
assert_json "记下来的这一步：位置锚点 + DOM 指纹 + 诊断都齐" '
import sys, json
d = json.load(sys.stdin); st = (d.get("script") or {}).get("steps") or []
if not st: print("    -> 取不到步骤"); sys.exit(1)
s = st[0]; a = s.get("anchor") or {}; t = s.get("target") or {}; g = s.get("diag") or {}
print("    -> 类型=%s 目标=%s#%s「%s」" % (s.get("t"), t.get("tag"), t.get("id"), (t.get("text") or "")[:16]))
print("    -> 位置：距底 %s px / 距顶 %s px（视口 %sx%s，纵向比例 %.3f，主锚=%s）"
      % (a.get("bottomPx"), a.get("topPx"), a.get("vw"), a.get("vh"), a.get("ratioY") or 0, a.get("mode")))
print("    -> 指纹：%s" % t.get("selector"))
print("    -> 诊断：isAnchor=%s 有onclick=%s cursor=%s 中心命中自身=%s 可点祖先=%s"
      % (g.get("isAnchor"), g.get("hasOnclickAttr") or g.get("onclickProp"), g.get("cursor"), g.get("hitSelf"), g.get("clickableAncestor")))
ok = (s.get("t") == "click" and a.get("bottomPx") and a.get("topPx") and a.get("vw")
      and g.get("isAnchor") is False and "divBtn" in (t.get("selector") or ""))
sys.exit(0 if ok else 1)
' "$S"

say "4. 回放（指纹完好）：应走真实触摸"
R=$(api "/api/replay?script=$ID")
assert_json "指纹完好时：真实触摸点中，且页面点击数确实增加" '
import sys, json
d = json.load(sys.stdin); st = (d.get("steps") or [{}])[0]; loc = st.get("locate") or {}
print("    -> 定位策略=%s 命中=%s 注入点=%s" % (loc.get("strategy"), loc.get("ok"), json.dumps(st.get("tapPoint"))))
print("    -> 点击计数 %s → %s，via=%s，isTrusted=%s，耗时 %s ms"
      % (st.get("clicksBefore"), st.get("clicksAfter"), st.get("via"), st.get("trusted"), d.get("ms")))
ok = (d.get("ok") is True and st.get("ok") is True and st.get("via") == "native-touch"
      and st.get("trusted") is True and (st.get("clicksAfter") or 0) > (st.get("clicksBefore") or 0))
sys.exit(0 if ok else 1)
' "$R"
DIV=$(page "window.H.counts.div")
[ "$DIV" = "2" ] && ok "页面侧计数 1 → 2" || bad "页面侧计数=$DIV（预期 2）"

say "5. 关键：把 DOM 指纹破坏掉，只留位置 —— 还能不能点中"
# 破坏方式要「公平」：只换 id 与文字（框架重渲染的典型情形），但保住布局 —— 
# 测试页这个 div 的样式原本是按 #divBtn 写死的，直接改 id 会连样式一起丢、位置也移，
# 那测的就不是「位置锚点」而是「目标搬走了」（搬到别处的情形在下面单独测）
BREAK_JS="(function(){var e=document.getElementById('divBtn')||document.getElementById('renamedByTest'); if(!e) return 'NO_ELEM'; e.id='renamedByTest'; e.textContent='完全不同的文字'; e.setAttribute('style','display:inline-block;padding:14px 22px;margin:6px 0;background:#1D4E75;border:2px solid #2F7DB5;border-radius:10px;color:#EAF6FF;'); return 'done';})()"
if page_until "$BREAK_JS" "!!document.getElementById('renamedByTest') && !document.getElementById('divBtn')" 6; then
  ok "指纹已破坏（id 与文字都换了，原先记录的 #divBtn 选择器失效）"
else
  bad "连指纹都没能改成功（页面侧 JS 没生效），这一步的判断不成立"
fi
note "破坏后目标几何: $(page "JSON.stringify((function(){var e=document.getElementById('renamedByTest');var r=e.getBoundingClientRect();return {x:Math.round(r.left),y:Math.round(r.top),w:Math.round(r.width),h:Math.round(r.height)};})())")"
R=$(api "/api/replay?script=$ID")
assert_json "指纹没了，靠位置锚点仍然点中" '
import sys, json
d = json.load(sys.stdin); st = (d.get("steps") or [{}])[0]; loc = st.get("locate") or {}
print("    -> 定位策略=%s 命中=%s（试过的策略：%s）" % (loc.get("strategy"), loc.get("ok"), loc.get("tried")))
print("    -> 注入点=%s via=%s isTrusted=%s 计数 %s→%s"
      % (json.dumps(st.get("tapPoint")), st.get("via"), st.get("trusted"), st.get("clicksBefore"), st.get("clicksAfter")))
ok = (st.get("ok") is True and loc.get("strategy") in ("anchor-point", "anchor:bottom"))
sys.exit(0 if ok else 1)
' "$R"
DIV=$(page "window.H.counts.div")
[ "$DIV" = "3" ] && ok "页面侧计数 2 → 3（位置锚点这一路真的点着了）" || bad "页面侧计数=$DIV（预期 3）"

say "5b. 目标搬走之后：必须如实报失败，不能点到别的东西上"
MOVED_BEFORE=$(page "window.H.counts.div")
MOVED_JS="(function(){var e=document.getElementById('renamedByTest'); if(!e) return 'NO_ELEM'; e.setAttribute('style','position:absolute;left:23px;top:2000px;display:inline-block;padding:14px 22px;background:#1D4E75;color:#EAF6FF;'); return 'moved';})()"
MOVED_CHECK="(function(){var e=document.getElementById('renamedByTest'); return !!e && e.getBoundingClientRect().top > 1500;})()"
page_until "$MOVED_JS" "$MOVED_CHECK" 5 && ok "已把目标搬到 y>1500 处（远离录制时位置）" || bad "没能把目标搬走，这一步不成立"
M=$(api "/api/replay?script=$ID")
assert_json "目标搬到屏幕外别处：回放报失败（不静默点到别处）" '
import sys, json
d = json.load(sys.stdin); st = (d.get("steps") or [{}])[0]; loc = st.get("locate") or {}
print("    -> 步骤 ok=%s 定位=%s 原因=%s" % (st.get("ok"), loc.get("ok"), str(loc.get("error"))[:60]))
sys.exit(0 if st.get("ok") is False else 1)
' "$M"
MOVED_AFTER=$(page "window.H.counts.div")
[ "$MOVED_BEFORE" = "$MOVED_AFTER" ] && ok "页面侧计数没变（$MOVED_AFTER：确实没误点到别的元素上）" || bad "计数 $MOVED_BEFORE → $MOVED_AFTER：疑似误点了别处"

say "6. 列表底部的按键：要先滚进视野才够得着"
ensure_harness
R=$(api "/api/click?text=%E5%88%97%E8%A1%A8%E5%BA%95%E9%83%A8%E7%9A%84%E3%80%8C%E4%B8%8B%E4%B8%80%E7%AB%A0%E3%80%8D")
note "App 结果: $(echo "$R" | head -c 420)"
DEEP=$(page "window.H.counts.deep"); SY=$(page "Math.round(window.scrollY)")
[ "$(echo "$R" | jqv "['ok']")" = "True" ] && [ "$DEEP" = "1" ] && ok "点中了列表底部的按键（页面计数=1）" || bad "没点中：deep=$DEEP"
[ "$(echo "$R" | jqv "['via']")" = "native-touch" ] && ok "走的是真实触摸注入（via=native-touch）" || bad "走的是兜底路径：via=$(echo "$R" | jqv "['via']")"
python3 -c "import sys;sys.exit(0 if float('$SY' or 0) > 100 else 1)" && ok "确实先滚动了页面（scrollY=$SY）" || bad "没滚动就点（scrollY=$SY），说明注入坐标不是滚后重取的"

say "小结：通过 $OKCNT 项 / 失败 $BADCNT 项"
[ "$BADCNT" = "0" ]
