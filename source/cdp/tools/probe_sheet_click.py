#!/usr/bin/env python3
"""专盯一件事：**小窗（#cdp-modal）里的按键，点了到底有没有用**。

用户反复报的就是这个（"下载里面的小窗口按键也没用" / "m3u8 点小窗播放按键没效果" / "重复实验一下按键问题"）。
这个探针把每一步的事实一次读全，避免来回猜：
  ① 小窗的可见性 / z-index / 位置（被谁盖住没有）
  ② 目标按键的中心点上**真正**是哪个元素（elementFromPoint）
  ③ 用 JS 点（.click()）有没有触发到 op
  ④ 用真输入点（cdp.mjs tap，Input.synthesizeTapGesture）有没有触发到 op
  ⑤ 点完界面/页面发生了什么（#sn-out、当前 URL、控制台是否收起）
用法：python3 tools/probe_sheet_click.py [行选择器] [按键文案]
"""
import json
import os
import subprocess
import sys
import time
import urllib.parse

ROOT = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
ENV = dict(os.environ, PATH="/home/lwgat/.local/bin:" + os.environ.get("PATH", ""))
ROW = sys.argv[1] if len(sys.argv) > 1 else "#sn-list .row-item"
LABEL = sys.argv[2] if len(sys.argv) > 2 else "播放"


def ui(expr, timeout=45):
    r = subprocess.run(["node", "tools/uiprobe.mjs", "--timeout", "12000", "--match", "ui/index.html", expr],
                       capture_output=True, text=True, cwd=ROOT, timeout=timeout, env=ENV)
    out = [l for l in (r.stdout or "").strip().splitlines() if l.strip()]
    return out[-1] if out else None


def js(body):
    return ui("(function(){" + body + "return 'ok';})()")


def api(path, timeout=40):
    try:
        return json.loads(subprocess.run(["curl", "-s", "--max-time", str(timeout), HTTP + path],
                                         capture_output=True, text=True, timeout=timeout + 10).stdout)
    except Exception:
        return {}


def cdp_tap(x, y):
    r = subprocess.run(["node", "tools/cdp.mjs", "tap", "--target", "ui/index.html", "--x", str(x), "--y", str(y)],
                       capture_output=True, text=True, cwd=ROOT, env=ENV, timeout=60)
    return (r.stdout or "").strip()[:120]


if not api("/api/status").get("ok"):
    print("环境没起来：控制口不通。")
    sys.exit(2)

print("== 0. 打开控制台并把小窗点出来 ==")
for _ in range(6):
    api("/api/ui/open?tab=sniff")
    time.sleep(2)
    if str(ui("String(window.innerWidth)")) not in ("0", "None", ""):
        break
js("try{CDPUI.Modal.close()}catch(e){}var b=document.getElementById('tabbtn-sniff');if(b)b.click();")
time.sleep(2)
js("var r=document.querySelector(%s);if(r)r.click();" % json.dumps(ROW))
time.sleep(1.5)
print("  小窗文字:", str(ui("(document.getElementById('cdp-modal')||{}).textContent||''"))[:70])

print("== 1. 小窗本身：可见性 / 层级 / 位置 ==")
print("  ", ui("(function(){var m=document.getElementById('cdp-modal');if(!m)return '没有这个元素';"
               "var cs=getComputedStyle(m),r=m.getBoundingClientRect();"
               "return JSON.stringify({display:cs.display,z:cs.zIndex,pos:cs.position,"
               "top:Math.round(r.top),bottom:Math.round(r.bottom),h:Math.round(r.height)});})()"))

print("== 2. 目标按键的中心点上到底是谁 ==")
info = ui("(function(){var m=document.getElementById('cdp-modal');if(!m)return null;"
          "var bs=m.querySelectorAll('button'),b=null;"
          "for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf(%s)>=0)b=bs[i];}"
          "if(!b)return null;var r=b.getBoundingClientRect();var cx=Math.round(r.left+r.width/2),cy=Math.round(r.top+r.height/2);"
          "var e=document.elementFromPoint(cx,cy);"
          "return JSON.stringify({x:cx,y:cy,rect:[Math.round(r.left),Math.round(r.top),Math.round(r.width),Math.round(r.height)],"
          "hit:e?(e.tagName+'.'+(e.className||'')):'null',hitSelf:(e===b)});})()" % json.dumps(LABEL))
print("  ", info)

print("== 3. 用 JS .click() 点（看有没有走到 op） ==")
js("window.__log=[];if(!window.__wrapped){var o=window.CDPT.call;window.CDPT.call=function(op,a){window.__log.push(op);return o(op,a);};window.__wrapped=1;}")
js("var m=document.getElementById('cdp-modal');var bs=m.querySelectorAll('button');"
   "for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf(%s)>=0){bs[i].click();break;}}" % json.dumps(LABEL))
time.sleep(3)
print("   调过的 op:", ui("JSON.stringify(window.__log||[])"))
print("   当前页:", (api("/api/status").get("browser") or {}).get("url", "")[:80])
print("   控制台收起没:", ui("String(window.innerWidth)"))

print("== 4. 用真输入点（cdp.mjs tap，坐标就是上面量到的） ==")
if info and info.startswith("{"):
    d = json.loads(info)
    api("/api/ui/open?tab=sniff")
    time.sleep(3)
    js("try{CDPUI.Modal.close()}catch(e){}var b=document.getElementById('tabbtn-sniff');if(b)b.click();")
    time.sleep(2)
    js("var r=document.querySelector(%s);if(r)r.click();" % json.dumps(ROW))
    time.sleep(1.5)
    js("window.__log=[];")
    print("   tap:", cdp_tap(d["x"], d["y"]))
    time.sleep(3)
    print("   调过的 op:", ui("JSON.stringify(window.__log||[])"))
    print("   当前页:", (api("/api/status").get("browser") or {}).get("url", "")[:80])
    print("   控制台收起没:", ui("String(window.innerWidth)"))
    print("   界面回执:", str(ui("(document.getElementById('sn-out')||{}).textContent||''"))[:90])
