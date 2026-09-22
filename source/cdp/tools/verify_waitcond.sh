#!/usr/bin/env bash
# CDP 项目验收 · 第五组：等待/条件（视频播完再点下一个）、运行计数、历史、书签、界面通道
. "$(dirname "$0")/_lib.sh"

require_device
ensure_harness
start_http

# ------------------------------------------------------------------ 小工具
# 手写一个「点元素」步骤（选择器足够，定位链会自己走 id → 路径 → 文字）
step_click() {
  python3 -c 'import json,sys;sel=sys.argv[1];i=sel[1:] if sel.startswith("#") else ""
print(json.dumps({"t":"click","target":{"selector":sel,"id":i,"tag":"","text":"","attrs":{}},"pauseAfter":300},ensure_ascii=False))' "$1"
}
# 插入一步（走 HTTP 控制口，和手机上点「插入」是同一条代码路径）
insert_step() { api "/api/record/insert?step=$(urlenc "$1")"; }
# 页面侧视频状态快照（页面自己记的计数，不看 App 的转述）
vstate() {
  page "JSON.stringify({videos:document.querySelectorAll('video').length,plays:H.video.plays,ended:H.video.ended,dur:+(H.video.duration||0).toFixed(2),next:H.counts.afterVideo,nextTrusted:H.trusted.afterVideo===true,shown:getComputedStyle(document.getElementById('nextAfterVideo')).display})"
}

say "0. 前置：页面得有一个真的 <video>，而且还没播过"
page "H.video.plays=0;H.video.ended=false;H.counts.afterVideo=0;H.trusted.afterVideo=null;document.getElementById('nextAfterVideo').style.display='none';'reset'" >/dev/null
V0=$(vstate)
note "视频初始状态: $V0"
assert_json "测试页上有 1 个真实 <video>（clip.webm）" '
import sys, json
d = json.load(sys.stdin)
print("    -> 视频数 %s，时长 %ss，已播 %s 次" % (d.get("videos"), d.get("dur"), d.get("plays")))
sys.exit(0 if d.get("videos") == 1 and d.get("dur", 0) > 2 and d.get("plays") == 0 else 1)
' "$V0"

# ------------------------------------------------------------------ 1. 录制里插入等待/条件
say "1. 监听（录制）里插入「播放视频 → 等它播完 → 如果出现『下一个』就点它」"
# 反馈 #13 之后：录制是"加到队列"，不再清空。这一节要断言"插了 3 步"，所以先显式清空队列，
# 免得上一轮留下的步骤把断言弄红（那是行为变更，不是缺陷）。
api "/api/record?action=clear" >/dev/null; sleep 1
assert_json "开始监听" '
import sys, json; d = json.load(sys.stdin); sys.exit(0 if d.get("ok") and d.get("on") in (True, None) else 1)
' "$(api "/api/record?action=start&name=$(urlenc '视频播完再点下一个')")"

S1=$(python3 -c 'import json;print(json.dumps({"t":"playVideo","selector":"","muted":True,"pauseAfter":600},ensure_ascii=False))')
S2=$(python3 -c 'import json;print(json.dumps({"t":"waitFor","cond":{"type":"videoEnded","selector":""},"timeoutMs":30000,"intervalMs":300,"pauseAfter":200},ensure_ascii=False))')
S3=$(python3 - <<'PY'
import json
click = {"t": "click", "target": {"selector": "#nextAfterVideo", "id": "nextAfterVideo", "tag": "div", "text": "", "attrs": {}}, "pauseAfter": 300}
print(json.dumps({"t": "if", "cond": {"type": "elementExists", "selector": "#nextAfterVideo"},
                  "then": [click], "else": [], "pauseAfter": 300}, ensure_ascii=False))
PY
)
R1=$(insert_step "$S1"); R2=$(insert_step "$S2"); R3=$(insert_step "$S3")
assert_json "插入步骤1：播放视频（当前 1 步）" 'import sys,json;d=json.load(sys.stdin);sys.exit(0 if d.get("ok") and d.get("count")==1 else 1)' "$R1"
assert_json "插入步骤2：等视频播完（当前 2 步）" 'import sys,json;d=json.load(sys.stdin);sys.exit(0 if d.get("ok") and d.get("count")==2 else 1)' "$R2"
assert_json "插入步骤3：如果『下一个』出现就点它（当前 3 步）" 'import sys,json;d=json.load(sys.stdin);sys.exit(0 if d.get("ok") and d.get("count")==3 else 1)' "$R3"

STOP=$(api "/api/record?action=stop")
SID=$(printf '%s' "$STOP" | jqv "['id']")
note "停止并保存：$STOP"
assert_json "停止并保存：本次新增 3 步、拿到脚本 id（队列不清空，所以总数=新增）" '
import sys, json
d = json.load(sys.stdin)
added = d.get("added", d.get("steps"))
sys.exit(0 if d.get("ok") and added == 3 and d.get("id") else 1)
' "$STOP"

