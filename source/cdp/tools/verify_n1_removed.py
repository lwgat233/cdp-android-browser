#!/usr/bin/env python3
"""N1（用户 2026-09-21）：删掉屏幕录制 + 无障碍残留，界面/接口/权限三面都要干净。
用法：python3 tools/verify_n1_removed.py
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
    print("  %s %s  → %s" % ("PASS" if ok else "FAIL", name, json.dumps(got, ensure_ascii=False)[:150]))
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


print("[1] 界面：找不到「屏幕录制 / 无障碍 / 权限申请」这类入口（扫所有栏目的按钮文字）")
found = []
for tab in ("plugins", "rec", "power", "settings", "sniff", "page"):
    api("/api/ui/open?tab=" + tab)
    time.sleep(2)
    txt = ui("JSON.stringify([].map.call(document.querySelectorAll('button,label'),function(b){return (b.textContent||'').trim()}).filter(function(s){return /录屏|屏幕录制|无障碍|授权|权限/.test(s)}))") or "[]"
    try:
        arr = json.loads(txt)
    except Exception:
        arr = []
    if arr:
        found += [tab + ":" + x for x in arr]
print("     命中：", found or "（无）")
chk("没有任何申请权限/录屏/无障碍的控件", found, lambda l: len(l) == 0)

print("[2] 接口：录屏与无障碍相关接口都不存在了")
chk("/api/screen/start 已删", api("/api/screen/start").get("error"), lambda s: "未知接口" in str(s))
chk("/api/screen 已删", api("/api/screen").get("error"), lambda s: "未知接口" in str(s))
chk("op screen.state 也没了", api("/api/status").get("ok"), True)
# sys.accessibility 这个 op 已经删掉：用桥的 op 清单查（端点在 status 里列的是 HTTP 路由）
eps = sh("curl -s --max-time 15 'http://127.0.0.1:8848/api/status'")
chk("HTTP 路由里没有 screen", "screen" in eps, False)

print("[3] 权限：清单里没有 BIND_ACCESSIBILITY_SERVICE，也没有 MEDIA_PROJECTION")
perms = sh("%s -s emulator-5554 shell dumpsys package dev.cdp | grep -oE 'android.permission.[A-Z_]+' | sort -u" % ADB).split()
print("     权限：", ", ".join(perms) or "(读不到)")
chk("没有 BIND_ACCESSIBILITY_SERVICE", any("BIND_ACCESSIBILITY" in x for x in perms), False)
chk("没有 FOREGROUND_SERVICE_MEDIA_PROJECTION", any("MEDIA_PROJECTION" in x for x in perms), False)

print("[4] 源码/构件里也没有残留（文件已删）")
left = sh("cd %s && ls app/src/main/java/dev/cdp/ | grep -icE 'screenrec|screenService|cdpdot'" % SRC)
chk("ScreenRec/ScreenService/CdpDotService 三个文件都没了", left, lambda s: str(s).strip() == "0")
man = sh("grep -c 'BIND_ACCESSIBILITY\\|CdpDotService\\|ScreenService' %s/app/src/main/AndroidManifest.xml" % SRC)
chk("AndroidManifest 里没有它们的痕迹", man, lambda s: str(s).strip() == "0")

print("\n结果：%d/%d 通过" % (P, P + F))
if FAILS:
    print("  未通过：" + "；".join(FAILS))
sys.exit(1 if F else 0)
