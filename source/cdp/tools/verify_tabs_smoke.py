#!/usr/bin/env python3
"""全栏目冒烟：逐个切栏目，抓**未捕获的 promise 异常**和页面错误。

为什么必须专门抓这个（血泪）：界面取数是 `call(...).then(function(){...})` 里的异步代码，
外面那层 try/catch **抓不到它** —— 一个 ReferenceError 就能让整张列表静默画不出来
（现象＝"列表空的、不报错"，用户报的"下载列表没内容""添加 APP 不渲染"都是这一类）。
页面自己的 window.__lastTabErr 也看不到（那是同步路径的）。

判据：每个栏目切过去后 ① 没有 unhandledrejection ② 没有 window.onerror
     ③ 该栏目若有列表，至少被碰过（不强制非空：空态是合理的）。
用法：python3 tools/verify_tabs_smoke.py
"""
import json
import os
import subprocess
import sys
import time

SRC = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
ENV = dict(os.environ, PATH="/home/lwgat/.local/bin:/home/lwgat/tools/android-sdk/platform-tools:" + os.environ.get("PATH", ""))
TABS = ["page", "tools", "rec", "scripts", "plugins", "term", "cookies", "vault", "block", "security",
        "net", "sniff", "downloads", "history", "bookmarks", "bundle", "api", "ai", "space", "settings",
        "power", "log", "intro"]
P = F = 0
FAILS = []


def sh(cmd, t=60):
    return subprocess.run(["bash", "-lc", cmd], capture_output=True, text=True, timeout=t, env=ENV).stdout.strip()


def ui(expr, t=60):
    r = subprocess.run(["node", "tools/uiprobe.mjs", "--timeout", "12000", "--match", "ui/index.html", expr],
                       capture_output=True, text=True, cwd=SRC, env=ENV, timeout=t)
    out = [l for l in (r.stdout or "").strip().splitlines() if l.strip()]
    return out[-1] if out else None


print("先挂错误钩子（页面自己的 try/catch 抓不到异步里的错）")
ui("(function(){window.__smoke=[];window.__rej=[];"
   "window.addEventListener('error',function(e){window.__smoke.push('error: '+e.message+' @'+(e.filename||'').slice(-16)+':'+e.lineno)});"
   "window.addEventListener('unhandledrejection',function(e){var r=e.reason;window.__smoke.push('reject: '+((r&&(r.stack||r.message))||String(r)).slice(0,160))});"
   "return 'hooked'})()")

bad = 0
for t in TABS:
    ui("(function(){var b=document.getElementById('tabbtn-%s');if(!b)return 'no-btn';b.click();return 'ok'})()" % t)
    time.sleep(2.5)
    errs = ui("JSON.stringify(window.__smoke||[])") or "[]"
    try:
        arr = json.loads(errs)
    except Exception:
        arr = []
    if arr:
        bad += 1
        F += 1
        FAILS.append(t)
        print("  FAIL %-10s → %s" % (t, "；".join(str(x) for x in arr)[:200]))
    else:
        P += 1
        print("  PASS %-10s" % t)
    ui("window.__smoke=[]; 'x'")

print("\n结果：%d/%d 栏目干净" % (P, P + F))
if FAILS:
    print("  有异常的栏目：" + "、".join(FAILS))
sys.exit(1 if F else 0)
