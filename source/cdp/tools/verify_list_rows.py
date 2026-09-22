#!/usr/bin/env python3
"""列表行合规审计 —— 判据就是用户定死的那份规范（docs/界面规范-列表行.md）。

一行 = 左列：第1行**标题**（大字号 ≥14px、字重 ≥600，**只有它**超出才用「…」）
            第2行**网址/次要信息**（小字号 ≤12px，且在标题**下面**）
       右列：时间 / 流量 / 大小 / 文件夹（**.rt / .when / .dl-size**，靠右，且 **scrollWidth<=clientWidth** 不截断）

做法：不看源码标记，只读**浏览器算出来的**字号/字重/位置/宽度 —— 视觉上不对就是不对。
用法：python3 tools/verify_list_rows.py
"""
import json
import os
import subprocess
import sys
import time

SRC = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
ADB = "/home/lwgat/tools/android-sdk/platform-tools/adb"
ENV = dict(os.environ, PATH="/home/lwgat/.local/bin:/home/lwgat/tools/android-sdk/platform-tools:" + os.environ.get("PATH", ""))

# 栏目 → 列表容器 id（本轮的清单；没出现的列表就是"没测到"，不假装通过）
TAB_LISTS = [
    ("history", ["h-list"]),
    ("bookmarks", ["b-list"]),
    ("downloads", ["d-list"]),
    ("net", ["n-list"]),
    ("sniff", ["sn-list"]),
    ("cookies", ["ck-list"]),
    # 其余列表（插件里的阅读记录、脚本库、密码库…）逐步纳入这份清单
    ("plugins", ["rd-list"]),
    ("scripts", ["sc-list"]),
]

# 页面里跑的判定：逐行给出 合不合规 + 为什么（判据全部是读回来的计算样式/几何）
PROBE = r"""
(function () {
  function info(el) {
    if (!el) return null;
    var c = getComputedStyle(el), r = el.getBoundingClientRect();
    return { fs: parseFloat(c.fontSize) || 0, fw: parseInt(c.fontWeight, 10) || 400,
             top: r.top, right: r.right, w: r.width,
             clip: (el.scrollWidth > el.clientWidth + 1) || (c.textOverflow === 'ellipsis'),
             hasEll: (el.textContent || '').indexOf('…') >= 0,
             txt: (el.textContent || '').trim().slice(0, 18), cls: el.className || '' };
  }
  var out = {};
  var ids = __IDS__;
  ids.forEach(function (id) {
    var host = document.getElementById(id);
    if (!host) { out[id] = { exists: false, rows: [] }; return; }
    // 「＋ 添加…」这种新增行不是信息行，不按信息行规范判（它就是个按钮）
    var rows = [].slice.call(host.querySelectorAll('.row-item:not(.add-row)')).slice(0, 12).map(function (row) {
      var kids = [].slice.call(row.children);
      // 标题/右列可能嵌在 .head 里（阅读记录那种结构）—— 往里看一层，别误判"认不出标题"
      var inner = kids.map(function (k) { return k.matches('.head') ? [].slice.call(k.children) : []; })
        .reduce(function (a, b) { return a.concat(b); }, []);
      var pool = kids.concat(inner);
      var title = pool.filter(function (k) { return k.matches('.lnk,.nm2,.ckn,.kind,.nm'); })[0] || null;
      var second = pool.filter(function (k) { return k.matches('.u,.ckv'); })[0] || null;
      var right = pool.filter(function (k) { return k.matches('.rt,.when,.dl-size,.t'); })[0] || null;
      if (!title && row.getAttribute('data-title')) {
        var cand = kids.filter(function (k) { return (k.textContent || '').trim().length > 0; })[0];
        title = cand || null;
      }
      var rr = row.getBoundingClientRect();
      var bad = [];
      var ti = info(title), si = info(second), ri = info(right);
      if (!ti) bad.push('连标题都认不出来');
      else {
        if (ti.fs < 14) bad.push('标题字号太小(' + ti.fs + 'px，规范 ≥14)');
        if (ti.fw < 600) bad.push('标题不够粗(weight ' + ti.fw + '，规范 ≥600)');
      }
      if (!si) bad.push('没有第 2 行小字（网址/次要信息）');
      else {
        if (si.fs > 12.5) bad.push('第 2 行不够小(' + si.fs + 'px，规范 ≤12)');
        if (ti && si.top <= ti.top + 1) bad.push('第 2 行没在标题下面');
        if (si.hasEll) bad.push('第 2 行用了「…」省略（只有标题可以用）');
      }
      if (right) {
        if (ri.clip) bad.push('右列可以截断（text-overflow:ellipsis）');
        if (right.scrollWidth > right.clientWidth + 1) bad.push('右列数值被撑破(scrollWidth ' +
          right.scrollWidth + ' > clientWidth ' + right.clientWidth + ')');
        if (ri.right < rr.right - 24) bad.push('右列没靠到行右边缘');
      }
      if (ti && ti.hasEll === false && right && right.hasEll) bad.push('右列出现了「…」');
      return { bad: bad, text: (row.textContent || '').trim().slice(0, 42),
               title: ti ? ti.txt : '', rs: ri ? ri.txt : '', ss: si ? si.txt : '',
               allday: row.getAttribute('data-fields') || '' };
    });
    out[id] = { exists: true, rows: rows };
  });
  return JSON.stringify(out);
})()
"""


def sh(cmd, t=90):
    return subprocess.run(["bash", "-lc", cmd], capture_output=True, text=True, timeout=t, env=ENV).stdout.strip()


def api(path, t=30):
    try:
        return json.loads(sh("curl -s --max-time %d '%s%s'" % (t, HTTP, path), t + 10))
    except Exception:
        return {}


def probe(ids):
    r = subprocess.run(["node", "tools/uiprobe.mjs", "--timeout", "20000", "--match", "ui/index.html",
                        PROBE.replace("__IDS__", json.dumps(ids))],
                       capture_output=True, text=True, cwd=SRC, env=ENV, timeout=90)
    out = [l for l in (r.stdout or "").strip().splitlines() if l.strip()]
    try:
        return json.loads(out[-1]) if out else {}
    except Exception:
        return {}


total = bad_rows = 0
notes = []
for tab, ids in TAB_LISTS:
    api("/api/ui/open?tab=" + tab)
    time.sleep(2)
    # 用页面自己的栏目按钮触发取数（控制口 API 切栏目不会跑页面里的加载器）
    subprocess.run(["bash", "-lc", "%s -s emulator-5554 shell input tap 1 1" % ADB], capture_output=True, text=True, timeout=30)
    res = probe(ids)
    for lid, v in (res or {}).items():
        if not v.get("exists"):
            print("── %-9s 这个页面里没有这个列表" % lid)
            continue
        rows = v.get("rows") or []
        if not rows:
            print("── %-9s（空列表，跳过）" % lid)
            continue
        print("── %-9s（%d 行）" % (lid, len(rows)))
        for i, row in enumerate(rows):
            total += 1
            if row["bad"]:
                bad_rows += 1
                print("     行 %-2d ✗ %s   ｜ %s" % (i, "；".join(row["bad"]), row["text"][:60]))
        print("     小结：%d 行里 %d 行不合规" % (len(rows), sum(1 for r in rows if r["bad"])))

print("\n共检查 %d 行，其中 %d 行不合规" % (total, bad_rows))
if total == 0:
    print("（一行都没读到 —— 先确认 App 在前台、控制口在听、页面是 ui/index.html）")
sys.exit(1 if bad_rows else 0)
