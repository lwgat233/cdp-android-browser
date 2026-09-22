#!/usr/bin/env python3
"""第 16 条（自代理统流量）+ 第 24 条（不再申请无障碍/录制权限）—— 一起验。
用法：python3 tools/verify_selfproxy_perm.py
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
    print("  %s %s  → %s" % ("PASS" if ok else "FAIL", name, json.dumps(got, ensure_ascii=False)[:160]))
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


# 注意：**不能用 127.0.0.1** —— WebView 对回环地址会自动绕过代理，流量不会过中继（踩过，字节恒 0）。
# 用 10.0.2.2（模拟器眼里的"宿主机"，非回环）就能真的走代理，而且仍然是本地资源、不用外网。
TEST_PAGE = "http://10.0.2.2:8848/api/_test/hls/page.html"

print("[1] 第 24 条：界面里不再有申请「无障碍 / 录制权限」的入口（渲染面 + 结构面）")
api("/api/ui/open?tab=record")
time.sleep(3)
btns = ui("JSON.stringify([].map.call(document.querySelectorAll('button'),function(b){return b.textContent.trim()}))") or "[]"
chk("按钮清单里没有「无障碍」字样的键", "无障碍" in btns, False)
chk("按钮清单里没有「去开…权限」这类键", ("权限" in btns), False)
chk("原来那个按键真的不在了（id=dot-perm）", ui("String(!!document.getElementById('dot-perm'))"), "false")
chk("「开 / 关小点」还在（功能没被误删）", ui("String(!!document.getElementById('dot-toggle'))"), "true")

print("[2] 第 24 条：权限清单（把话说到位：声明了但不申请、不依赖）")
pm = sh("%s -s emulator-5554 shell dumpsys package dev.cdp | grep -i 'granted=true' | head -12" % ADB)
perms = sh("%s -s emulator-5554 shell dumpsys package dev.cdp | grep -oE 'android.permission.[A-Z_]+' | sort -u" % ADB).split()
print("     权限：", ", ".join(perms) or "(读不到)")
chk("没有 RECORD_AUDIO（不再需要录音权限）", "android.permission.RECORD_AUDIO" in perms, False)
chk("没有 SYSTEM_ALERT_WINDOW（不需要悬浮窗权限）", "android.permission.SYSTEM_ALERT_WINDOW" in perms, False)
chk("无障碍权限只是「声明了」（不申请也不依赖，界面无入口）",
    "android.permission.BIND_ACCESSIBILITY_SERVICE" in perms, True)

print("[3] 第 16 条：开自代理 → 浏览器流量过本地中继，统计里能看到「来自自代理的字节」")
st0 = api("/api/net/stats?range=hour")
before = st0.get("proxyDown") or 0
api("/api/settings/set?proxyType=none&selfProxy=1")
# 中继起来要几百毫秒，而且日志是环形的 → **轮询**读"端口 > 0"，再看启动那一条（别拿最后一条碰运气）
port = 0
for _ in range(12):
    time.sleep(1.5)
    port = api("/api/status").get("proxyPort") or 0
    if port > 0:
        break
log = api("/api/log?n=60")
lines = [x.get("text", "") if isinstance(x, dict) else str(x) for x in (log.get("lines") or log.get("list") or [])]
started = [l for l in lines if "本地中继已启动" in l]
print("     启动日志：", (started[-1][-90:] if started else "(没读到)"))
chk("日志里看到「本地中继已启动」", bool(started), True)
chk("控制口能读到中继端口 > 0", port, lambda p: isinstance(p, int) and p > 0)

print("     （浏览一个本地测试页，让流量真的过中继）")
api("/api/nav/open?url=" + TEST_PAGE.replace(":", "%3A").replace("/", "%2F"))
# 中继是在**连接结束**时报字节的 → 轮询等它涨上来
st1 = {}
after = before
for _ in range(12):
    time.sleep(2)
    st1 = api("/api/net/stats?range=hour")
    after = st1.get("proxyDown") or 0
    if after > before:
        break
print("     自代理下行：开前 %s → 开后 %s（总下行 %s）" % (before, after, st1.get("bytesDown")))
chk("自代理字节涨了（页面流量真的过中继）", after > before, True)
chk("统计接口里有 proxyDown / proxyUp 两项", ("proxyDown" in st1) and ("proxyUp" in st1), True)

print("[4] 第 16 条：关掉自代理 → 不再往中继上加字节")
api("/api/settings/set?selfProxy=0")
time.sleep(3)
d0 = (api("/api/net/stats?range=hour").get("proxyDown") or 0)
api("/api/nav/open?url=" + TEST_PAGE.replace(":", "%3A").replace("/", "%2F") + "%3Fnocache%3D2")
time.sleep(6)
d1 = (api("/api/net/stats?range=hour").get("proxyDown") or 0)
print("     关掉后：%s → %s" % (d0, d1))
chk("关掉后自代理字节不再增长（差值为 0）", d1 - d0, 0)
chk("关掉后代理端口已释放", api("/api/status").get("proxyPort"), lambda p: (p or 0) == 0)

print("\n结果：%d/%d 通过" % (P, P + F))
if FAILS:
    print("  未通过：" + "；".join(FAILS))
sys.exit(1 if F else 0)
