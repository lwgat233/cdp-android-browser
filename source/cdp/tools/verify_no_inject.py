#!/usr/bin/env python3
"""「不要往页面里注入 JS；阅读时长和 AI 放到我们自己这边」的验收（用户 2026-09-20 要求）。

用户原话："不要嵌入 js 到页面你们，对于什么阅读时长和 ai，把它放在（我们自己的界面）里。"

所以这条验两件事：
  · **页面里干干净净**：没有阅读时间徽标（#cdp-rt）、没有 __CDP_READTIME_MINS、没有助手面板那类 cdp-* 元素；
    插件列表里"阅读时间 / 助手面板"已被**退休**（assets 删了、设备上那份也一并移除），只剩用户要的"纯文本阅读"。
  · **阅读时长照样在记**，而且是 **App 自己计时**（/api/read/timer）：
    真站点上停留 → 计时器显示正在计这一页、pendingMs 在涨 → 一个周期后记录出现且时长在涨；
    退到后台期间**不涨**（只算前台停在那一页的时间）。
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse

ROOT = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
ENV = dict(os.environ, PATH="/home/lwgat/.local/bin:" + os.environ.get("PATH", ""))
checks = []


def ui(expr, timeout=45):
    r = subprocess.run(["node", "tools/uiprobe.mjs", "--timeout", "12000", "--match", "ui/index.html", expr],
                       capture_output=True, text=True, cwd=ROOT, timeout=timeout, env=ENV)
    out = [l for l in (r.stdout or "").strip().splitlines() if l.strip()]
    return out[-1] if out else None


def api(path, timeout=90):
    try:
        r = subprocess.run(["curl", "-s", "--max-time", str(timeout), HTTP + path],
                           capture_output=True, text=True, timeout=timeout + 10)
        return json.loads(r.stdout)
    except Exception:
        return {}


def adb(args, timeout=45):
    return subprocess.run(["bash", "-lc",
                           "export PATH=/home/lwgat/tools/android-sdk/platform-tools:$PATH; adb -s emulator-5554 " + args],
                          capture_output=True, text=True, timeout=timeout).stdout.strip()


def page(js, timeout=40):
    """在**浏览的那个页面**里执行一条表达式（不是控制台）"""
    try:
        r = subprocess.run(["curl", "-s", "--max-time", str(timeout),
                            HTTP + "/api/eval?js=" + urllib.parse.quote(js, safe="")],
                           capture_output=True, text=True, timeout=timeout + 10)
        d = json.loads(r.stdout)
        return d.get("raw")
    except Exception:
        return None


def chk(name, got, want):
    ok = want(got) if callable(want) else got == want
    checks.append((name, bool(ok), str(got)[:200]))
    print(("  PASS " if ok else "  FAIL ") + name + "  → " + str(got)[:200])
    return ok


if not api("/api/status").get("ok"):
    print("环境没起来：控制口不通。先起模拟器/应用再跑。")
    sys.exit(2)

PAGE = "https://yhtn.cc/"

print("[0] 环境")
chk("控制口在", bool(api("/api/status").get("ok")), True)
api("/api/goto?url=" + urllib.parse.quote(PAGE, safe=""))
time.sleep(8)

print("[1] 页面里干干净净（不注入 JS）")
chk("页面上没有阅读时间的徽标（#cdp-rt）", page("String(!!document.getElementById('cdp-rt'))"), "false")
chk("页面上没有 __CDP_READTIME_MINS（旧插件留下的全局）",
    page("String(typeof window.__CDP_READTIME_MINS)"), "undefined")
# 页面里合法存在的只有"用户自己开的功能"：纯文本阅读(cdp-pt*)、录制小点(cdp-dot*)
# 剩下的（阅读时间徽标/助手面板那类）一个都不该有
ALLOW = ("cdp-pt", "cdp-dot", "cdp-layer", "cdp-mark")
ids = [x for x in (page(
    "(function(){var a=[];document.querySelectorAll('[id^=cdp-]').forEach(function(e){a.push(e.id)});return a.join(',');})()") or "").split(",") if x]
chk("页面上没有我们注入的元素（除纯文本阅读/录制小点这些用户自己开的）",
    [x for x in ids if not x.startswith(ALLOW)], [])
chk("页面上没有助手面板那类容器",
    page("String(document.querySelectorAll('.cdp-assist,[class*=cdp-assist],[id*=assist-panel]').length)"), "0")

print("[2] 内置插件：注入的那两个已退休，剩的是用户要的那个")
names = [s.get("name") for s in (api("/api/scripts").get("list") or [])]
chk("「阅读时间」插件已经不在脚本库里", ["阅读时间" in str(n) for n in names], lambda v: not any(v))
chk("「助手面板」插件已经不在脚本库里", ["助手面板" in str(n) for n in names], lambda v: not any(v))
chk("「纯文本阅读」还在（对照：不是把所有注入都砍了）", any("纯文本" in str(n) for n in names), True)

print("[3] 阅读时长照样在记 —— App 自己计时（/api/read/timer）")
api("/api/read/clear")
t1 = (api("/api/read/timer").get("timer") or {})
chk("计时器在计这一页", (t1.get("url") or ""), lambda v: PAGE.rstrip("/") in str(v))
chk("计时器知道 App 在前台", t1.get("foreground"), True)
# pendingMs 每 15 秒会被冲刷归零，单点两次容易正好都撞在归零处 → 采一串看有没有涨
samples = []
for _ in range(6):
    samples.append((api("/api/read/timer").get("timer") or {}).get("pendingMs") or 0)
    time.sleep(3)
chk("攒的时间在涨（App 自己数的）", (min(samples), max(samples)), lambda v: v[1] > v[0])
rec = {}
for _ in range(12):                              # 等到它上报（15 秒一拍）
    time.sleep(5)
    for it in (api("/api/read?sort=ts&limit=20").get("list") or []):
        if it.get("url") == PAGE:
            rec = it
            break
    if rec:
        break
chk("记录出现了（不离开页面）", bool(rec), True)
m1 = rec.get("ms") or 0
time.sleep(20)
m2 = 0
for it in (api("/api/read?sort=ts&limit=20").get("list") or []):
    if it.get("url") == PAGE:
        m2 = it.get("ms") or 0
chk("时长在往上涨（实时）", m2 > m1, True)
chk("这条是 App 自己计的（native 标记）", rec.get("native"), True)

print("[4] 退到后台期间不算（只算前台停在那一页的时间）")
adb("shell input keyevent KEYCODE_HOME")
time.sleep(3)
q1 = (api("/api/read/timer").get("timer") or {})
time.sleep(8)
q2 = (api("/api/read/timer").get("timer") or {})
chk("退后台后计时器知道不在前台了", q1.get("foreground"), False)
chk("退后台期间不再累加", ((q2.get("pendingMs") or 0) <= (q1.get("pendingMs") or 0) + 1000), True)
adb("shell am start -n dev.cdp/.MainActivity >/dev/null 2>&1")
time.sleep(4)

print("[5] 落盘：控制台重载后记录还在")
n1 = api("/api/read?limit=50").get("count")
ui("location.reload()")
time.sleep(6)
n2 = api("/api/read?limit=50").get("count")
chk("重载前后条数不变", n2, lambda v: v == n1 and v > 0)

ok = sum(1 for _, p, _ in checks if p)
print("\n结果：%d/%d 通过" % (ok, len(checks)))
bad = [n for n, p, _ in checks if not p]
if bad:
    print("  未通过：" + "；".join(bad))
sys.exit(0 if ok == len(checks) else 1)
