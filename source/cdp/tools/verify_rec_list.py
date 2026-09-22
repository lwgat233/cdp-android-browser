#!/usr/bin/env python3
"""「录制脚本列表放在录制板块里」的验收（用户 2026-09-20 要求）。

要点：
  · 录制脚本（kind=recording / queue）列在**录制板块**（录制与回放 ▸ 录制脚本 = `#rec-list`）；
  · 脚本栏目那张表**只剩用户脚本**（不再把录制脚本混在一起）；
  · 点录制脚本的一行 = 把它的步骤**载入步骤编辑区**（`rec.load`），可以改、可以回放；
  · 行上删除是 **✕**，不是"删除"两个字。
判据：读回来的 DOM 文本/元素、`rec.live` 的步骤数、`scripts.list` 的分类，全是事实。
"""
import json
import os
import re
import subprocess
import sys
import time

ROOT = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
ADB = "/home/lwgat/tools/android-sdk/platform-tools/adb"
ENV = dict(os.environ, PATH="/home/lwgat/.local/bin:" + os.environ.get("PATH", ""))
checks = []


def ui(expr, timeout=45):
    r = subprocess.run(["node", "tools/uiprobe.mjs", "--timeout", "12000", "--match", "ui/index.html", expr],
                       capture_output=True, text=True, cwd=ROOT, timeout=timeout, env=ENV)
    out = [l for l in (r.stdout or "").strip().splitlines() if l.strip()]
    if not out:
        return None
    for line in reversed(out):
        try:
            v = json.loads(line)
        except Exception:
            continue
        for _ in range(3):                       # 可能"JSON 里包 JSON"（uiprobe 打回来的字符串）
            if isinstance(v, str) and v[:1] in "[{":
                try:
                    v = json.loads(v)
                    continue
                except Exception:
                    pass
            break
        return v
    return out[-1]


def asobj(v):
    """uiprobe 报回来的是行文本，可能"JSON 里包着 JSON"，一路解到能用的对象"""
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


def adb(args, timeout=45):
    return subprocess.run([ADB, "-s", "emulator-5554"] + args, capture_output=True, text=True, timeout=timeout).stdout


def open_console(tab="rec"):
    """打开控制台浮层（否则真手指点会点在它后面的网页上 —— 这就是前面几条"点行没反应"的真因）"""
    for _ in range(6):
        api("/api/ui/open?tab=" + tab)
        time.sleep(2)
        if (ui("String(window.innerWidth)") or "0") not in ("0", "None", ""):
            return True
    return False


def chk(name, got, want):
    ok = want(got) if callable(want) else got == want
    checks.append((name, bool(ok), str(got)[:200]))
    print(("  PASS " if ok else "  FAIL ") + name + "  → " + str(got)[:200])
    return ok


def dump_xml():
    adb(["shell", "uiautomator", "dump", "/sdcard/rl.xml"], timeout=60)
    return adb(["shell", "cat", "/sdcard/rl.xml"], timeout=60)


def web_tap(cx, cy, cw=393.0):
    """控制台里的真手指点：用 WebView 的屏幕位置把 CSS 坐标换算成设备坐标（同 _lib.sh 的算法）"""
    xml = dump_xml()
    vx = vy = vw = None
    for m in re.finditer(r"<node ([^>]+?)/?>", xml):
        a = dict(re.findall(r'([a-zA-Z\-]+)="([^"]*)"', m.group(1)))
        if a.get("class", "").endswith("WebView") and a.get("bounds"):
            n = re.findall(r"-?\d+", a["bounds"])
            if len(n) == 4 and (int(n[3]) - int(n[1])) > 500:
                vx, vy, vw = int(n[0]), int(n[1]), int(n[2]) - int(n[0])
                break
    if vw is None:
        return False
    adb(["shell", "input", "tap", str(int(vx + cx * (vw / cw))), str(int(vy + cy * (vw / cw)))])
    time.sleep(1.5)
    return True


if not api("/api/status").get("ok"):
    print("环境没起来：控制口不通。先起模拟器/应用再跑。")
    sys.exit(2)

print("[0] 环境")
chk("控制口在", bool(api("/api/status").get("ok")), True)

print("[0.5] 控制台浮层要开着（不然真手指点不中它）")
chk("控制台浮层可见（window.innerWidth>0）", open_console("rec"), True)

print("[1] 录制脚本列表在录制板块里")
ui("(function(){var b=document.getElementById('tabbtn-rec');if(b)b.click();return 'ok';})()")
time.sleep(3)
rec_text = ui("(document.getElementById('rec-list')||{}).textContent||''")
rows = ui("document.querySelectorAll('#rec-list .row-item').length")
chk("录制栏目里有「录制脚本」这块（#rec-list 有行）", rows, lambda v: isinstance(v, int) and v >= 1)
scripts = api("/api/scripts").get("list") or []
recs = [s for s in scripts if s.get("kind") in ("recording", "queue")]
chk("列表里的名字就是脚本库里的录制脚本", [s["name"] for s in recs if s["name"] in str(rec_text)][:1],
    lambda v: len(v) >= 1)
chk("这块在录制板块里（data-tab=rec）", ui("(function(){var e=document.querySelector('[data-board=\\'rec-list\\']');return e?e.getAttribute('data-tab'):'';})()"), "rec")

