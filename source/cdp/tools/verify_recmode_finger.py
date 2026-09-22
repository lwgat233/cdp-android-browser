#!/usr/bin/env python3
"""坐标录制（用户报"没效果"那条路）—— 全程**真手指**走一遍，读回来的事实说话。

用户报的现象：「屏幕录制功能的坐标录制没效果」。
真根因（本轮修）：☰ 菜单里那条「开始监听」发 rec.start 时**不带 mode**，
Bridge 退回默认 element —— 所以"在控制台选了坐标录制、再用 ☰ 菜单开始"，录下来的还是元素步。
顺手补的缺口：控制台选完方式要**立刻同步给原生**（rec.mode），菜单才知道按哪种录。

这条脚本走的就是用户那条路，全部真手指：
  ① 控制台里把录制方式选成「坐标录制」（<select> 是系统弹窗，只能用 JS 设值 —— 这一点在报告里写明）
  ② 点 ☰ → 菜单第一行「● 开始监听（**坐标录制**）」：真点，并断言原生 recording=true & mode=coord
  ③ 在页面上真点一下蓝块（坐标录制记的就是这一下）
  ④ 再点 ☰ →「■ 停止监听并保存」
  ⑤ 断言录到的是坐标步（mode=coord / 没有选择器 / 有锚点），回放后页面计数 +1
"""
import json
import re
import subprocess
import sys
import time

SER = "emulator-5554"
HTTP = "http://127.0.0.1:8848"
ROOT = "/vol1/1000/airesults/cdp/source/cdp"
ADB = "/home/lwgat/tools/android-sdk/platform-tools/adb"
PAGE = "https://appassets.androidplatform.net/test/tap.html"
checks = []


class R:
    def __init__(self, out="", err="", rc=1):
        self.stdout, self.stderr, self.returncode = out, err, rc


