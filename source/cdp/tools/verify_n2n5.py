#!/usr/bin/env python3
"""N2（嗅探按文件类型分）+ N5（录制板块重排）—— 各看一眼就够的那几件。

N2 判据：① 列表里的类型标签是**扩展名**（.m3u8 / .ts …）；
        ② 状态行按文件类型数（".m3u8 N 条"）；
        ③ 筛选下拉的选项是扩展名；④ 整页里没有"视频/音频/播放列表/分片"这种媒体分类字样。
N5 判据：① 录制栏目里 **步骤框在录制脚本列表上面**（DOM 顺序）；
        ② 回放/运行那几个动作和脚本列表在**同一块**（不再单独一块）；
        ③ 「回放这条 / 跑当前队列 / 刷新」三个键都在，且点「刷新」有回执；
        ④ 「载入到步骤列表」在**步骤框下面**。
用法：python3 tools/verify_n2n5.py
"""
import json
import os
import subprocess
import sys
import time

SRC = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
ENV = dict(os.environ, PATH="/home/lwgat/.local/bin:/home/lwgat/tools/android-sdk/platform-tools:" + os.environ.get("PATH", ""))
P = F = 0
FAILS = []


def chk(name, got, want):
    global P, F
    ok = want(got) if callable(want) else got == want
    print("  %s %s  → %s" % ("PASS" if ok else "FAIL", name, json.dumps(got, ensure_ascii=False)[:170]))
    if ok:
        P += 1
    else:
        F += 1
        FAILS.append(name)


def sh(cmd, t=90):
    return subprocess.run(["bash", "-lc", cmd], capture_output=True, text=True, timeout=t, env=ENV).stdout.strip()


def api(path, t=25):
    try:
        return json.loads(sh("curl -s --max-time %d '%s%s'" % (t, HTTP, path), t + 8))
    except Exception:
        return {}


def ui(expr, t=60, match="ui/index.html"):
    r = subprocess.run(["node", "tools/uiprobe.mjs", "--timeout", "12000", "--match", match, expr],
                       capture_output=True, text=True, cwd=SRC, env=ENV, timeout=t)
    out = [l for l in (r.stdout or "").strip().splitlines() if l.strip()]
    return out[-1] if out else None


def open_tab(key, timeout=30):
    for _ in range(max(3, timeout // 3)):
        api("/api/ui/open?tab=" + key)
        time.sleep(2)
        if str(ui("(function(){var t=document.querySelector('.tab.on');return t?t.id:'';})()")) == "tab-" + key:
            return True
    return False


print("[1] N2：嗅探按文件类型分")
open_tab("sniff")
api("/api/sniff/clear")
time.sleep(1)
# 打开 HLS 测试页（里面有 m3u8 与 ts 分片），让嗅探有料。
# 注意：嗅探**故意跳过 127.0.0.1 的本机请求**（防自嗅探），所以测试页要走 10.0.2.2（模拟器眼里的宿主）。
api("/api/nav/open?url=" + "http://10.0.2.2:8848/api/_test/hls/page.html".replace(":", "%3A").replace("/", "%2F"))
time.sleep(8)
api("/api/ui/open?tab=sniff")
time.sleep(3)
lst = api("/api/sniff") or {}
st = lst.get("stats") or {}
print("     嗅探统计：", json.dumps(st, ensure_ascii=False)[:200], " 目录:", lst.get("dir"))
chk("统计是按文件类型给的（byKind）", "byKind" in st, True)
chk("里面有 m3u8", str(st.get("byKind")), lambda s: "m3u8" in s)
chk("给人看的下载目录是系统目录", lst.get("dir"), "Download/cdp")
kinds = ui("JSON.stringify([].map.call(document.querySelectorAll('#sn-list .row-item .kind'),function(e){return e.textContent.trim()}))") or "[]"
print("     行上的类型标签：", kinds[:160])
chk("类型标签是扩展名（.m3u8 / .ts 这种）", kinds, lambda s: (".m3u8" in str(s) or ".ts" in str(s)))
chk("类型标签里没有「视频/音频」这种媒体词", any(x in str(kinds) for x in ("视频", "音频", "播放列表", "分片")), False)
stats_line = ui("(document.getElementById('sn-stats')||{}).textContent||''") or ""
print("     状态行：", stats_line[:140])
chk("状态行按文件类型数", stats_line, lambda s: (".m3u8" in str(s) or ".ts" in str(s)))
opts = ui("JSON.stringify([].map.call(document.querySelectorAll('#sn-group option'),function(o){return o.value}))") or "[]"
print("     筛选项：", opts[:160])
chk("筛选项是扩展名（不是 playlist/video/audio）", opts,
    lambda s: ("m3u8" in str(s) or "ts" in str(s)) and "playlist" not in str(s) and "video" not in str(s))
page_txt = ui("JSON.stringify([].map.call(document.querySelectorAll('#tab-sniff *'),function(e){return (e.childElementCount===0?(e.textContent||''):'')}).join(' '))") or ""
chk("界面里不再出现「播放列表 / 视频 / 音频 / 分片」这些分类词",
    any(x in str(page_txt) for x in ("播放列表", "音频（", "分片（")), False)

print("[2] N5：录制板块重排")
open_tab("rec")
order = ui("(function(){var steps=document.getElementById('r-steps'),list=document.getElementById('rec-list');"
           "if(!steps||!list)return '?';"
           "var pos=steps.compareDocumentPosition(list);"
           "return (pos & Node.DOCUMENT_POSITION_FOLLOWING)?'steps-first':'list-first';})()")
chk("步骤框在录制脚本列表上面", order, "steps-first")
rng = ui("(function(){var s=document.getElementById('r-steps'),b=document.getElementById('r-loadgo');"
         "if(!s||!b)return '?';var c=s.compareDocumentPosition(b);"
         "return (c & Node.DOCUMENT_POSITION_FOLLOWING)?'load-below':'load-above';})()")
chk("「载入到步骤列表」在步骤框下面", rng, "load-below")
same = ui("(function(){var b=document.getElementById('r-replay');if(!b)return '?';"
          "var card=b.closest('[data-board]');return card?card.getAttribute('data-board'):'none';})()")
chk("回放键与脚本列表在同一块（data-board=rec-list）", same, "rec-list")
keys = ui("JSON.stringify(['r-replay','r-runall','rc-reload','rc-clear'].map(function(i){return i+':'+!!document.getElementById(i)}))")
chk("回放/跑队列/刷新/清空四个键都在", keys, lambda s: s.count("true") == 4)
api("/api/ui/open?tab=rec")
time.sleep(2)
ui("(function(){var b=document.getElementById('rc-reload');if(b)b.click();return 'x';})()")
time.sleep(2)
chk("点「刷新」有可读回执", ui("String(!!document.getElementById('rc-reload'))"), "true")

print("\n结果：%d/%d 通过" % (P, P + F))
if FAILS:
    print("  未通过：" + "；".join(FAILS))
sys.exit(1 if F else 0)