# ------------------------------------------------------------------ 2. 回放：必须等到 ended
say "2. 回放：必须真的等到视频播完，再点下一个"
R=$(api "/api/replay?script=$SID")
assert_json "三步依次生效，且等待步骤真的等到了 ended" '
import sys, json
d = json.load(sys.stdin)
st = d.get("steps") or []
print("    -> 总耗时 %sms，整体 ok=%s" % (d.get("ms"), d.get("ok")))
for i, s in enumerate(st):
    t = s.get("type")
    if t == "playVideo":
        print("    -> 步骤%d 播放视频 ok=%s 注入坐标=%s" % (i+1, s.get("ok"), json.dumps(s.get("tapPoint"))))
    elif t == "waitFor":
        print("    -> 步骤%d 等待 %s：等了 %sms，轮询 %s 次，ok=%s" % (i+1, s.get("cond"), s.get("waitedMs"), s.get("polls"), s.get("ok")))
    elif t == "if":
        print("    -> 步骤%d 条件 %s=%s → 走 %s 分支，子步骤 %s 个" % (i+1, s.get("cond"), s.get("condResult"), s.get("branch"), len(s.get("sub") or [])))
# 视频 3 秒：等到 ended 至少要等 1 秒以上、轮询 3 次以上，才算「真的在等」而不是空转
ok = (len(st) == 3 and all(s.get("ok") for s in st)
      and st[1].get("waitedMs", 0) > 800 and (st[1].get("polls") or 0) >= 3
      and st[2].get("condResult") is True and st[2].get("branch") == "then")
sys.exit(0 if ok else 1)
' "$R"

V1=$(vstate)
note "页面侧视频状态: $V1"
assert_json "视频真的被播放了 1 次（不是假状态）" '
import sys, json; d = json.load(sys.stdin)
print("    -> plays=%s ended=%s dur=%s" % (d.get("plays"), d.get("ended"), d.get("dur")))
sys.exit(0 if d.get("plays") == 1 else 1)
' "$V1"
assert_json "ended 事件真的触发了（播放到结尾）" '
import sys, json; d = json.load(sys.stdin); sys.exit(0 if d.get("ended") is True else 1)
' "$V1"
assert_json "「下一个」被点中 1 次" '
import sys, json; d = json.load(sys.stdin)
print("    -> next=%s nextTrusted=%s shown=%s" % (d.get("next"), d.get("nextTrusted"), d.get("shown")))
sys.exit(0 if d.get("next") == 1 else 1)
' "$V1"
assert_json "点它用的是真实触摸（isTrusted=true）" '
import sys, json; d = json.load(sys.stdin); sys.exit(0 if d.get("nextTrusted") is True else 1)
' "$V1"

# ------------------------------------------------------------------ 3. 计数
say "3. 计数：再跑一次，累计次数与每步触发次数都要涨"
api "/api/replay?script=$SID" >/dev/null
ST=$(api "/api/scripts/get?id=$SID")
V2=$(vstate)
assert_json "运行计数正确累加（跑了 2 次、每步各触发 2 次）" '
import sys, json
d = json.load(sys.stdin)
s = d.get("stats") or (d.get("script") or {}).get("stats") or {}
print("    -> stats: 跑了 %s 次，成功 %s 次，上次耗时 %sms，每步触发 %s" % (s.get("runs"), s.get("okRuns"), s.get("lastMs"), s.get("stepFires")))
fires = s.get("stepFires") or []
sys.exit(0 if s.get("runs") == 2 and fires[:3] == [2, 2, 2] else 1)
' "$ST"
assert_json "页面侧计数也到 2（两轮各点中一次）" '
import sys, json; d = json.load(sys.stdin); sys.exit(0 if d.get("next") == 2 else 1)
' "$V2"

# ------------------------------------------------------------------ 4. 条件为假 → else
say "4. 条件为假时必须走 else，不能碰 then 里的东西"
COND_SCRIPT=$(python3 - <<'PY'
import json
steps = [
    {"t": "if", "cond": {"type": "elementExists", "selector": "#definitelyNotHere"},
     "then": [{"t": "click", "target": {"selector": "#easyBtn", "id": "easyBtn", "tag": "button", "text": "", "attrs": {}}, "pauseAfter": 200}],
     "else": [{"t": "wait", "ms": 80}], "pauseAfter": 200}
]
print(json.dumps({"name": "条件为假", "kind": "recording", "steps": steps}, ensure_ascii=False))
PY
)
page "H.counts.easy=0" >/dev/null
EID=$(printf '%s' "$COND_SCRIPT" | curl -s --max-time 30 -X POST -H 'Content-Type: application/json' \
        --data-binary @- "$API/api/scripts/save" | jqv "['id']")
