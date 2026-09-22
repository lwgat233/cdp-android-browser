#!/usr/bin/env python3
"""「多窗口列表：每行用 ✕ 关掉那一个窗口」的验收（用户 2026-09-20 要求）。

用户原话："多窗口的栏目有 x 表示删除而不是文字。"
原来是 PopupMenu 的文字项（只能"关掉当前窗口"）。现在是一个列表：每行 [● 页面标题] [✕]，
点标题切过去、点 ✕ 关掉**那一个**。

判据：真手指点出来的界面层级（uiautomator dump）里
  · 每个窗口一行，行尾有 content-desc=「关掉这个窗口」的 ✕ 节点；
  · 没有"关掉当前窗口 / 删除"这类文字项；
  · 点某行的 ✕ 之后，`win.list` 的窗口数**减 1**；只剩一个时点 ✕ 关不掉（后端如实回"不能关"）。
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
checks = []


def api(path, timeout=60):
    try:
        r = subprocess.run(["curl", "-s", "--max-time", str(timeout), HTTP + path],
                           capture_output=True, text=True, timeout=timeout + 10)
        return json.loads(r.stdout)
    except Exception:
        return {}


def adb(args, timeout=60):
    return subprocess.run([ADB, "-s", "emulator-5554"] + args, capture_output=True, text=True, timeout=timeout).stdout


def chk(name, got, want):
    ok = want(got) if callable(want) else got == want
    checks.append((name, bool(ok), str(got)[:200]))
    print(("  PASS " if ok else "  FAIL ") + name + "  → " + str(got)[:200])
    return ok


def nodes():
    """dump 界面层级，返回 [(attrs, cx, cy)]（中心坐标现测）"""
    for _ in range(3):
        adb(["shell", "uiautomator", "dump", "/sdcard/w.xml"])
        xml = adb(["shell", "cat", "/sdcard/w.xml"])
        if "dev.cdp" in xml:
            break
        adb(["shell", "am", "force-stop", "com.google.android.apps.nexuslauncher"])
        adb(["shell", "am", "start", "-n", "dev.cdp/.MainActivity"])
        time.sleep(2)
    out = []
    for m in re.finditer(r"<node ([^>]+?)/?>", xml):
        a = dict(re.findall(r'([a-zA-Z\-]+)="([^"]*)"', m.group(1)))
        b = re.findall(r"-?\d+", a.get("bounds", ""))
        if len(b) == 4:
            out.append((a, (int(b[0]) + int(b[2])) // 2, (int(b[1]) + int(b[3])) // 2))
    return out


def tap(x, y):
    adb(["shell", "input", "tap", str(x), str(y)])
    time.sleep(1.6)


if not api("/api/status").get("ok"):
    print("环境没起来：控制口不通。先起模拟器/应用再跑。")
    sys.exit(2)

print("[0] 环境与前置：备好 3 个窗口")
while (api("/api/win").get("count") or 1) > 1:
    api("/api/win/close")
    time.sleep(1)
api("/api/win/new")
time.sleep(2)
api("/api/win/new")
time.sleep(2)
n0 = api("/api/win").get("count")
chk("窗口数（用来数 ✕ 的个数）", n0, lambda v: (v or 0) == 3)

print("[1] 点工具栏「▤」打开窗口列表")
bar = [n for n in nodes() if "▤" in (n[0].get("text") or "")]
chk("工具栏上有「▤」", len(bar) >= 1, True)
if bar:
    tap(bar[0][1], bar[0][2])
time.sleep(1)

print("[2] 列表里每个窗口一行，行尾是 ✕（不是文字）")
ns = nodes()
xs = [n for n in ns if (n[0].get("content-desc") or "") == "关掉这个窗口"]
chk("✕ 的个数 = 窗口数", len(xs), lambda v: v == n0)
if len(xs) != n0:
    print("      dump 里的行：", [ (n[0].get("text") or "")[:30] for n in ns if (n[0].get("text") or "").strip() ][:12])
alltext = " ".join((n[0].get("text") or "") for n in ns)
chk("没有「关掉当前窗口」这种文字项", "关掉当前窗口" in alltext, False)
chk("没有「删除」这种文字项", "删除" in alltext, False)
chk("有「＋ 新窗口」入口", "新窗口" in alltext, True)

print("[3a] 真手指点「行本身」→ 切到那个窗口（用户要求：点行就是选择窗口）")
first = [n for n in ns if (n[0].get("text") or "").startswith(("● ", "○ "))]
if len(first) >= 2:
    # 点非当前那一行（○ 开头的那条）
    target = [n for n in first if (n[0].get("text") or "").startswith("○ ")]
    if target:
        before_active = api("/api/win").get("active")
        tap(target[-1][1], target[-1][2])
        time.sleep(2)
        chk("点行之后当前窗口变了", api("/api/win").get("active"), lambda v: v != before_active)
        # 打开列表再确认一次（后面的关闭用例要用新鲜的坐标）
        bar2 = [n for n in nodes() if "▤" in (n[0].get("text") or "")]
        if bar2:
            tap(bar2[0][1], bar2[0][2])
            ns = nodes()

print("[3] 真手指点某一行的 ✕ → 那一个窗口被关掉")
if xs:
    # 点**最后一个**窗口的 ✕（它一定不是当前窗口：当前窗口是刚切过去的那个）
    tap(xs[-1][1], xs[-1][2])
    time.sleep(2)
    n1 = api("/api/win").get("count")
    chk("窗口数减 1", n1, lambda v: v == n0 - 1)

print("[4] 只剩一个窗口时，✕ 关不掉（如实拒绝）")
while (api("/api/win").get("count") or 1) > 1:
    api("/api/win/close")
    time.sleep(1)
time.sleep(1)
bar = [n for n in nodes() if "▤" in (n[0].get("text") or "")]
if bar:
    tap(bar[0][1], bar[0][2])
xs = [n for n in nodes() if (n[0].get("content-desc") or "") == "关掉这个窗口"]
chk("只剩 1 个窗口时列表里仍有 1 个 ✕", len(xs), 1)
if xs:
    tap(xs[0][1], xs[0][2])
    time.sleep(1)
    chk("点了也还是 1 个窗口（不能关）", api("/api/win").get("count"), 1)

ok = sum(1 for _, p, _ in checks if p)
print("\n结果：%d/%d 通过" % (ok, len(checks)))
bad = [n for n, p, _ in checks if not p]
if bad:
    print("  未通过：" + "；".join(bad))
sys.exit(0 if ok == len(checks) else 1)
