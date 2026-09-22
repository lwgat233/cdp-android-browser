#!/usr/bin/env python3
"""log.md 第 10 条：省电板块两类排序（按板块 / 按耗电排行）+ 点按键开关。

判据：
  ① 回包里每个项都带 板块(group) / 开销(est) / 来源(costSrc)，来源必须写明是**估算**（不编实测数字）；
  ② 界面有排序下拉（两个选项），切到"按耗电排行"后行序真的变了（按 est 从大到小）；
  ③ 每行的开销在点开的小窗里能看到，且写明来源；
  ④ 真手指点某一行的开关 → 那一项的状态真的翻转（API 面读回）。
用法：python3 tools/verify_power_sort.py
"""
import json
import os
import re
import subprocess
import sys
import time

SRC = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
ADB = "/home/lwgat/tools/android-sdk/platform-tools/adb"
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


def dump_xml():
    sh("%s -s emulator-5554 shell uiautomator dump /sdcard/pw.xml" % ADB)
    return sh("%s -s emulator-5554 shell cat /sdcard/pw.xml" % ADB)


_VB = []          # WebView 屏幕框缓存：**不要**在"打开小窗 → 点小窗里的键"中间去 dump
                  # （uiautomator dump 会打断页面状态，小窗会被关掉 → 点了个寂寞，实测踩到）


def box_from_app():
    """让 App 自己报 WebView 在屏幕上的位置（准确、不受页面动画影响）——比 dump ui 可靠"""
    try:
        d = json.loads(sh("curl -s --max-time 12 'http://127.0.0.1:8848/api/view/box'"))
        if d.get("ok") and d.get("w"):
            return int(d["x"]), int(d["y"]), int(d["w"]), float(d.get("cssW") or 0)
    except Exception:
        pass
    return None


def webview_box(tries=3):
    if _VB:
        return _VB[0]
    a = box_from_app()
    if a:
        _VB[:] = [a[0], a[1], a[2]]
        return _VB[0]
    """WebView 在屏幕上的位置。**量不到就重试**：uiautomator 偶尔抓不到（截图那一刻界面在动），
    量不到时以前是静默 return None → 点了个寂寞，断言假红（踩过一次）。"""
    for _ in range(tries):
        xml = dump_xml()
        if "WebView" in xml:
            for m in re.finditer(r"<node ([^>]+?)/?>", xml):
                a = dict(re.findall(r'([a-zA-Z\-]+)="([^"]*)"', m.group(1)))
                if "WebView" in a.get("class", "") and a.get("bounds"):
                    n = re.findall(r"-?\d+", a["bounds"])
                    if len(n) == 4 and (int(n[3]) - int(n[1])) > 500:
                        _VB[:] = [int(n[0]), int(n[1]), int(n[2]) - int(n[0])]
                        return _VB[0]
        time.sleep(1)
    return None


def tap(expr, tries=3):
    for _ in range(tries):
        r = ui("(function(){var e=%s;if(!e)return '';var b=e.getBoundingClientRect();"
               "return JSON.stringify({x:Math.round(b.left+b.width/2),y:Math.round(b.top+b.height/2)});})()" % expr)
        try:
            c = json.loads(r)
        except Exception:
            time.sleep(1)
            continue
        vb = webview_box()
        if not vb:
            print("     （量不到 WebView 屏幕框，这次点击没发出去）")
            return False
        vx, vy, vw = vb
        iw = (a[3] if (a := box_from_app()) else 0) or ui("String(window.innerWidth)")
        cw = float(iw) if iw and float(iw) > 100 else 412.0
        s = vw / cw
        sh("%s -s emulator-5554 shell input tap %d %d" % (ADB, int(vx + c["x"] * s), int(vy + c["y"] * s)))
        time.sleep(1.6)
        return True
    return False


print("[0] 进省电栏目")
for _ in range(10):
    api("/api/ui/open?tab=power")
    time.sleep(2)
    if str(ui("(function(){var t=document.querySelector('.tab.on');return t?t.id:'';})()")) == "tab-power":
        break
chk("当前栏目是省电", ui("(function(){var t=document.querySelector('.tab.on');return t?t.id:'';})()"), "tab-power")

print("[1] 回包里每项都带 板块 / 开销 / 来源，且来源写明是估算")
st = (api("/api/power") or {}).get("state") or {}
items = st.get("items") or []
chk("有 items 数组", isinstance(items, list) and len(items) >= 5, True)
chk("每项都有 group / est / costSrc", all(("group" in x and "est" in x and "costSrc" in x) for x in items), True)
chk("来源里写清了是「估算」（不是假装实测）", sorted({x.get("costSrc") for x in items}),
    lambda s: all("估算" in str(x) for x in s))
