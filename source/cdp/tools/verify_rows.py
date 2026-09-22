#!/usr/bin/env python3
"""信息型列表的验收：行里不许有按键，点这行要弹出这条的全部信息。

用户反复讲的规矩（照它验）：
  ① 网络 / Cookie / 嗅探 / 下载 / 历史 这些"信息列表"的行上**不该有按键**（最多一个 ⋯ 提示）；
  ② **点这一行** → 弹窗，把这条的信息按「字段：值」摆出来；
  ③ 动作排在信息下面（弹窗里有按键是对的，行上没有才对）。
判据都是读回来的 DOM 状态 + 弹窗正文，不是"看着像"。
"""
import json
import subprocess
import sys
import time

ROOT = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
checks = []


def ui(expr, timeout=40):
    r = subprocess.run(["node", "tools/uiprobe.mjs", "--timeout", "8000", expr],
                       capture_output=True, text=True, cwd=ROOT, timeout=timeout)
    out = [l for l in (r.stdout or "").strip().splitlines() if l.strip()]
    if not out:
        return None
    try:
        return json.loads(out[-1])
    except Exception:
        return out[-1]


def api(path, timeout=40):
    r = subprocess.run(["curl", "-s", "--max-time", str(timeout), HTTP + path],
                       capture_output=True, text=True, timeout=timeout + 10)
    try:
        return json.loads(r.stdout)
    except Exception:
        return {}


def chk(name, got, want):
    ok = want(got) if callable(want) else got == want
    checks.append((name, bool(ok), str(got)[:200]))
    print(("  PASS " if ok else "  FAIL ") + name + "  → " + str(got)[:200])
    return ok


BOARDS = [("net", "网络", "n-list"), ("cookies", "Cookie", "ck-list"),
          ("sniff", "嗅探", "sn-list"), ("downloads", "下载", "d-list"), ("history", "历史", "h-list")]


