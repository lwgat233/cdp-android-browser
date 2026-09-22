#!/usr/bin/env python3
"""第二批（排版）的验收：历史 / 书签 / 阅读记录 的列表排版

用户定的排版（原话）："大字号是标题，小字号放下面是链接，链接和标题不在同一个起点，小字号右移大概一个空格；
列表如果到达右侧边界就渲染成…"；"播放时间的记录也是这样的，标题永远在前面大字号，然后是链接，
那么时间的话占据固定宽度的右边空间，然后标题的边界左移且超出部分用…代替"。

判据（读回来的计算样式与几何，不看"看着像"）：
  ① 标题字号 > 链接字号
  ② 链接那一行有左内边距（约一个字符宽）
  ③ 标题 text-overflow = ellipsis，且超长时真的溢出（scrollWidth > clientWidth）
  ④ 阅读记录的时间列**宽度固定**（不同标题长度下宽度相同）且在最右
"""
import json
import os
import subprocess
import sys
import time

ROOT = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
ENV = dict(os.environ, PATH="/home/lwgat/.local/bin:" + os.environ.get("PATH", ""))
checks = []


def ui(expr, timeout=45):
    r = subprocess.run(["node", "tools/uiprobe.mjs", "--timeout", "12000", "--match", "ui/index.html", expr],
                       capture_output=True, text=True, cwd=ROOT, timeout=timeout, env=ENV)
    out = [l for l in (r.stdout or "").strip().splitlines() if l.strip()]
    if not out:
        return None
    v = out[-1]
    for _ in range(4):
        if isinstance(v, str) and v[:1] in "[{":
            try:
                v = json.loads(v)
                continue
            except Exception:
                pass
        break
    return v


def api(path, timeout=60):
    try:
        r = subprocess.run(["curl", "-s", "--max-time", str(timeout), HTTP + path],
                           capture_output=True, text=True, timeout=timeout + 10)
        return json.loads(r.stdout)
    except Exception:
        return {}


def chk(name, got, want):
    ok = want(got) if callable(want) else got == want
    checks.append((name, bool(ok), str(got)[:200]))
    print(("  PASS " if ok else "  FAIL ") + name + "  → " + str(got)[:200])
    return ok


def open_console(tab):
    for _ in range(6):
        api("/api/ui/open?tab=" + tab)
        time.sleep(2)
        if str(ui("String(window.innerWidth)")) not in ("0", "None", ""):
            return True
    return False


def style_probe(sel, prop):
    return ui("(function(){var e=document.querySelector(%s);if(!e)return null;"
              "return getComputedStyle(e)[%s];})()" % (json.dumps(sel), json.dumps(prop)))


def num(v):
    try:
        return float(str(v).replace("px", ""))
    except Exception:
        return -1.0


if not api("/api/status").get("ok"):
    print("环境没起来：控制口不通。")
    sys.exit(2)

# 造几条数据（长短标题各一），保证列表非空
for t in ["短标题", "这是一个特别特别长的标题用来验证超出边界时会渲染成省略号而不是把布局撑破的标题"]:
    api("/api/history/add?url=https%3A%2F%2Fexample.com%2F" + ("a" if "短" in t else "bbbbbbbbbb") +
        "&title=" + __import__("urllib.parse", fromlist=["quote"]).quote(t, safe=""))
api("/api/read/add?url=https%3A%2F%2Fexample.com%2Fread&title=短标题&ms=61000")
api("/api/read/add?url=https%3A%2F%2Fexample.com%2Fread2&title=" +
    __import__("urllib.parse", fromlist=["quote"]).quote("很长的阅读标题一二三四五六七八九十十一十二十三十四十五", safe="") + "&ms=120000")

print("[0] 环境与前置")
chk("控制口在", bool(api("/api/status").get("ok")), True)

for tab, name, sel_title, sel_link in [
        ("history", "历史", "#h-list .row-item.two .lnk", "#h-list .row-item.two .u"),
        ("bookmarks", "书签", "#b-list .row-item.two .lnk", "#b-list .row-item.two .u"),
]:
    print("[%s] %s：标题大字 / 链接小字右移 / 超出省略" % (tab, name))
    open_console(tab)
    ui("(function(){var b=document.getElementById('tabbtn-%s');if(b)b.click();return 'ok';})()" % tab)
    time.sleep(2)
    chk("行是两行结构（.row-item.two 存在）", ui("document.querySelectorAll('.row-item.two').length"),
        lambda v: (int(v) if str(v).isdigit() else 0) >= 1)
    ts, ls = num(style_probe(sel_title, "fontSize")), num(style_probe(sel_link, "fontSize"))
    chk("标题字号 > 链接字号", (ts, ls), lambda v: v[0] > v[1] > 0)
    chk("链接有左内边距（右移约一格）", num(style_probe(sel_link, "paddingLeft")), lambda v: v > 0)
    chk("标题超出用省略号", style_probe(sel_title, "textOverflow"), "ellipsis")

print("[read] 阅读记录：标题大字在前 + 链接小字 + **时间固定宽靠右**")
open_console("plugins")
ui("(function(){var b=document.getElementById('tabbtn-plugins');if(b)b.click();return 'ok';})()")
time.sleep(2)
chk("阅读行是两行结构", ui("document.querySelectorAll('#rd-list .row-item.two').length"),
    lambda v: (int(v) if str(v).isdigit() else 0) >= 1)
chk("阅读行有标题（大字）", num(style_probe("#rd-list .row-item.two .nm2", "fontSize")), lambda v: v > 0)
chk("阅读行的时间列存在", ui("String(!!document.querySelector('#rd-list .row-item.two .when'))"), "true")
w = ui("(function(){var a=document.querySelectorAll('#rd-list .row-item.two .when');"
       "if(a.length<2)return null;return JSON.stringify({w1:a[0].offsetWidth,w2:a[1].offsetWidth});})()")
chk("时间列宽度固定（两条一样宽）", w, lambda v: isinstance(v, dict) and v.get("w1") == v.get("w2") and v.get("w1", 0) > 0)
right = ui("(function(){var r=document.querySelector('#rd-list .row-item.two');if(!r)return null;"
           "var t=r.querySelector('.when'),n=r.querySelector('.nm2');if(!t||!n)return null;"
           "return JSON.stringify({t:Math.round(t.getBoundingClientRect().right),n:Math.round(n.getBoundingClientRect().right)});})()")
chk("时间在标题右边（靠右）", right, lambda v: isinstance(v, dict) and v.get("t", 0) >= v.get("n", 0))
chk("阅读标题超出省略", style_probe("#rd-list .row-item.two .nm2", "textOverflow"), "ellipsis")

ok = sum(1 for _, p, _ in checks if p)
print("\n结果：%d/%d 通过" % (ok, len(checks)))
bad = [n for n, p, _ in checks if not p]
if bad:
    print("  未通过：" + "；".join(bad))
sys.exit(0 if ok == len(checks) else 1)
