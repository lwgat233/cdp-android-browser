#!/usr/bin/env python3
"""用户 2026-09-21 报的两条：N3（下到系统「下载/cdp」目录 + 清空旧的应用内下载）、N4（首页小 app 两个入口都要能渲染）。

判据：
 N3-1 下载一个文件 → **系统 /sdcard/Download/cdp/ 里真的有它**（adb 直接查文件系统，不看回包自述）
 N3-2 下载记录里那条记的是**系统路径**（Download/cdp/…），不是 /data/user/0/…/downloads/…
 N3-3 应用目录里的旧下载已被清空（升级后清一次：filesDir/downloads 为空；标记写进设置）
 N4-1 首页两个入口（本机控制口 / 内置资源）都能渲染出格子
 N4-2 添加一个 → 立刻能看到（DOM 格子数 = 列表条数 + 1）
 N4-3 从**内置资源**入口打开时，控制口不可达也不静默空白：要么有格子，要么有一句明确的提示
用法：python3 tools/verify_download_sysdir_and_home.py
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


def ui(expr, t=60, match="ui/start.html"):
    r = subprocess.run(["node", "tools/uiprobe.mjs", "--timeout", "12000", "--match", match, expr],
                       capture_output=True, text=True, cwd=SRC, env=ENV, timeout=t)
    out = [l for l in (r.stdout or "").strip().splitlines() if l.strip()]
    return out[-1] if out else None


TEST = "http://127.0.0.1:8848/api/_test/hls/page.html"

print("[1] N3：下载一个文件 → 系统「下载/cdp」里真的有它")
before = sh("%s -s emulator-5554 shell ls /sdcard/Download/cdp/ 2>/dev/null | wc -l" % ADB)
api("/api/settings/set?downloadVia=app")            # 先确保走应用内下载（下完由 App 发布到系统目录）
time.sleep(1.5)
api("/api/download?url=" + TEST.replace(":", "%3A").replace("/", "%2F") + "&name=sysdir-test.html")
rec = {}
for _ in range(12):
    time.sleep(2)
    recs = api("/api/downloads").get("list") or []
    hit = [x for x in recs if x.get("name") == "sysdir-test.html"]
    if hit and ("完成" in str(hit[-1].get("state")) or "失败" in str(hit[-1].get("state"))):
        rec = hit[-1]
        break
print("     记录：", json.dumps({k: rec.get(k) for k in ("name", "state", "path", "publicPath")}, ensure_ascii=False)[:180])
chk("系统目录里出现了这个文件", sh("%s -s emulator-5554 shell ls /sdcard/Download/cdp/sysdir-test.html 2>/dev/null" % ADB),
    lambda s: "sysdir-test" in str(s))
chk("下载记录里记的是系统路径（Download/cdp）", rec.get("publicPath") or rec.get("path"),
    lambda s: "Download/cdp" in str(s))

print("[2] N3：应用目录里的旧下载已清空（升级后清一次）")
old = sh("%s -s emulator-5554 shell run-as dev.cdp ls files/downloads 2>/dev/null | wc -l" % ADB)
print("     files/downloads 里还有：", old)
chk("应用目录已清空（0 个）", old, lambda s: str(s).strip() in ("0", ""))

print("[3] N4：首页两个入口都要能渲染出格子")
api("/api/nav/open?url=" + "http://127.0.0.1:8848/ui/start.html".replace(":", "%3A").replace("/", "%2F"))
time.sleep(4)
n_ctrl = ui("String(document.querySelectorAll('#apps .app').length)")
chk("控制口入口：有格子", n_ctrl, lambda s: s and int(s) >= 2)
chk("控制口入口：列表条数读得到", api("/api/apps/list").get("ok"), True)

api("/api/nav/open?url=" + "https://appassets.androidplatform.net/ui/start.html".replace(":", "%3A").replace("/", "%2F"))
time.sleep(4)
n_asset = ui("String(document.querySelectorAll('#apps .app').length)", match="ui/start.html")
note = ui("(document.getElementById('appgrid-note')||{}).textContent||''", match="ui/start.html") or ""
print("     内置资源入口：格子 =", n_asset, "｜提示 =", str(note)[:60])
chk("内置资源入口：要么有格子，要么有一句明确提示（不许静默空白）",
    (str(n_asset) not in ("0", "None", "")) or ("读不到" in str(note)), True)

print("[4] N4：添加一个 → 立刻可见")
before_n = len((api("/api/apps/list").get("apps") or []))
api("/api/apps/save?name=sysdir测试站点&url=http%3A%2F%2F10.0.2.2%3A8848%2Fui%2Fstart.html")
time.sleep(2)
apps = api("/api/apps/list").get("apps") or []
chk("列表里多了一条", len(apps), before_n + 1)
api("/api/nav/open?url=" + "http://127.0.0.1:8848/ui/start.html".replace(":", "%3A").replace("/", "%2F"))
time.sleep(4)
dom = ui("String(document.querySelectorAll('#apps .app').length)")
chk("页面上也画出来了（格子数 = 列表数 + 1）", dom, lambda s: s and int(s) == len(apps) + 1)
# 收尾：把测试条目删掉
tid = [a.get("id") for a in apps if "sysdir" in str(a.get("name"))]
if tid:
    api("/api/apps/remove?id=%s" % tid[0])

print("\n结果：%d/%d 通过" % (P, P + F))
if FAILS:
    print("  未通过：" + "；".join(FAILS))
sys.exit(1 if F else 0)