def run(cmd, timeout=45):
    try:
        return subprocess.run(cmd, shell=True, cwd=ROOT, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        print("  （超时 %ss：%s）" % (timeout, cmd[:70]))
        return R()


def api(path, timeout=60):
    r = run("curl -s --max-time %d %s%s" % (timeout, HTTP, path), timeout + 10)
    try:
        return json.loads(r.stdout)
    except Exception:
        return {}


def ui(expr, match="ui/index.html", timeout=40):
    r = run("node tools/uiprobe.mjs --timeout 8000 --match %s %s" % (match, json.dumps(expr)), timeout)
    out = (r.stdout or "").strip().splitlines()
    if not out:
        return None
    try:
        return json.loads(out[-1])
    except Exception:
        return out[-1]


def dump():
    run("%s -s %s shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1" % (ADB, SER))
    return run("%s -s %s shell cat /sdcard/ui.xml" % (ADB, SER)).stdout


def texts(xml):
    """text → 中心坐标（原生按钮没有 resource-id，靠文案点）"""
    out = {}
    for m in re.finditer(r'<node[^>]*text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml or ""):
        t = m.group(1)
        x1, y1, x2, y2 = map(int, m.groups()[1:])
        if t and t not in out:
            out[t] = ((x1 + x2) // 2, (y1 + y2) // 2)
    return out


def tap_text(xml, pred, wait_s=10, label=""):
    """按文案真点（先刷新 dump 再找，元素出现慢也不怕）。
    找不到时先按一下 BACK 把抽屉/菜单收起来再试 —— 抽屉开着时顶层按钮会被盖住（踩过）。"""
    t0 = time.time()
    tried_back = False
    while time.time() - t0 < wait_s:
        if time.time() - t0 > wait_s * 0.5 and not tried_back:
            run("%s -s %s shell input keyevent 4" % (ADB, SER))
            tried_back = True
            time.sleep(1)
        ts = texts(dump())
        for t, (x, y) in ts.items():
            if pred(t):
                run("%s -s %s shell input tap %d %d" % (ADB, SER, x, y))
                print("    真点了「%s」@(%d,%d)" % (t, x, y))
                return t
        time.sleep(1)
    print("    没找到可点的文案：%s" % label)
    return None


def tap_xy(x, y):
    run("%s -s %s shell input tap %d %d" % (ADB, SER, x, y))


def refwd():
    """App 一重启 pid 就变，9222 的 forward 会指向已死进程（踩过：之后所有 CDP 调用都 fetch failed）。
    每次重启 App 之后必须重新 forward。"""
    time.sleep(1)
    pid = run("%s -s %s shell pidof dev.cdp" % (ADB, SER)).stdout.strip()
    run("%s -s %s forward --remove-all" % (ADB, SER))
    if pid:
        run("%s -s %s forward tcp:9222 localabstract:webview_devtools_remote_%s" % (ADB, SER, pid))
    run("%s -s %s forward tcp:8848 tcp:8848" % (ADB, SER))
    time.sleep(1)


def chk(name, got, want):
    ok = want(got) if callable(want) else got == want
    checks.append((name, bool(ok), str(got)[:200]))
    print(("  PASS " if ok else "  FAIL ") + name + "  → " + str(got)[:200])
    return ok


def page_state():
    return ui("JSON.stringify({hits:window.__hits,trusted:window.__lastTrusted||null,"
              "cnt:(document.getElementById('cnt')||{}).textContent})", match="tap.html")


def main():
    print("[0] 干净起：重启 App，打开测试页")
    run("%s -s %s shell am force-stop dev.cdp" % (ADB, SER))
    time.sleep(2)
    run("%s -s %s shell am start -n dev.cdp/.MainActivity" % (ADB, SER))
    time.sleep(7)
    run("%s -s %s shell svc power stayon true" % (ADB, SER))
    refwd()
    api("/api/ui/close")
    st0 = api("/api/state")
    print("    健康检查：/api/state ok=%s" % (st0.get("ok")))
    api("/api/record?action=stop")        # 先把上一轮可能挂着的录制停掉
    api("/api/record/clear")
    api("/api/goto?url=" + PAGE)
    time.sleep(4)
    chk("测试页就绪（计数 0）", (page_state() or {}).get("hits"), 0)

    print("[1] 控制台里把录制方式选成「坐标录制」")
    xml = dump()
    tap_text(xml, lambda t: t.strip() == "☰", wait_s=6, label="☰")
    time.sleep(1.2)
    xml = dump()
    tap_text(xml, lambda t: "控制台" in t, wait_s=8, label="控制台")
    time.sleep(2)
    ui("window.__cdpTab && window.__cdpTab('rec')")
    time.sleep(1)
    got = ui("(function(){var s=document.getElementById('r-mode');s.value='coord';"
             "s.dispatchEvent(new Event('change',{bubbles:true}));return 'set';})()")
    time.sleep(1)
    native = api("/api/recording")
    chk("控制台选完方式已同步给原生（mode=coord）", native.get("mode"), "coord")

    print("[2] 真手指点 ☰ → 「● 开始监听（坐标录制）」，看原生是不是按坐标录制开录")
    xml = dump()
    tap_text(xml, lambda t: t.strip() == "☰", wait_s=6, label="☰")
    time.sleep(1.2)
    xml = dump()
    opened = tap_text(xml, lambda t: "开始监听" in t, wait_s=10, label="开始监听")
    chk("菜单里那条写明了方式（坐标录制）", opened, lambda t: t and "坐标" in t)
    time.sleep(2.5)
    st = api("/api/recording")
    chk("真点菜单 → 原生 recording=true", st.get("recording"), True)
    chk("原生按坐标录制（mode=coord）", st.get("mode"), "coord")

    print("[3] 真手指点测试页（整屏都是可点区：点屏幕中央就是点在页面上）")
    time.sleep(1)
    box = ui("JSON.stringify((function(){var b=document.getElementById('bigBtn').getBoundingClientRect();"
             "return {x:Math.round(b.left+b.width/2),y:Math.round(b.top+b.height/2),"
             "dpr:window.devicePixelRatio,vw:innerWidth,vh:innerHeight};})())", match="tap.html")
    chk("量到可点区（页内坐标 + dpr）", bool(isinstance(box, dict) and box.get("x")), True)
    # 屏幕坐标 = WebView 视口偏移 + 页内坐标 × dpr；这里直接点屏幕中央：
    # 页面是整屏可点，中央一定落在可点区里，不用去凑 DPR 换算（换算错会点空，踩过）
    tap_xy(540, 1400)
    time.sleep(2)
    st1 = page_state()
    chk("真手指点到了蓝块（页面计数 +1）", (st1 or {}).get("hits"), lambda h: h == 1)
    chk("这一下是真触摸（isTrusted）", (st1 or {}).get("trusted"), True)

    print("[4] 真手指点 ☰ →「■ 停止监听并保存」")
    live = api("/api/record/live")
    tape = live.get("steps") or []
    clicks = [s for s in tape if isinstance(s, dict) and s.get("t") == "click"]
    chk("录到了点击步", len(clicks), lambda n: n >= 1)
    if clicks:
        s = clicks[0]
        chk("坐标步：mode=coord", s.get("mode"), "coord")
        chk("坐标步：没有选择器（不看元素）", (s.get("target") or {}).get("selector"), lambda v: not v)
        anc = s.get("anchor") or {}
        chk("坐标步：有锚点坐标", [anc.get("cx"), anc.get("cy")], lambda v: v[0] is not None)
    xml = dump()
    tap_text(xml, lambda t: t.strip() == "☰", wait_s=6, label="☰")
    time.sleep(1.2)
    xml = dump()
    tap_text(xml, lambda t: "停止监听" in t, wait_s=10, label="停止监听")
    time.sleep(2.5)
    chk("停下来了", api("/api/recording").get("recording"), False)

    print("[5] 回放这条坐标脚本：页面计数应该再 +1")
    recs = [x for x in (api("/api/scripts").get("list") or []) if x.get("kind") == "recording"]
    recs = sorted(recs, key=lambda x: x.get("updated") or 0)
    if recs:
        target = recs[-1]
        print("    回放 %s（%s）" % (target.get("id"), target.get("name")))
        before = (page_state() or {}).get("hits")
        pl = api("/api/replay?script=%s" % target.get("id"), timeout=120)
        time.sleep(2.5)
        after = (page_state() or {}).get("hits")
        chk("回放整体 ok", pl.get("ok"), True)
        chk("回放把页面计数从 %s 推到 %s" % (before, after), [before, after],
            lambda v: v[0] is not None and v[1] == v[0] + 1)

    bad = [c for c in checks if not c[1]]
    print("\n结果：%d/%d 通过" % (len(checks) - len(bad), len(checks)))
    for c in bad:
        print("  未通过：" + c[0] + " → " + c[2])
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
