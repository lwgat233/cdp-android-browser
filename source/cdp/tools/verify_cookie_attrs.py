#!/usr/bin/env python3
"""Cookie 属性验收（B-82）：**清空 → 打开测试页 → 不等**就要求属性可读；界面上真手指点行读小窗。

背景（证据 evidence/47-cookie属性实验（落盘时延与CDP）.txt）：两个真因
  ① 库里那份滞后 —— Chromium 批量提交，不 flush 时刚写的 cookie 库里一行都没有；
  ② 前端把属性字段写死成一句"系统不提供"，后端有值也不显示。
本脚本围这两条转，判据全部是读回来的事实（API 字段 / 小窗文本 / select 的值）。

用法：python3 tools/verify_cookie_attrs.py
"""
import json
import os
import subprocess
import sys
import time
import urllib.parse
import urllib.request

ROOT = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
PAGE = "http://10.0.2.2:8899/"
ENV = dict(os.environ, PATH="/home/lwgat/.local/bin:" + os.environ.get("PATH", ""))
N = 0
PASS = 0
FAIL = 0


def ok(m):
    global PASS
    PASS += 1
    print("  PASS " + m)


def bad(m):
    global FAIL
    FAIL += 1
    print("  FAIL " + m)


def api(path, timeout=40):
    with urllib.request.urlopen(HTTP + path, timeout=timeout) as r:
        return json.loads(r.read().decode())


def ui(expr, timeout=60):
    r = subprocess.run(["node", "tools/uiprobe.mjs", "--timeout", "12000", "--match", "ui/index.html", expr],
                       capture_output=True, text=True, cwd=ROOT, timeout=timeout, env=ENV)
    out = [l for l in (r.stdout or "").strip().splitlines() if l.strip()]
    return out[-1] if out else None


def tap(x, y):
    return subprocess.run(["node", "tools/cdp.mjs", "tap", "--target", "ui/index.html", "--x", str(x), "--y", str(y)],
                          capture_output=True, text=True, cwd=ROOT, env=ENV, timeout=60).stdout.strip()


if not api("/api/status").get("ok"):
    print("控制口不通，先起环境。")
    sys.exit(2)

print("== 1. API 面：清空 → 打开测试页 → **立刻**读（不等 60 秒） ==")
api("/api/cookies/clear")
api("/api/nav/open?url=" + urllib.parse.quote(PAGE))
d = api("/api/cookies")
flat = {}
for g in d.get("list", []):
    for c in g.get("cookies", []):
        flat.setdefault(c["name"], c)

ho = flat.get("hocookie")
if not ho:
    bad("列表里没有 hocookie（测试页没打开？在跑 %s 吗）" % PAGE)
else:
    if ho.get("httpOnly") is True:
        ok("hocookie httpOnly=True（刚写的、没等）")
    else:
        bad("hocookie httpOnly=%r（attrMissing=%r）" % (ho.get("httpOnly"), ho.get("attrMissing")))
    if str(ho.get("expires", "")).startswith("20"):
        ok("hocookie 过期时间是真日期：" + str(ho.get("expires")))
    else:
        bad("hocookie 过期时间=%r" % ho.get("expires"))
    if ho.get("attrFrom"):
        ok("属性来源于 cookie 库（attrFrom 有值）")
    else:
        bad("没有 attrFrom → 属性没并上（attrMissing=%r）" % ho.get("attrMissing"))
    if int(ho.get("expiresMs") or 0) > 0:
        ok("带机器可读的 expiresMs=%s（界面按它算有效期）" % ho.get("expiresMs"))
    else:
        bad("expiresMs=%r（界面算不了有效期）" % ho.get("expiresMs"))

se = flat.get("session_only")
if se and str(se.get("expires", "")).startswith("会话期"):
    ok("会话 cookie 如实显示「会话期」")
else:
    bad("session_only 过期时间=%r" % (se or {}).get("expires"))

print("== 2. 界面面：真手指点 hocookie 行 → 小窗里读属性 ==")
api("/api/ui/open?tab=cookies")
rows = "0"
for _ in range(8):
    time.sleep(2)
    rows = ui("String(document.querySelectorAll('#ck-list .row-item').length)")
    if rows and rows.isdigit() and int(rows) > 0:
        break
if not (rows and rows.isdigit() and int(rows) > 0):
    bad("Cookie 栏目里没有行（rows=%r）" % rows)
else:
    n = N = len(ui("String(document.body.textContent)") or "")
    loc = ui("(function(){var rs=document.querySelectorAll('#ck-list .row-item');"
             "for(var i=0;i<rs.length;i++){var e=rs[i].querySelector('.ckn');"
             "if(e&&e.textContent.trim()==='hocookie'){var r=rs[i].getBoundingClientRect();"
             "return JSON.stringify({x:Math.round(r.left+r.width/2),y:Math.round(r.top+r.height/2)});}}"
             "return 'NOTFOUND';})()")
    if not loc or not loc.startswith("{"):
        bad("列表里找不到 hocookie 这一行（%r）" % loc)
    else:
        p = json.loads(loc)
        print("   真手指点 (%d,%d)" % (p["x"], p["y"]))
        tap(p["x"], p["y"])
        time.sleep(2)
        text = ui("(document.getElementById('cdp-modal')||{}).textContent||''") or ""
        if "HttpOnly" in text:
            ok("小窗里有 HttpOnly 字段")
        else:
            bad("小窗里没有 HttpOnly 字段")
        # 只读字段的值在 input/select 的 value 里（不是 textContent）——从值这一面判
        vals = ui("(function(){var s=document.getElementById('cdp-modal');if(!s)return '[]';"
                  "var o=Array.prototype.slice.call(s.querySelectorAll('input')).map(function(x){return x.value;});"
                  "o=o.concat(Array.prototype.slice.call(s.querySelectorAll('select')).map(function(x){return x.value;}));"
                  "return JSON.stringify(o);})()")
        try:
            vlist = json.loads(vals) if vals and vals.startswith("[") else []
        except Exception:
            vlist = []
        if any(str(v).startswith("2026-") for v in vlist):
            ok("小窗里读到了真实过期时间：" + str([v for v in vlist if str(v).startswith("2026-")]))
        else:
            bad("小窗里没有真实过期时间，值=%r" % vlist)
        days = [v for v in vlist if str(v).isdigit()]
        if any(str(v) == "1" for v in days):
            ok("「有效期（天）」是算出来的真值 1（不再是写死的 0）")
        else:
            bad("有效期（天）还是无效值：%r" % days)
        if "系统不提供" in text or "系统不暴露" in text:
            bad("小窗里还有那句写死的道歉文案")
        else:
            ok("写死的道歉文案已删")
        sel = ui("(function(){var s=document.getElementById('cdp-modal');if(!s)return '[]';"
                 "return JSON.stringify(Array.prototype.slice.call(s.querySelectorAll('select')).map(function(x){return x.value;}));})()")
        if sel and '"1"' in sel:
            ok("HttpOnly/Secure 的开关是真值（select 值含 1）：" + str(sel))
        else:
            bad("小窗里的开关值不对：%r" % sel)
        ui("try{CDPUI.Modal.close()}catch(e){}")

print("\n结果：%d PASS / %d FAIL" % (PASS, FAIL))
sys.exit(0 if FAIL == 0 else 1)
