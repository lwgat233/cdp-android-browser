#!/usr/bin/env python3
"""B-80：按键必须是**简单的黑白色**（灰阶，R≈G≈B），不许有彩色按钮。

判据：把页面上**可见**的 <button> 全扫一遍，读浏览器算出来的
      background-color / color / border-top-color，要求三者的 R、G、B 分量差 ≤ 6（灰阶）。
      只有色差超标的才报出来，并附上它长什么样、在哪个栏目。
用法：python3 tools/verify_buttons_mono.py
"""
import json
import os
import subprocess
import sys
import time

SRC = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
ENV = dict(os.environ, PATH="/home/lwgat/.local/bin:/home/lwgat/tools/android-sdk/platform-tools:" + os.environ.get("PATH", ""))
TABS = ["page", "tools", "rec", "scripts", "plugins", "cookies", "vault", "block", "security", "net",
        "sniff", "downloads", "history", "bookmarks", "settings", "power"]

PROBE = r"""
(function () {
  function rgb(s) {
    var m = String(s).match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/);
    return m ? [parseInt(m[1], 10), parseInt(m[2], 10), parseInt(m[3], 10)] : null;
  }
  function gray(c) { if (!c) return true; var d = Math.max.apply(null, c) - Math.min.apply(null, c); return d <= 6; }
  var bad = [], okCount = 0, samples = [];
  [].slice.call(document.querySelectorAll('button')).forEach(function (b) {
    if (b.offsetParent === null) return;                       // 看不见的不算
    var c = getComputedStyle(b);
    var bg = rgb(c.backgroundColor), fg = rgb(c.color), bd = rgb(c.borderTopColor);
    var txt = (b.textContent || '').trim().slice(0, 12);
    var board = b.closest('[data-board]');
    var where = (board ? board.getAttribute('data-board') : (b.parentNode && b.parentNode.id) || '') || '';
    var alpha = /rgba\(\s*\d+,\s*\d+,\s*\d+,\s*0\)/.test(c.backgroundColor);
    if (gray(bg) && gray(fg) && gray(bd)) {
      okCount++;
      if (samples.length < 6) samples.push({ t: txt, where: where,
        bg: c.backgroundColor, fg: c.color });
    } else {
      bad.push({ t: txt, where: where, bg: c.backgroundColor, fg: c.color, bd: c.borderTopColor,
                 alphaZero: alpha });
    }
  });
  return JSON.stringify({ ok: okCount, bad: bad, samples: samples });
})()
"""


def ui(expr):
    r = subprocess.run(["node", "tools/uiprobe.mjs", "--timeout", "15000", "--match", "ui/index.html", expr],
                       capture_output=True, text=True, cwd=SRC, env=ENV, timeout=70)
    out = [l for l in (r.stdout or "").strip().splitlines() if l.strip()]
    try:
        return json.loads(out[-1]) if out else {}
    except Exception:
        return {}


def sh(cmd, t=40):
    return subprocess.run(["bash", "-lc", cmd], capture_output=True, text=True, timeout=t, env=ENV).stdout.strip()


total_ok = 0
allbad = []
for t in TABS:
    sh("curl -s --max-time 12 'http://127.0.0.1:8848/api/ui/open?tab=%s'" % t)
    time.sleep(1.6)
    r = ui(PROBE) or {}
    total_ok += r.get("ok", 0)
    for b in (r.get("bad") or []):
        b["tab"] = t
        allbad.append(b)
    if t == "page" and r.get("samples"):
        print("抽样（栏目 %s）：" % t)
        for s in r["samples"]:
            print("   「%s」@%s  底 %s / 字 %s" % (s["t"], s["where"], s["bg"], s["fg"]))

print("\n灰阶按键 %d 个；不合规 %d 个" % (total_ok, len(allbad)))
for b in allbad[:20]:
    print("  ✗ [%s] 「%s」@%s  底 %s / 字 %s / 边 %s" % (b["tab"], b["t"], b["where"], b["bg"], b["fg"], b["bd"]))
sys.exit(1 if allbad else 0)