def main():
    api("/api/ui/open?tab=net")
    time.sleep(1.5)
    for tab, label, host in BOARDS:
        print("\n[%s]" % label)
        ui("window.__cdpTab(%s)" % json.dumps(tab))
        time.sleep(1.6)
        # 让列表真的有内容（按栏目各自的刷新键刷一次）
        ui("(function(){var b=document.querySelector('#tab-%s button');if(b)b.click();return 1;})()" % tab)
        time.sleep(1.2)
        rows = ui("JSON.stringify(window.__cdpRowScan().filter(function(r){return !r.board || r.board.indexOf(%s)>=0;}))"
                  % json.dumps({"net": "net-", "cookies": "cookie", "sniff": "sniff", "downloads": "downloads", "history": "history"}[tab]))
        if not isinstance(rows, list) or not rows:
            print("  （这个列表现在是空的，跳过行为检查；这也算如实结果）")
            continue
        bad = [r for r in rows if r["visibleButtons"]]
        chk("行上没有按键（%d 行）" % len(rows), bad, lambda b: b == [])
        chk("每行都能点开（data-sheeted）", [r for r in rows if not r["clickable"]], lambda b: b == [])
        chk("每行都带这条的信息字段", [r for r in rows if not r["hasFields"]], lambda b: b == [])
        # 真点第一行 → 弹窗里要有字段
        # 字段现在是「键小字在上 / 值大字在下」的成对块（.kv-k / .kv-v），不再是一整段文字
        opened = ui("(function(){var r=document.querySelector('.listbox .row-item:not([data-kind])');if(!r)return 'no-row';"
                    "r.click();var m=document.getElementById('cdp-modal');"
                    "var ks=Array.prototype.map.call(document.querySelectorAll('#cdp-modal .kv-list .kv-k'),"
                    "function(e){return (e.textContent||'').trim();});"
                    "var vs=Array.prototype.map.call(document.querySelectorAll('#cdp-modal .kv-list .kv-v'),"
                    "function(e){return (e.textContent||'').trim();});"
                    "return JSON.stringify({shown:!!m&&m.style.display!=='none',keys:ks,vals:vs.slice(0,2)});})()")
        chk("点第一行弹出小窗", (opened or {}).get("shown"), True)
        ks = (opened or {}).get("keys") or []
        chk("小窗里摆出了字段（键:值 的对数）", len(ks), lambda n: n >= 2)
        chk("每个键都有名字（不是空标签）", [k for k in ks if not k], lambda b: b == [])
        ui("(function(){var b=document.getElementById('cdp-modal-x');if(b)b.click();return 1;})()")
        time.sleep(0.3)

    print("\n[反复点同一条：小窗里的按键不能越点越多]")
    # 注意：前后必须点**同一行**再比（第一次我拿历史行、后面拿网络行比，键位数当然不一样 —— 假红）
    ui("window.__cdpTab('net')")
    time.sleep(1.6)
    ROW = "#n-list .row-item"
    first = ui("(function(){var r=document.querySelector(%s);if(!r)return null;r.click();return JSON.stringify(window.__cdpModalScan());})()" % json.dumps(ROW))
    if not first:
        ui("window.__cdpTab('history')")
        time.sleep(1.5)
        ROW = "#h-list .row-item"
        first = ui("(function(){var r=document.querySelector(%s);if(!r)return null;r.click();return JSON.stringify(window.__cdpModalScan());})()" % json.dumps(ROW))
    if isinstance(first, dict):
        n0 = len(first.get("buttons") or [])
        chk("第一次点开：小窗里有一排动作栏", first.get("bars"), 1)
        # 连点同一个来源行 6 次 —— 用户报的就是"多点几次按键重复"
        scan = ui("(function(){var r=document.querySelector(%s);"
                  "for(var i=0;i<6;i++){r.click();}"
                  "return JSON.stringify(window.__cdpModalScan());})()" % json.dumps(ROW)) or {}
        chk("连点 6 次后：动作栏还是只有一排", scan.get("bars"), 1)
        chk("连点 6 次后：键位数量没变（%d 个）" % n0, len(scan.get("buttons") or []), n0)
        chk("连点 6 次后：没有重复的按键文案", scan.get("dupes"), lambda d: d == [])
        ui("(function(){var x=document.getElementById('cdp-modal-x');if(x)x.click();return 1;})()")
        time.sleep(0.3)

    print("\n[汇总：全界面扫描]")
    # 只看**信息行**：带 kind 的交互行（步骤行 ▲▼⋯、列表底部加号）按规矩就是有按键的
    allrows = ui("JSON.stringify(window.__cdpRowScan().filter(function(r){return !r.kind;}))")
    if isinstance(allrows, list):
        bad = [r for r in allrows if r["visibleButtons"]]
    chk("当前界面上所有信息行都没有可见按键（%d 行）" % len(allrows), bad, lambda b: b == [])

    # 模块自检也一起验：撞 id、没走渲染器、登记表问题
    print("\n[模块与登记表]")
    mod = ui("JSON.stringify(window.__cdpModules ? window.__cdpModules() : null)")
    if isinstance(mod, dict):
        chk("行都走 list.js 渲染（%s 行）" % mod.get("rows"), mod.get("rows") - (mod.get("rowsWithRenderer") or 0), 0)
        chk("没有撞 id（重复 id 列表为空）", mod.get("duplicateIds"), lambda d: d == [])
        chk("没有违规（violations 为空）", mod.get("violations"), lambda v: v == [])
        chk("小窗由 modal.js 渲染（若当前开着）", mod.get("modal", {}).get("renderer") in ("", "modal.js"), True)
    prob = ui("JSON.stringify(window.__cdpRegistry().problems)")
    chk("功能登记表自检 problems=[]", prob, lambda p: p == [])

    bad = [c for c in checks if not c[1]]
    print("\n结果：%d/%d 通过" % (len(checks) - len(bad), len(checks)))
    for c in bad:
        print("  未通过：" + c[0] + " → " + c[2])
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
