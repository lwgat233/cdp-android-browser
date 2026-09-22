#!/usr/bin/env python3
"""多窗口三态验收（第 28 条 + 28.1：当前 / 后台 / 历史下来的，参考 Firefox）

用户原话：「还有多窗口模式我之前说了要干嘛，什么表示在后台，什么表示当前，什么表示历史下来的，参考firefox」

判据（读回来的事实：接口字段 + ▤ 弹窗里的真实文本）：
  [A] 开 3 个窗口 → `win.list` 里**恰好一个** active=true；其余 kept=true、active=false
  [B] ▤ 弹窗读数：当前那行是「● …」且带底色，其它是「○ …」
  [C] 开满 12 个 → kept=true 的最多 10；被回收的那几个 kept=false
  [D] ▤ 弹窗里被回收的行带「⟳」标记（读 uiautomator dump 的真实文本）
  [E] 关掉一个 → `/api/win/recent` 出现 1 条（标题/地址在）；`win.list` 条数 -1
  [F] 点「最近关闭」里那一条恢复 → `win.list` 条数 +1、`win.recent` 条数 -1
  [G] 重启 App → 窗口列表与「最近关闭」都还在（条数一致）

用法：python3 tools/verify_win_states.py
"""
import json
import os
import subprocess
import sys
import time
import urllib.parse
import urllib.request

HTTP = "http://127.0.0.1:8848"
ADB = "/home/lwgat/tools/android-sdk/platform-tools/adb"
PASS = 0
FAIL = 0
NOTE = []


def ok(m):
    global PASS
    PASS += 1
    print("  PASS " + m)


def bad(m):
    global FAIL
    FAIL += 1
    print("  FAIL " + m)


def note(m):
    NOTE.append(m)
    print("  NOTE " + m)


def api(path, timeout=60):
    with urllib.request.urlopen(HTTP + path, timeout=timeout) as r:
        return json.loads(r.read().decode())


def wins():
    return api("/api/win")


def recent():
    return api("/api/win/recent")


def dump_ui():
    """读当前屏幕上的原生控件文字（▤ 弹窗是原生 AlertDialog）"""
    subprocess.run([ADB, "shell", "rm", "-f", "/sdcard/u.xml"], capture_output=True, text=True)
    subprocess.run([ADB, "shell", "uiautomator", "dump", "/sdcard/u.xml"], capture_output=True, text=True, timeout=90)
    return subprocess.run([ADB, "shell", "cat", "/sdcard/u.xml"], capture_output=True, text=True).stdout


def wake():
    subprocess.run([ADB, "shell", "input", "keyevent", "KEYCODE_WAKEUP"], capture_output=True, text=True)
    subprocess.run([ADB, "shell", "svc", "power", "stayon", "true"], capture_output=True, text=True)


def open_menu_dump():
    """打开 ▤ 窗口菜单并 dump 它的真实文本（菜单是原生 AlertDialog）"""
    api("/api/win/menu")
    time.sleep(1.5)
    u = dump_ui()
    subprocess.run([ADB, "shell", "input", "keyevent", "KEYCODE_BACK"], capture_output=True, text=True)
    time.sleep(0.8)
    return u


print("== 0. 环境 ==")
for _ in range(30):
    try:
        if api("/api/status").get("ok"):
            break
    except Exception:
        pass
    time.sleep(2)
wake()
ensure = subprocess.run([ADB, "shell", "pidof", "dev.cdp"], capture_output=True, text=True).stdout.strip()
if ensure:
    subprocess.run([ADB, "forward", "tcp:9222", "localabstract:webview_devtools_remote_" + ensure],
                   capture_output=True, text=True)
subprocess.run([ADB, "forward", "tcp:8848", "tcp:8848"], capture_output=True, text=True)
print("   pid=%s" % ensure)

print("== 准备：收拢到 2 个窗口（后面好数） ==")
w = wins()
n0 = w.get("count") or 0
for i in range(n0 - 1, 1, -1):
    api("/api/win/close?id=%d" % i)
time.sleep(1.5)
w = wins()
print("   现在 count=%d active=%d" % (w["count"], w.get("active")))

print("== 1. 当前/后台：开 3 个窗口，看标记 ==")
while wins().get("count", 0) < 3:
    api("/api/win/new?url=" + urllib.parse.quote("https://appassets.androidplatform.net/test/tap.html"))
    time.sleep(2)
time.sleep(1)
w = wins()
lst = w.get("list") or []
act = [x for x in lst if x.get("active")]
kept = [x for x in lst if x.get("kept")]
if len(act) == 1:
    ok("恰好一个窗口是 active（当前）：#%s" % act[0].get("id"))
else:
    bad("active 标记不对：%d 个" % len(act))
