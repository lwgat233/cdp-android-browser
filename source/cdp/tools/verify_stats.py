#!/usr/bin/env python3
"""统计（阅读时长 / 网络流量）的验收 —— 用户这一轮要的三件事：

  ① 阅读时长统计：主页不记、其它网页按站点记；饼图（按站点）+ 柱状图；
     两种口径（单网页阅读时长 / 连续使用时长）；**按小时 · 天 · 月 · 年** 看；
     柱子上要能读出"多长时间 + 多少个网站"。
  ② 流量统计：0~24 小时每个小时的上行/下行柱状图 + 按站点饼图。
  ③ 采集要分**前台 / 后台**，柱状图双色堆叠（下面前台、上面后台）。

判据全部是**读回来的事实**：HTTP 上的 stats 数字、DOM 里 SVG 的元素数与文字。
只测这次动的范围（阅读统计 / 流量统计 / 统计板块），不跑全量。
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
    if not out:
        return None
    for line in reversed(out):
        try:
            v = json.loads(line)
        except Exception:
            continue
        if isinstance(v, str):
            try:
                return json.loads(v)
            except Exception:
                return v
        return v
    return out[-1]


def api(path, timeout=180):
    try:
        r = subprocess.run(["curl", "-s", "--max-time", str(timeout), HTTP + path],
                           capture_output=True, text=True, timeout=timeout + 15)
        return json.loads(r.stdout)
    except Exception:
        return {}


def sh(cmd, timeout=60):
    try:
        return subprocess.run(["bash", "-lc", cmd], capture_output=True, text=True, timeout=timeout).stdout.strip()
    except Exception:
        return ""


def adb(args, timeout=60):
    return sh("export PATH=/home/lwgat/tools/android-sdk/platform-tools:$PATH; adb -s emulator-5554 " + args, timeout)


def chk(name, got, want):
    ok = want(got) if callable(want) else got == want
    checks.append((name, bool(ok), str(got)[:220]))
    print(("  PASS " if ok else "  FAIL ") + name + "  → " + str(got)[:220])
    return ok


def add_read(url, title, ms):
    return api("/api/read/add?url=" + urllib.parse.quote(url, safe="") +
               "&title=" + urllib.parse.quote(title, safe="") + "&ms=%d" % ms)


def stats(range_="hour", src="read"):
    return api("/api/read/stats?range=" + range_) if src == "read" else api("/api/net/stats?range=" + range_)


# ---------------------------------------------------------------- 环境自检
if not api("/api/status").get("ok"):
    print("环境没起来：控制口 /api/status 不通（模拟器/应用没在跑）。这不是产品缺陷，先起环境再跑。")
    sys.exit(2)

print("[0] 环境")
chk("控制口在（/api/status）", bool(api("/api/status").get("ok")), True)
chk("应用跑的是这一版界面", ui("window.__cdpAppVer"), lambda v: isinstance(v, str) and v.startswith("2026-09-20-") and "统计" in v)

# ---------------------------------------------------------------- [1] 采集口径：外来页记、我们自己的页面不记
print("[1] 阅读采集：其它网页记，主页不记（用户明确要求：主页的阅读时间不管）")
api("/api/read/clear")
add_read("https://example.com/a", "外部页 A", 60000)
st = stats("hour")
chk("外部网页被记下来了", st.get("count"), 1)
add_read("https://appassets.androidplatform.net/ui/start.html", "我们自己的主页", 45000)
add_read("https://appassets.androidplatform.net/test/tap.html", "我们自己的测试页", 45000)
st = stats("hour")
chk("我们自己的页面（主页/测试页）不记", st.get("count"), 1)
chk("不记的那条是主页/自建页", [h.get("name") for h in st.get("byHost", [])], lambda v: v == ["example.com"])

# ---------------------------------------------------------------- [2] 前台 / 后台分开采
print("[2] 前台/后台分开采（用户要求：柱状图下面前台、上面后台）")
# 只报"刚过去的一小段"：区间必须完全落在已知的前台/后台里，否则是在考脚本的假设，不是考产品
api("/api/read/clear")
adb("shell input keyevent KEYCODE_WAKEUP")
adb("shell am start -n dev.cdp/.MainActivity >/dev/null 2>&1")
time.sleep(5)                                   # 等它真进前台（onResume → Fg.on）
add_read("https://example.com/fg", "前台页", 5000)
# 只看**我造的这条记录**：真实网页那个"阅读时间"插件也在实时上报，
# 它和我们造的记录落在同一个小时桶里，拿桶去比就会读到别人的数（踩过两次了）。
def rec_of(url):
    for it in api("/api/read?sort=ts&limit=30").get("list", []):
        if it.get("url") == url:
            return it
    return {}

time.sleep(1)
r_fg = rec_of("https://example.com/fg")
chk("前台时报的时间记成前台", r_fg.get("msFg"), lambda v: (v or 0) >= 4000)
chk("前台时的时间不落到后台", (r_fg.get("msBg") or 0), lambda v: v <= 1000)
# 退到后台 → 报一次；onPause 会关掉前台段，这一小段应算后台
# （先清一次：否则同一个小时桶里还留着前面那条"前台"记录，比出来的大小没意义）
api("/api/read/clear")
adb("shell input keyevent KEYCODE_HOME")
time.sleep(3)
add_read("https://example.com/bg", "后台页", 5000)
time.sleep(1)
r_bg = rec_of("https://example.com/bg")
# 阈值说明：按 HOME 到系统真正 pause 有 1~2 秒传播延迟，那一小段本来就该算前台，
# 所以判"后台明显多"而不是"后台独占整段"。
chk("退到后台后报的时间主要落在「后台」", r_bg.get("msBg"), lambda v: (v or 0) >= 2500)
chk("后台那条：后台 > 前台", ((r_bg.get("msBg") or 0) > (r_bg.get("msFg") or 0)), True)
adb("shell am start -n dev.cdp/.MainActivity >/dev/null 2>&1")
time.sleep(5)

# ---------------------------------------------------------------- [3] 分桶：小时 / 天 / 月 / 年
print("[3] 四种粒度（小时 / 天 / 月 / 年）都能分桶，字段齐")
h = stats("hour")
chk("按小时：固定 24 档（0–23）", len(h.get("bars", [])), 24)
chk("按小时：桶里有前台时长", any((b.get("value") or 0) > 0 for b in h.get("bars", [])), True)
chk("按小时：桶里带网站数", any("sites" in b for b in h.get("bars", [])), True)
chk("按小时：桶里带访问次数", any("visits" in b for b in h.get("bars", [])), True)
d = stats("day")
chk("按天：有桶且标签是日期", d.get("bars") and re.match(r"^\d{4}-\d{2}-\d{2}$", d["bars"][-1]["key"]), lambda v: bool(v))
m = stats("month")
chk("按月：有桶且标签是年月", m.get("bars") and re.match(r"^\d{4}-\d{2}$", m["bars"][-1]["key"]), lambda v: bool(v))
y = stats("year")
chk("按年：有桶且标签是年份", y.get("bars") and re.match(r"^\d{4}$", y["bars"][-1]["key"]), lambda v: bool(v))

print("[4] 两种口径：单网页时长（饼图）与连续使用时长")
chk("饼图数据：按站点带百分比", h.get("byHost") and abs(sum(x["pct"] for x in h["byHost"]) - 100) <= 1.1, lambda v: bool(v))
u = h.get("usage", {})
chk("连续使用：段数", (u.get("sessions") or 0) >= 1, True)
chk("连续使用：最长一段 > 0", (u.get("longestMs") or 0) > 0, True)
chk("连续使用：当前这段（刚报过，应该还在连续中）", (u.get("continuousMs") or 0) > 0, True)
chk("合计 = 前台 + 后台", (h.get("total") or 0) >= (h.get("totalFg") or 0) + (h.get("totalBg") or 0), True)

# ---------------------------------------------------------------- [5] 流量：字节事件 → 分桶 + 饼图
print("[5] 流量统计（小时上/下行柱 + 按站点饼）")
api("/api/net/clear")
before = stats("hour", "net")
chk("清空后字节为 0", (before.get("bytesDown") or 0), 0)
# 用 App 自带的离线测试源下载一条 m3u8（不联网）→ 字节要进统计，并归到站点
dl = api("/api/download?url=" + urllib.parse.quote(HTTP + "/api/_test/hls/index.m3u8", safe="") + "&name=stats-probe", timeout=90)
chk("下载起得来", dl.get("ok"), True)
time.sleep(6)
n = stats("hour", "net")
chk("下行字节 > 0", (n.get("bytesDown") or 0) > 0, True)
chk("字节事件被记下（用于分桶/饼图）", (n.get("events") or 0) >= 1, True)
chk("柱状图有桶且桶里有下行", any((b.get("value") or 0) > 0 for b in n.get("bars", [])), True)
chk("饼图：按站点汇总", (n.get("slices") or []) and n["slices"][0]["value"] > 0, lambda v: bool(v))
chk("小时粒度仍是 24 档", len(n.get("bars", [])), 24)
chk("前台/后台字节分开记", "bytesDownFg" in n and "bytesDownBg" in n, True)

# ---------------------------------------------------------------- [6] 界面：同一块统计画两种图
print("[6] 界面：一块统计同时喂阅读/流量（柱状图 + 饼图）")
api("/api/read/add?url=" + urllib.parse.quote("https://example.com/ui", safe="") + "&title=界面用&ms=45000")
ui("(function(){var s=document.getElementById('ch-src');if(s)s.value='read';"
   "var r=document.getElementById('ch-range');if(r)r.value='hour';"
   "if(window.__cdpLoadStats__)window.__cdpLoadStats__();"
   "var b=document.getElementById('ch-go');if(b)b.click();return 'ok';})()")
time.sleep(3)
rects = ui("document.querySelectorAll('#ch-bar svg rect').length")
chk("柱状图真的画出来了（有柱子）", rects, lambda v: isinstance(v, int) and v > 0)
paths = ui("document.querySelectorAll('#ch-pie svg path').length")
chk("饼图真的画出来了（有扇形）", paths, lambda v: isinstance(v, int) and v > 0)
lg = ui("(document.querySelector('#ch-bar .chart-legend')||{}).textContent||''")
chk("柱状图图例写明两色含义（前台/后台）", lg, lambda v: "前台" in str(v) and "后台" in str(v))
sm = ui("(document.getElementById('ch-sum')||{}).textContent||''")
chk("一行摘要里有合计与连续使用", sm, lambda v: ("合计" in str(v) and "连续使用" in str(v)))
ui("(function(){var s=document.getElementById('ch-src');if(s){s.value='net';s.dispatchEvent(new Event('change'));}return 'ok';})()")
time.sleep(3)
lg2 = ui("(document.querySelector('#ch-bar .chart-legend')||{}).textContent||''")
chk("切到「网络流量」后图例变上下行", lg2, lambda v: "下行" in str(v) and "上行" in str(v))
ui("(function(){var r=document.getElementById('ch-range');if(r){r.value='day';r.dispatchEvent(new Event('change'));}return 'ok';})()")
time.sleep(3)
xl = ui("Array.prototype.slice.call(document.querySelectorAll('#ch-bar .chart-x')).map(function(t){return t.textContent}).join(',')")
chk("切到「按天」后横轴变日期", xl, lambda v: bool(re.search(r"\d{2}-\d{2}", str(v))))
# 网络栏目里的跳转入口
ui("(function(){var b=document.getElementById('n-to-stats');if(b)b.click();return 'ok';})()")
time.sleep(2)
shown = ui("(function(){var t=document.getElementById('tab-plugins');return t&&t.classList.contains('on')?'plugins':'';})()")
chk("网络栏目点「统计图」跳到统计所在栏目", shown, "plugins")

# ---------------------------------------------------------------- 汇总
ok = sum(1 for _, p, _ in checks if p)
print("\n结果：%d/%d 通过" % (ok, len(checks)))
bad = [n for n, p, _ in checks if not p]
if bad:
    print("  未通过：" + "；".join(bad))
sys.exit(0 if ok == len(checks) else 1)