note "建脚本结果 id=$EID"
assert_json "已建一条「条件为假」的脚本（POST 请求体里带中文也不卡）" '
import sys, json; d = json.load(sys.stdin); sys.exit(0 if d.get("ok") and d.get("id") else 1)
' "$(printf '{"ok":%s,"id":"%s"}' "$([ -n "$EID" ] && echo true || echo false)" "$EID")"

RE=$(api "/api/replay?script=$EID")
assert_json "条件不成立 → 走 else，且 then 里的点击没被执行" '
import sys, json
d = json.load(sys.stdin)
st = d.get("steps") or []
s = st[0] if st else {}
print("    -> 条件 %s=%s → 分支 %s，子步骤 %s" % (s.get("cond"), s.get("condResult"), s.get("branch"), len(s.get("sub") or [])))
sys.exit(0 if s.get("condResult") is False and s.get("branch") == "else" else 1)
' "$RE"
assert_json "then 里的点击确实没执行（easy 计数保持 0）" '
import sys, json
n = json.load(sys.stdin)          # 页面侧返回的就是个裸数字
print("    -> H.counts.easy = %s" % n)
sys.exit(0 if int(n) == 0 else 1)
' "$(page "H.counts.easy")"

# ------------------------------------------------------------------ 5. 历史与书签
say "5. 历史与书签"
H1=$(api /api/history)
assert_json "导航被记进历史" '
import sys, json
d = json.load(sys.stdin)
lst = d.get("list") or []
print("    -> 历史条数 %s，最新一条：%s %s" % (len(lst), (lst[0] or {}).get("title") if lst else "-", (lst[0] or {}).get("url") if lst else "-"))
sys.exit(0 if any("harness.html" in (x.get("url") or "") for x in lst) else 1)
' "$H1"

BT=$(api "/api/bookmarks/toggle")
assert_json "收藏当前页成功" '
import sys, json
d = json.load(sys.stdin)
print("    -> %s" % json.dumps(d, ensure_ascii=False)[:200])
sys.exit(0 if d.get("ok") and (d.get("added") is True or d.get("bookmark")) else 1)
' "$BT"
B1=$(api /api/bookmarks)
assert_json "书签列表里有它" '
import sys, json
d = json.load(sys.stdin)
lst = d.get("list") or []
print("    -> 书签 %s 条：%s" % (len(lst), [x.get("url") for x in lst][:2]))
sys.exit(0 if any("harness.html" in (x.get("url") or "") for x in lst) else 1)
' "$B1"

api "/api/goto?url=$(urlenc "https://appassets.androidplatform.net/ui/start.html")" >/dev/null
sleep 2
api "/api/nav/open?url=$(urlenc "$HARNESS")" >/dev/null
sleep 3
assert_json "从书签/历史点开能真的导航过去" '
import sys, json; d = json.load(sys.stdin)
u = (d.get("browser") or {}).get("url") or ""
print("    -> 当前 URL：%s" % u)
sys.exit(0 if "harness.html" in u else 1)
' "$(api /api/status)"

BT2=$(api "/api/bookmarks/toggle")
assert_json "再点一次取消收藏" '
import sys, json
d = json.load(sys.stdin)
print("    -> added=%s removed=%s" % (d.get("added"), json.dumps(d.get("removed"), ensure_ascii=False)[:120]))
sys.exit(0 if d.get("ok") and d.get("added") is False and d.get("removed") else 1)
' "$BT2"

# ------------------------------------------------------------------ 6. 界面通道
say "6. 界面通道：✕ 真的能关控制台、下拉菜单能切到对应栏目"
CL=$(api /api/ui/close)
assert_json "✕ 走的是原生通道（日志里有『控制台已关闭（✕ 生效）』）" '
import sys, json; d = json.load(sys.stdin); sys.exit(0 if d.get("ok") else 1)
' "$CL"
sleep 1
assert_json "关闭动作真的落到了 App 日志里" '
import sys, json
d = json.load(sys.stdin)
lines = d.get("lines") or []
print("    -> 日志尾部：%s" % [x[-60:] for x in lines[-2:]])
sys.exit(0 if any("控制台已关闭" in x for x in lines) else 1)
' "$(api "/api/log?n=40")"

api "/api/ui/open?tab=history" >/dev/null
sleep 2
TAB=$(cdp eval --target ui/index.html --expr "JSON.stringify({on:(document.querySelector('#tabs button.tabBtn.on')||{}).textContent,hisTab:document.getElementById('tab-history').className})" 2>/dev/null | tail -1)
note "控制台当前页签: $TAB"
assert_json "从下拉菜单可直接落到「历史」栏目" '
import sys, json
d = json.load(sys.stdin)
# 本轮改版：抽屉按钮文案带了栏目里的功能数（"历史（1）"），所以比"包含"而不是"等于"
sys.exit(0 if "历史" in (d.get("on") or "") and "on" in (d.get("hisTab") or "") else 1)
' "$TAB"

say "小结：通过 $OKCNT 项 / 失败 $BADCNT 项"
[ "$BADCNT" = "0" ] || exit 1
