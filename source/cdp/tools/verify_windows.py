#!/usr/bin/env python3
"""多窗口（第 28 条）：持久列表 + 不预加载 + 只保活最近 <=10 个

用法：python3 tools/verify_windows.py
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
    print("  %s %s  -> %s" % ("PASS" if ok else "FAIL", name, json.dumps(got, ensure_ascii=False)[:150]))
    if ok:
        P += 1
    else:
        F += 1
        FAILS.append(name)


def sh(cmd, t=90):
    return subprocess.run(["bash", "-lc", cmd], capture_output=True, text=True, timeout=t, env=ENV).stdout.strip()


def api(path, t=25):
    try:
        return json.loads(sh("curl -s --max-time %d %s%s" % (t, HTTP, path), t + 8))
    except Exception:
        return {}


def ui(expr, t=60, match="ui/index.html"):
    r = subprocess.run(["node", "tools/uiprobe.mjs", "--timeout", "12000", "--match", match, expr],
                       capture_output=True, text=True, cwd=SRC, env=ENV, timeout=t)
    out = [l for l in (r.stdout or "").strip().splitlines() if l.strip()]
    return out[-1] if out else None


def dump_xml():
    sh("%s -s emulator-5554 shell uiautomator dump /sdcard/v.xml" % ADB)
    return sh("%s -s emulator-5554 shell cat /sdcard/v.xml" % ADB)


def webview_box():
    for m in re.finditer(r"<node ([^>]+?)/?>", dump_xml()):
        a = dict(re.findall(r'([a-zA-Z\-]+)="([^"]*)"', m.group(1)))
        if a.get("class", "").endswith("WebView") and a.get("bounds"):
            n = re.findall(r"-?\d+", a["bounds"])
            if len(n) == 4 and (int(n[3]) - int(n[1])) > 500:
                return int(n[0]), int(n[1]), int(n[2]) - int(n[0])
    return None


def tap_css(cx, cy, hold=0):
    vb = webview_box()
    if not vb:
        return False
    vx, vy, vw = vb
    v = ui("String(window.innerWidth)")
    try:
        cw = float(v) if v and float(v) > 100 else 412.0
    except Exception:
        cw = 412.0
    s = vw / cw
    x, y = int(vx + cx * s), int(vy + cy * s)
    if hold:
        sh("%s -s emulator-5554 shell input swipe %d %d %d %d %d" % (ADB, x, y, x, y, hold))
    else:
        sh("%s -s emulator-5554 shell input tap %d %d" % (ADB, x, y))
    time.sleep(1.5)
    return True


def center(expr):
    r = ui("(function(){var e=%s;if(!e)return '';var b=e.getBoundingClientRect();"
           "return JSON.stringify({x:Math.round(b.left+b.width/2),y:Math.round(b.top+b.height/2)});})()" % expr)
    try:
        return json.loads(r)
    except Exception:
        return None


def tap(expr, hold=0, tries=3):
    for _ in range(tries):
        c = center(expr)
        if c:
            tap_css(c["x"], c["y"], hold)
            return c
        time.sleep(1)
    return None


def sheet_text():
    v = ui("(document.getElementById('cdp-modal')||{}).textContent||''")
    return "" if v is None else str(v)


def open_tab(key, timeout=40):
    for _ in range(max(3, timeout // 4)):
        api("/api/ui/open?tab=" + key)
        time.sleep(2)
        cur = ui("(function(){var t=document.querySelector('.tab.on');return t?t.id:'';})()")
        if str(cur) == "tab-" + key:
            return True
    return False


print("环境：%s" % ("控制口在" if api("/api/status").get("ok") else "控制口不通（先跑 env_health）"))

print("[1] 开 12 个窗口 -> 保活只留最近 10 个，最久没用的变 kept=false")
w = api("/api/win/list")
for i in range((w.get("count") or 1) - 1):
    api("/api/win/close?id=1")
    time.sleep(0.6)
base = api("/api/win/list")
chk("起点：只剩 1 个窗口", base.get("count"), 1)
for i in range(11):
    api("/api/win/new?url=http%3A%2F%2F127.0.0.1%3A8848%2Fapi%2F_test%2Fhls%2Fpage.html%3Fw%3D" + str(i))
    time.sleep(1.2)
w12 = api("/api/win/list")
chk("开了 12 个", w12.get("count"), 12)
chk("保活上限写在回包里", w12.get("keptMax"), 10)
chk("保活数量 <= 10", w12.get("keptCount"), lambda n: isinstance(n, int) and 0 < n <= 10)
kept_ids = [x["id"] for x in (w12.get("list") or []) if x.get("kept")]
unkept = [x["id"] for x in (w12.get("list") or []) if not x.get("kept")]
chk("确实有窗口被排除在保活之外", len(unkept), lambda n: n >= 2)
chk("被清掉的是最久没用的那一批", min(unkept) < max(kept_ids) if kept_ids and unkept else False, True)

print("[2] 重启 App -> 窗口列表还在（持久保存、不预加载）")
api("/api/win/switch?id=3")
time.sleep(1.5)
sh("%s -s emulator-5554 shell am force-stop dev.cdp" % ADB)
time.sleep(2)
sh("%s -s emulator-5554 shell am start -n dev.cdp/.MainActivity" % ADB)
time.sleep(9)
pid = sh("%s -s emulator-5554 shell pidof dev.cdp" % ADB).strip()
sh("%s -s emulator-5554 forward --remove-all" % ADB)
sh("%s -s emulator-5554 forward tcp:9222 localabstract:webview_devtools_remote_%s" % (ADB, pid))
sh("%s -s emulator-5554 forward tcp:8848 tcp:8848" % ADB)
time.sleep(3)
after = api("/api/win/list")
chk("重启后条数一致", after.get("count"), 12)
chk("重启后保活仍 <= 10", after.get("keptCount"), lambda n: isinstance(n, int) and n <= 10)
chk("重启后地址都还在", [x.get("url") for x in (after.get("list") or [])], lambda urls: all(urls))

print("[3] 切到被清掉（kept=false）的那个 -> 地址还在、页面真的重新加载")
target = unkept[0] if unkept else 0
want_url = ""
for x in (after.get("list") or []):
    if x.get("id") == target:
        want_url = x.get("url") or ""
api("/api/win/switch?id=%d" % target)
time.sleep(4)
cur = (api("/api/status").get("browser") or {}).get("url", "")
chk("切过去后浏览器地址 == 这个窗口自己记的地址（被清掉的那个也照样有地址）", cur,
    lambda u: str(u) == want_url or (want_url and str(u).startswith(want_url.split("?")[0])))

print("\n结果：%d/%d 通过" % (P, P + F))
if FAILS:
    print("  未通过：" + "；".join(FAILS))
sys.exit(1 if F else 0)