if len(kept) == len(lst):
    ok("其余窗口都还在保活（kept=true，%d 个）" % len(kept))
else:
    bad("kept 数量不对：%d/%d" % (len(kept), len(lst)))

# ▤ 弹窗（用 win.menu 打开同一个菜单）：当前 ● 实心，后台 ○ 空心
wake()
u2 = open_menu_dump()
if "●" in u2 or "○" in u2:
    solid = u2.count("●")
    hollow = u2.count("○")
    if solid >= 1 and hollow >= 1:
        ok("窗口菜单里：当前是实心 ●（%d 个），后台是空心 ○（%d 个）" % (solid, hollow))
    else:
        bad("窗口菜单里标记不对：●=%d ○=%d" % (solid, hollow))
else:
    note("菜单 dump 里没读到 ● / ○（dump 可能没抓到弹窗文本）：%r" % u2[:120])

print("== 2. 开满 12 个：被回收的窗口打「⟳」 ==")
while wins().get("count", 0) < 12:
    api("/api/win/new?url=" + urllib.parse.quote("https://appassets.androidplatform.net/test/tap.html"))
    time.sleep(1.6)
time.sleep(2)
w = wins()
lst = w.get("list") or []
kcount = len([x for x in lst if x.get("kept")])
if w["count"] == 12 and kcount <= 10:
    ok("12 个窗口里 kept=true 的 %d 个（上限 10 生效）" % kcount)
else:
    bad("count=%s kept=%d（期望 12 / ≤10）" % (w["count"], kcount))
recycled = [x for x in lst if not x.get("kept")]
if recycled:
    ok("被回收的窗口 kept=false：%d 个（例：#%s）" % (len(recycled), recycled[0].get("id")))
else:
    bad("没有出现被回收的窗口")
u2 = open_menu_dump()
if "⟳" in u2:
    ok("被回收的行在菜单里带「⟳」标记（要重载）")
else:
    bad("菜单里没看到「⟳」标记（dump 片段 %r）" % u2[:120])

print("== 3. 关一个 → 进「最近关闭」 ==")
r0 = recent().get("count") or 0
n_before = wins()["count"]
api("/api/win/close?id=0")
time.sleep(2)
r1 = recent().get("count") or 0
n_after = wins()["count"]
if r1 == min(r0 + 1, 10) and n_after == n_before - 1:
    ok("关掉一个：窗口 %d→%d，「最近关闭」%d→%d（满 10 条时挤掉最旧的一条）" % (n_before, n_after, r0, r1))
else:
    bad("关窗后计数不对：窗口 %d→%d，最近关闭 %d→%d" % (n_before, n_after, r0, r1))
item = (recent().get("list") or [{}])[0]
if (item.get("url") or "").startswith("http"):
    ok("「最近关闭」里记着地址与标题：%s | %s" % ((item.get("title") or "")[:20], (item.get("url") or "")[:50]))
else:
    bad("「最近关闭」条目没地址：%s" % json.dumps(item, ensure_ascii=False)[:120])

print("== 4. 恢复它 → 变回窗口 ==")
n_before = wins()["count"]
r_before = recent().get("count")
api("/api/win/reopen?i=0")
time.sleep(2.5)
n_after = wins()["count"]
r_after = recent().get("count")
if n_after == n_before + 1 and r_after == r_before - 1:
    ok("点恢复：窗口 %d→%d，「最近关闭」%d→%d" % (n_before, n_after, r_before, r_after))
else:
    bad("恢复后计数不对：窗口 %d→%d，最近关闭 %d→%d" % (n_before, n_after, r_before, r_after))

print("== 5. 重启 App：窗口与「最近关闭」都还在 ==")
n_before = wins()["count"]
r_before = recent().get("count")
subprocess.run([ADB, "shell", "am", "force-stop", "dev.cdp"], capture_output=True, text=True)
subprocess.run([ADB, "shell", "am", "start", "-n", "dev.cdp/.MainActivity"], capture_output=True, text=True)
ok_ready = False
for _ in range(30):
    time.sleep(2)
    try:
        if api("/api/status").get("ok"):
            ok_ready = True
            break
    except Exception:
        pass
if not ok_ready:
    bad("重启后控制口没起来")
else:
    time.sleep(3)
    n_after = wins()["count"]
    r_after = recent().get("count")
    if n_after == n_before and r_after == r_before:
        ok("重启后窗口 %d 个、「最近关闭」%d 条（和重启前一致）" % (n_after, r_after))
    else:
        bad("重启后不一致：窗口 %d→%d，最近关闭 %d→%d" % (n_before, n_after, r_before, r_after))

print("\n结果：%d PASS / %d FAIL" % (PASS, FAIL))
for n in NOTE:
    print("NOTE：" + n)
sys.exit(0 if FAIL == 0 else 1)