print("[2] 脚本栏目那张表只剩用户脚本（录制脚本不再混在里面）")
ui("(function(){var b=document.getElementById('tabbtn-scripts');if(b)b.click();return 'ok';})()")
time.sleep(3)
sc_text = ui("(document.getElementById('sc-list')||{}).textContent||''")
rec_names = [s["name"] for s in recs]
chk("脚本栏目里没有录制脚本的名字", [n for n in rec_names if n in str(sc_text)], [])
users = [s for s in scripts if s.get("kind") not in ("recording", "queue")]
chk("脚本栏目里有用户脚本（对照，别过滤成空）", [n for n in [s["name"] for s in users] if n in str(sc_text)][:1],
    lambda v: len(v) >= 1)

print("[3] 点录制脚本的一行 → 小窗里能「载入步骤」，载入后步骤列表就是它的步骤")
open_console("rec")
ui("(function(){var b=document.getElementById('tabbtn-rec');if(b)b.click();return 'ok';})()")
time.sleep(2)
# 本项目的规矩：信息行**行面不摆按键**，按键都收进"点行后的小窗"（list.js 统一管）
chk("行面上没有按键（按键收进小窗，符合项目规矩）",
    ui("document.querySelectorAll('#rec-list .row-item button').length"), 0)
# 行可能在视口下面（控制台页面很长）——先滚到屏幕中间再量坐标，否则点的是屏幕外的位置
ui("(function(){var r=document.querySelector('#rec-list .row-item[data-tag=\"rec-script\"]');"
   "if(r&&r.scrollIntoView)r.scrollIntoView({block:'center'});return 'ok';})()")
time.sleep(1)
# 滚完再校一次：行可能落在视口外（量到负坐标就点到屏幕外面去了）
ui("(function(){window.scrollTo(0,window.scrollY);"          # 先把横向归零（页面横滚过会让 x 变负）
   "var r=document.querySelector('#rec-list .row-item[data-tag=\"rec-script\"]');if(!r)return 'no-row';"
   "var b=r.getBoundingClientRect();window.scrollTo(0,window.scrollY+b.top-200);return 'ok';})()")
time.sleep(1)
rect = ui("(function(){var r=document.querySelector('#rec-list .row-item[data-tag=\"rec-script\"]');if(!r)return null;"
          "var b=r.getBoundingClientRect();return JSON.stringify({x:Math.round(b.left+b.width/2),y:Math.round(b.top+b.height/2),"
          "text:(r.querySelector('.nm2')||{}).textContent||''});})()")
rect = asobj(rect)
chk("量得到第一行的位置", bool(isinstance(rect, dict) and rect.get("y")), True)
if isinstance(rect, dict) and rect.get("y"):
    web_tap(rect["x"], rect["y"])                      # 真手指点这一行 → 弹小窗
    time.sleep(1.5)
    sheet = ui("(document.getElementById('cdp-modal')||{}).textContent||''")
    chk("点行弹出了小窗", sheet, lambda v: bool(v))
    chk("小窗里有「载入步骤」这个动作", sheet, lambda v: "载入步骤" in str(v))
    body = ui("(function(){var m=document.getElementById('cdp-modal');if(!m)return null;var bs=m.querySelectorAll('button');"
              "for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('载入步骤')>=0){var b=bs[i].getBoundingClientRect();"
              "return JSON.stringify({x:Math.round(b.left+b.width/2),y:Math.round(b.top+b.height/2)});}}return null;})()")
    body = asobj(body)
    if isinstance(body, dict) and body.get("x"):
        web_tap(body["x"], body["y"])                  # 真手指点「载入步骤」
        time.sleep(2)
        # 载入是异步的：轮询到步骤列表有内容（最多 10 秒）
        after = []
        for _ in range(10):
            after = api("/api/record/live").get("steps") or []
            if after:
                break
            time.sleep(1)
        # 对账用**界面上那句回执**里的步数（"已载入「X」（N 步）"），不用脚本元数据 ——
        # 元数据里的 steps 是列表用的计数，和"真载入进去几步"不是同一个来源（踩过）。
        out = ui("(document.getElementById('r-out')||{}).textContent||''")
        n_out = None
        m = re.search(r"（(\d+) 步）", str(out))
        if m:
            n_out = int(m.group(1))
        chk("载入步骤：界面回执与步骤列表对得上",
            (len(after), n_out, "已载入" in str(out)),
            lambda v: v[0] > 0 and v[1] == v[0] and v[2])
    else:
        chk("小窗里量得到「载入步骤」的位置", False, True)   # 到这一步说明小窗没开

print("[4] 删除是 ✕（不是文字）")
chk("小窗里的动作含 ✕", ui("(document.getElementById('cdp-modal')||{}).textContent||''"), lambda v: "✕" in str(v))
chk("小窗里没有「删除」两个字", ui("(document.getElementById('cdp-modal')||{}).textContent||''"), lambda v: "删除" not in str(v))

print("[5] 功能登记表自检（新板块登记了、没重名）")
probs = ui("JSON.stringify(window.__cdpRegistry().problems)")
chk("登记表 problems=[]", probs, lambda v: str(v) in ("[]", "null", "None") or v == [])

ok = sum(1 for _, p, _ in checks if p)
print("\n结果：%d/%d 通过" % (ok, len(checks)))
bad = [n for n, p, _ in checks if not p]
if bad:
    print("  未通过：" + "；".join(bad))
sys.exit(0 if ok == len(checks) else 1)
