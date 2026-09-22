#!/usr/bin/env python3
"""本轮（界面归类改版）验收：真手指点抽屉 + 读回页面状态。

为什么这么做（用户反复要求）：
  · 合成事件（CDP dispatchTouchEvent / JS click）只能证明"逻辑通"，证明不了"用户点得到"；
    所以关键交互用 `adb shell input tap` 真手指点。
  · 坐标一律**现测**：抽屉里的元素是 WebView 内部节点，但 WebView 会把它们作为无障碍节点
    暴露出来（节点带 resource-id = 元素 id，这正是"稳定标识"的好处），所以
    uiautomator dump 出来的 bounds 就是真手指要点的地方（不写死坐标、不靠换算）。
  · 判据一律读回可观测状态（DOM 类名 / 面包屑 / section.on / 节点 bounds），"没报错"不算通过。
"""
import json
import re
import subprocess
import sys
import time

SER = "emulator-5554"
HTTP_OPEN = "http://127.0.0.1:8848"
ROOT = "/vol1/1000/airesults/cdp/source/cdp"
ADB = "/home/lwgat/tools/android-sdk/platform-tools/adb"

checks = []


class R:
    def __init__(self, out="", err="", rc=1):
        self.stdout, self.stderr, self.returncode = out, err, rc


def run(cmd, timeout=45):
    """每条命令都有超时兜底：uiautomator dump / CDP 偶发卡住时，整轮不能跟着卡死"""
    try:
        return subprocess.run(cmd, shell=True, cwd=ROOT, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        print("  （命令超时 %ss，跳过这一条：%s）" % (timeout, cmd[:60]))
        return R()


def ui(expr, timeout=30):
    """读控制台界面状态：用极简探针（硬超时），不用 cdp.mjs —— 后者反复"验活"偶尔会卡几十秒"""
    r = run("node tools/uiprobe.mjs --timeout 8000 %s" % json.dumps(expr), timeout)
    out = (r.stdout or "").strip().splitlines()
    if not out:
        return None
    v = out[-1]
    try:
        return json.loads(v)
    except Exception:
        return v


def dump():
    run("adb -s %s shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1" % SER)
    return run("adb -s %s shell cat /sdcard/ui.xml" % SER).stdout


def parse_nodes(xml):
    """resource-id → (bounds, text, clickable)；bounds 为 [x1,y1,x2,y2]"""
    out = {}
    for m in re.finditer(r'<node[^>]*>', xml):
        s = m.group(0)
        rid = re.search(r'resource-id="([^"]*)"', s)
        b = re.search(r'bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"', s)
        if not rid or not b or not rid.group(1):
            continue
        out[rid.group(1)] = {
            "bounds": [int(b.group(i)) for i in range(1, 5)],
            "text": (re.search(r'text="([^"]*)"', s) or [None, ""])[1],
            "clickable": 'clickable="true"' in s,
        }
    return out


def wait_rid(rid, tries=5, wait=1.0, need_size=True):
    """轮询等无障碍树刷新（跨 WebView 的改动是异步的，dump 一次就断言会假红 —— 踩过）"""
    nodes = {}
    for _ in range(tries):
        nodes = parse_nodes(dump())
        n = nodes.get(rid)
        if n and (not need_size or n["bounds"][2] > n["bounds"][0]):
            return nodes
        time.sleep(wait)
    return nodes


def chk(name, got, want):
    ok = want(got) if callable(want) else got == want
    checks.append((name, bool(ok), str(got)[:200]))
    print(("  PASS " if ok else "  FAIL ") + name + "  → " + str(got)[:200])
    return ok


def tap_rid(rid, nodes, label):
    n = nodes.get(rid)
    if not n:
        chk("真手指点「%s」：节点在无障碍树里" % label, None, lambda g: False)
        return False
    x1, y1, x2, y2 = n["bounds"]
    if x2 - x1 <= 0 or y2 - y1 <= 0:
        chk("真手指点「%s」：节点有实际大小（没被隐藏）" % label, n["bounds"], lambda b: b[2] > b[0])
        return False
    x, y = (x1 + x2) // 2, (y1 + y2) // 2
    print("  真手指点 %-16s id=%-14s bounds=[%d,%d][%d,%d] → (%d,%d)  text=%s"
          % (label, rid, x1, y1, x2, y2, x, y, n["text"][:22]))
    run("adb -s %s shell input tap %d %d" % (SER, x, y))
    time.sleep(0.9)
    return True


def main():
    print("[0] 先确保控制台是打开的（关着的话无障碍树里根本没有它）")
    # 上一轮如果超时退出，抽屉搜索可能还留着过滤态（栏目都被 hide）→ 先清掉，否则后面全是假红
    ui("(function(){var q=document.getElementById('drawerSearch');if(q){q.value='';q.dispatchEvent(new Event('input'));}"
       "document.body.classList.contains('drawer-open')&&document.getElementById('scrim').click();return 1;})()")
    for _ in range(3):
        run('curl -s "%s/api/ui/open?tab=page" >/dev/null' % HTTP_OPEN, timeout=20)
        time.sleep(1.5)
        nodes = parse_nodes(dump())
        if "drawerBtn" in nodes:
            break
    chk("控制台界面的 ☰ 在无障碍树里", "drawerBtn" in nodes, True)

    print("[1] 打开抽屉（点原生 ☰；它是开关，本来开着就别点，否则会关掉）")
    state = ui("document.body.classList.contains('drawer-open')")
    if state is True:
        print("  （抽屉本来就开着，跳过 ☰）")
    else:
        if not tap_rid("drawerBtn", nodes, "☰"):
            return 2
        time.sleep(0.8)
    nodes = parse_nodes(dump())

    print("[2] 抽屉是两级：7 个集合行 + 25 个栏目，且都真的有大小（点得到）")
    heads = [k for k in nodes if k.startswith("collhead-")]
    chk("集合行数 = 7", sorted(heads), lambda h: len(h) == 7)
    chk("集合行文案带功能数", nodes.get("collhead-api", {}).get("text"),
        lambda t: t and "接口与隔离" in t and "个功能" in t)
    btns = [k for k in nodes if k.startswith("tabbtn-")]
    # 折叠起来的集合里的栏目是 display:none，无障碍树里就没有大小（这是对的，不是缺陷）：
    # 所以"总数"按页面里真实存在的算（CDP），"当前可见/可点"的按无障碍树算，期望值也从 DOM 现算。
    total = ui("document.querySelectorAll('#tabs button.tabBtn').length")
    chk("页面里栏目按钮总数 = 登记表里的栏目数", total, 24)
    expect_vis = ui("Array.from(document.querySelectorAll('.collTabs.open button.tabBtn')).length") or 0
    vis = [k for k in btns if nodes[k]["bounds"][2] > nodes[k]["bounds"][0]]
    chk("无障碍树里可点的栏目 == 展开的集合里的栏目数（%s）" % expect_vis, len(vis), expect_vis)
    zero = [k for k in heads + btns if (nodes[k]["bounds"][2] - nodes[k]["bounds"][0]) <= 0]
    print("  当前展开的集合里可见的栏目：" + ", ".join(sorted(k for k in btns if nodes[k]["bounds"][2] > nodes[k]["bounds"][0]))[:160])
    chk("集合行都拿到了实际大小", [k for k in heads if nodes[k]["bounds"][2] <= 0], lambda z: z == [])

    print("[3] 真手指点集合「接口与隔离」→ 展开它的栏目")
    tap_rid("collhead-api", nodes, "集合 接口与隔离")
    chk("点完这个集合展开了（DOM 读回）",
        ui("document.getElementById('coll-api').classList.contains('open')"), True)
    nodes = wait_rid("tabbtn-space")   # 展开后 a11y 树刷新是异步的，轮询等它
    chk("展开后它的栏目拿到实际大小（点得到）",
        nodes.get("tabbtn-space", {}).get("bounds"), lambda b: b and b[2] > b[0])

    print("[4] 真手指点栏目「配置空间」→ 内容区切过去 + 面包屑跟着变")
    tap_rid("tabbtn-space", nodes, "栏目 配置空间")
    chk("内容区切到该栏目", ui("(document.querySelector('section.tab.on')||{}).id"), "tab-space")
    chk("面包屑写明 集合 ▸ 栏目", ui("(document.getElementById('drawerNow')||{}).textContent"),
        lambda t: t and "接口与隔离" in t and "配置空间" in t)

    print("[5] 真手指点「更多 ▾」→ 第 4 个键才露出来")
    ui("window.__cdpTab('history');JSON.stringify(1)")
    time.sleep(0.6)
    # 上一轮超时可能留了"展开态" → 先归位再验，否则会验到"点一下反而收起了"（假红）
    if ui("document.getElementById('h-morebox').classList.contains('hide')") is not True:
        ui("(function(){document.getElementById('h-more').click();return 1;})()")
        time.sleep(0.5)
    nodes = parse_nodes(dump())
    was_hidden = (nodes.get("h-morebox", {}).get("bounds") or [0, 0, 0, 0])[2] == 0
    tap_rid("h-more", nodes, "更多 ▾")
    nodes = parse_nodes(dump())
    chk("点之前是收着的", was_hidden, True)
    chk("点完展开了，里面的键有实际大小（可见可点）",
        [k for k in nodes if k.startswith("h-") and nodes[k]["bounds"][2] > 0],
        lambda ks: ("h-export" in ks) or ("h-del-before" in ks))

    print("[6] 抽屉搜索：输入「拦截」只剩对应栏目（其余栏目节点尺寸归零）")
    ui("(function(){var q=document.getElementById('drawerSearch');q.value='拦截';q.dispatchEvent(new Event('input'));return 1;})()")
    time.sleep(0.5)
    vis_keys = ui("Array.from(document.querySelectorAll('#tabs button.tabBtn')).filter(function(b){return !b.classList.contains('hide');}).map(function(b){return b.getAttribute('data-tab');})")
    chk("只剩「拦截与名单」这一个栏目可见", vis_keys, lambda v: v == ["block"])
    ui("(function(){var q=document.getElementById('drawerSearch');q.value='';q.dispatchEvent(new Event('input'));return 1;})()")

    print("[7] 收尾：回页面栏目，抽屉关掉")
    ui("window.__cdpTab('page');JSON.stringify(1)")
    chk("收尾后当前栏目是页面与元素", ui("(document.querySelector('section.tab.on')||{}).id"), "tab-page")

    bad = [c for c in checks if not c[1]]
    print("\n结果：%d/%d 通过" % (len(checks) - len(bad), len(checks)))
    for c in bad:
        print("  未通过：" + c[0] + " → " + c[2])
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