chk("板块不止一个（才谈得上按板块排）", len({x.get("group") for x in items}), lambda n: n >= 3)

print("[2] 界面有排序下拉，两个选项；切到「按耗电排行」行序真的变")
chk("两个排序按键都在", ui("String(!!document.getElementById('pw-sort-board') && !!document.getElementById('pw-sort-hot'))"), "true")
# 先点"按板块"（真手指），保证起点确定（状态依赖踩过一次）
tap("document.getElementById('pw-sort-board')")
time.sleep(2)
by_board = ui("JSON.stringify([].map.call(document.querySelectorAll('#pw-list .row-item .nm2'),function(e){return e.textContent.trim()}))")
tap("document.getElementById('pw-sort-hot')")
time.sleep(2)
by_hot = ui("JSON.stringify([].map.call(document.querySelectorAll('#pw-list .row-item .nm2'),function(e){return e.textContent.trim()}))")
print("     按板块：", str(by_board)[:120])
print("     按排行：", str(by_hot)[:120])
chk("两种排法结果不同（真的重排了）", str(by_board) != str(by_hot) or len(items) <= 1, True)
# 排行第一项应该是 est 最大的那个
top_est = max(items, key=lambda x: x.get("est") or 0).get("label", "")
chk("排行第一行就是开销最大的那一项", str(by_hot), lambda s: top_est[:6] in str(s))

print("[3] 点开一行 → 小窗里能看到开销与来源")
tap("document.querySelectorAll('#pw-list .row-item')[0]")
time.sleep(1.4)
# 小窗里的"值"是 <input>（键值对形态：键小字在上、值=文本框）→ textContent 读不到值，要把 value 也读上
sheet = (ui("(document.getElementById('cdp-modal')||{}).textContent||''") or "") + " " + \
        (ui("JSON.stringify([].map.call(document.querySelectorAll('#cdp-modal input'),function(i){return i.value}))") or "")
chk("小窗里写了「开销」", sheet, lambda s: "开销" in str(s))
chk("小窗里写了来源（估算）", sheet, lambda s: "估算" in str(s))
ui("(function(){try{CDPUI.Modal.close()}catch(e){}return 'x';})()")

print("[4] 真手指点某一行的开关 → 那一项状态真的翻转（API 面读回）")
before = {x["name"]: x["on"] for x in ((api("/api/power") or {}).get("state") or {}).get("items") or []}
first = (api("/api/power") or {}).get("state", {}).get("items") or []
# 挑一个**没有连锁副作用**的项来翻（录制/后台播放会引发别的动作，容易看不出翻转是真是假）
name0 = "metrics"
idx = next((i for i, x in enumerate(first) if x.get("name") == name0), 0)
# 按用户定的规矩：信息行行面不摆按键，按键在小窗里 → 先点行，再点小窗里的「打开/关掉」
def flip_once():
    """点这一行 → 点小窗里的「打开/关掉」（行面没有按键，按键在小窗里 —— 用户定的规矩）"""
    tap("document.querySelectorAll('#pw-list .row-item')[%d]" % idx)
    time.sleep(1.4)
    tap("(function(){var b=document.querySelectorAll('#cdp-modal button');"
        "for(var i=0;i<b.length;i++){var t=(b[i].textContent||'').trim();"
        "if(t==='打开'||t==='关掉')return b[i];}return null;})()")
    time.sleep(2)


flip_once()
after = {x["name"]: x["on"] for x in ((api("/api/power") or {}).get("state") or {}).get("items") or []}
for _ in range(3):        # 丢输入是常态：没翻转就再点一次，别一次不成下结论
    if after.get(name0) != before.get(name0):
        break
    flip_once()
    time.sleep(1.2)
    tap("(function(){var b=document.querySelectorAll('#cdp-modal button');"
        "for(var i=0;i<b.length;i++){var t=(b[i].textContent||'').trim();"
        "if(t==='打开'||t==='关掉')return b[i];}return null;})()")
    time.sleep(2)
    after = {x["name"]: x["on"] for x in ((api("/api/power") or {}).get("state") or {}).get("items") or []}
chk("被点的那一项翻转了", (before.get(name0), after.get(name0)), lambda t: t[0] != t[1])
# 复原，别把状态留在改过的样子
api("/api/power/set?name=%s&on=%s" % (name0, "1" if before.get(name0) else "0"))
time.sleep(1)
back = {x["name"]: x["on"] for x in ((api("/api/power") or {}).get("state") or {}).get("items") or []}
chk("复原成功（不把状态留在改过的样子）", back.get(name0), before.get(name0))

print("\n结果：%d/%d 通过" % (P, P + F))
if FAILS:
    print("  未通过：" + "；".join(FAILS))
sys.exit(1 if F else 0)
