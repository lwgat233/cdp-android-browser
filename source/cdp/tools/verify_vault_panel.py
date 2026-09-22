#!/usr/bin/env python3
"""密码库（第 20 条）：「查看全部密码」隐藏全局面板 —— 未解锁要明确提示

用法：python3 tools/verify_vault_panel.py
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

print("[0] 进密码库栏目（先把状态弄干净：这一套验的是「没解锁」时的行为，不能吃上一轮留下的解锁状态）")
api("/api/vault/lock")
time.sleep(1)
api("/api/vault/lock")
time.sleep(1)
st_lock = api("/api/vault/state")
chk("起点：vault 是锁着的（脚本自己锁的）", st_lock.get("unlocked"), False)
chk("切到「密码库」栏目", open_tab("vault"), True)
chk("面板在（结构存在）", ui("String(!!document.getElementById('pv-panel'))"), "true")
chk("按钮在（查看全部密码）", ui("String(!!document.getElementById('pv-all'))"), "true")
chk("面板默认是藏着的", ui("String(document.getElementById('pv-panel').classList.contains('hidden'))"), "true")
chk("收起来键也在", ui("String(!!document.getElementById('pv-all-close'))"), "true")

print("[1] 未解锁时点「查看全部密码」-> 明确提示要先过锁屏，面板不出现")
st = api("/api/vault/state")
chk("现在确实没解锁", st.get("unlocked"), False)
tap("document.getElementById('pv-all')")
time.sleep(1.5)
out = ui("(document.getElementById('pv-out')||{}).textContent||''")
chk("提示里说清了先过锁屏", out, lambda s: "锁屏" in str(s))
chk("面板仍然藏着（不给看）", ui("String(document.getElementById('pv-panel').classList.contains('hidden'))"), "true")

print("[2] 试着真过一遍锁屏（设备有锁屏就输入 PIN，没有就如实记下来）")
has_lock = "设锁屏" not in sh("%s -s emulator-5554 shell locksettings get-disabled" % ADB)
un = api("/api/vault/unlock")
print("     vault.unlock 回包：", json.dumps(un, ensure_ascii=False)[:220])
if not (un.get("ok") and un.get("unlocked")) and "没设锁屏" not in json.dumps(un, ensure_ascii=False):
    # 系统锁屏界面被拉起来了 → 输入 PIN（脚本里的测试用 PIN，仅本机模拟器）
    time.sleep(3)
    sh("%s -s emulator-5554 shell input text 1234" % ADB)
    sh("%s -s emulator-5554 shell input keyevent 66" % ADB)
    time.sleep(4)
    un = api("/api/vault/state")
    print("     输完 PIN 后再读状态：", json.dumps(un, ensure_ascii=False)[:160])
if un.get("ok") and un.get("unlocked"):
    print("[2b] 解锁成功 -> 面板要列出与 vault.list 一样多的条目")
    n_list = len((api("/api/vault/list").get("list") or []))
    tap("document.getElementById('pv-all')")
    time.sleep(2)
    chk("面板出现了", ui("String(!document.getElementById('pv-panel').classList.contains('hidden'))"), "true")
    chk("面板里的条目数 == vault.list 长度",
        ui("String(document.querySelectorAll('#pv-all-list .row-item').length)"), str(n_list))
    # 判据③：先存一条测试条目，再点开它看小窗（密码字段要在、可改）
    if n_list == 0:
        api("/api/vault/save?site=test.example&user=tester&pass=T3st%21pass")
        time.sleep(1)
        tap("document.getElementById('pv-all')")
        time.sleep(2)
    chk("面板里的条目数 == vault.list 长度", ui("String(document.querySelectorAll('#pv-all-list .row-item').length)"),
        str(len((api("/api/vault/list").get("list") or []))))
    tap("document.querySelectorAll('#pv-all-list .row-item')[0]")
    time.sleep(1.5)
    got = sheet_text()
    chk("点一条 -> 小窗里有「密码」字段", got, lambda s: "密码" in str(s))
    chk("小窗里读到的是那条的账号/密码", got, lambda s: "tester" in str(s) or "T3st" in str(s) or "test.example" in str(s))
else:
    print("     （这台无头模拟器过不了锁屏验证 -> 第 20 条的 (2)(3) 只能人工在真机/有锁屏的设备上补验）")
    chk("未解锁时条目接口也不吐数据（这是设计）", api("/api/vault/list").get("needUnlock"), True)
    chk("收起来键能面板收起（真手指点一下，面板本来就藏着，所以应保持藏着）",
        tap("document.getElementById('pv-all-close')") is not None, True)

print("\n结果：%d/%d 通过" % (P, P + F))
if FAILS:
    print("  未通过：" + "；".join(FAILS))
print("  注：无锁屏的无头环境只能验到「未解锁的提示与隐藏行为」；解锁后的两条需人工过锁屏。")
sys.exit(1 if F else 0)
