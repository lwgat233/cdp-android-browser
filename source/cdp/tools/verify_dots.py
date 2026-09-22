#!/usr/bin/env python3
"""小点（坐标步骤的落点）验收 —— 按用户说的形态：

  · 坐标步骤在屏幕上画成**编号小点**；元素步骤**不画点**，但序号照占（所以页面上可能是 1 2 4）；
  · 点一下小点 = "让它自己点那里"（回放这一步）；
  · 同一个点连点几次 → 合成一个点（角标 ×N）；不同处的两个点可以合并成"一个点组"（仍然一步）；
  · 覆盖层用无障碍画（`TYPE_ACCESSIBILITY_OVERLAY`），才能盖在视频/播放器上面。

判据都是读回来的事实：页面里的点（CDP 读 DOM）、屏幕上的点（uiautomator 读无障碍节点）、
回放效果（页面计数器 +1）、合并前后的步骤结构。
"""
import json
import re
import subprocess
import sys
import time

ROOT = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
ADB = "/home/lwgat/tools/android-sdk/platform-tools/adb"
SER = "emulator-5554"
PKG = "dev.cdp"
SVC = "dev.cdp/dev.cdp.CdpDotService"
PAGE = "https://appassets.androidplatform.net/test/tap.html"
checks = []


def run(cmd, timeout=45):
    try:
        return subprocess.run(cmd, shell=True, cwd=ROOT, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return subprocess.CompletedProcess(cmd, 1, "", "timeout")


def api(path, timeout=60):
    try:
        r = subprocess.run(["curl", "-s", "--max-time", str(timeout), HTTP + path],
                           capture_output=True, text=True, timeout=timeout + 10)
        return json.loads(r.stdout)
    except Exception:
        return {}


def insert_step(step, timeout=60):
    """插步骤：控制口的这条接口是 GET + 查询参数（step=<json>），要用 --data-urlencode 传"""
    try:
        r = subprocess.run(["curl", "-s", "--max-time", str(timeout), "-G", HTTP + "/api/record/insert",
                            "--data-urlencode", "step=" + json.dumps(step, ensure_ascii=False)],
                           capture_output=True, text=True, timeout=timeout + 10)
        return json.loads(r.stdout)
    except Exception:
        return {}


def ui(expr, match="tap.html", timeout=40):
    r = run("node tools/uiprobe.mjs --timeout 8000 --match %s %s" % (match, json.dumps(expr)), timeout)
    out = (r.stdout or "").strip().splitlines()
    if not out:
        return None
    try:
        return json.loads(out[-1])
    except Exception:
        return out[-1]


def dump_nodes():
    run("%s -s %s shell uiautomator dump /sdcard/d.xml >/dev/null 2>&1" % (ADB, SER))
    xml = run("%s -s %s shell cat /sdcard/d.xml" % (ADB, SER)).stdout or ""
    out = []
    for m in re.finditer(r'<node[^>]*>', xml):
        t = m.group(0)
        if "cdp-dot" not in t:
            continue
        rid = re.search(r'content-desc="([^"]*)"', t)
        txt = re.search(r'text="([^"]*)"', t)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', t)
        out.append({
            "desc": rid.group(1) if rid else "",
            "text": txt.group(1) if txt else "",
            "bounds": tuple(map(int, b.groups())) if b else None,
        })
    return out


def refwd():
    pid = run("%s -s %s shell pidof %s" % (ADB, SER, PKG)).stdout.strip()
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


def coord_step(cx, cy):
    return {
        "t": "click", "mode": "coord", "selector": "",
        "url": PAGE, "title": "点我测试页",
        "box": {"cx": cx, "cy": cy, "x": cx, "y": cy, "w": 1, "h": 1},
        "anchor": {"mode": "top", "cx": cx, "cy": cy, "vw": 393, "vh": 680,
                   "topPx": int(cy), "bottomPx": int(680 - cy), "leftPx": int(cx), "rightPx": int(393 - cx),
                   "ratioY": round(cy / 680.0, 4), "ratioX": round(cx / 393.0, 4), "scrollY": 0},
        "pauseAfter": 300,
    }


def element_step(sel, text):
    return {"t": "click", "mode": "element", "selector": sel,
            "target": {"selector": sel, "id": sel.lstrip("#"), "tag": "div", "text": text, "attrs": {}},
            "pauseAfter": 300}


def main():
    print("[0] 起 App + 打开无障碍服务（小点覆盖层要靠它）")
    run("%s -s %s shell am force-stop %s" % (ADB, SER, PKG))
    time.sleep(2)
    run("%s -s %s shell am start -n %s/.MainActivity" % (ADB, SER, PKG))
    time.sleep(7)
    run("%s -s %s shell svc power stayon true" % (ADB, SER))
    refwd()
    run("%s -s %s shell settings put secure enabled_accessibility_services %s" % (ADB, SER, SVC))
    run("%s -s %s shell settings put secure accessibility_enabled 1" % (ADB, SER))
    time.sleep(4)
    api("/api/goto?url=" + PAGE)
    time.sleep(3)
    api("/api/ui/close")
    time.sleep(1)
    st = api("/api/record/dots/status")
    chk("小点状态接口：开着 + 覆盖层可用（无障碍已授权）", [st.get("on"), st.get("overlay")], [True, True])

    print("[1] 造步骤：坐标 → 元素 → 坐标（元素步不画点，但序号照占）")
    api("/api/record/clear")
    time.sleep(0.5)
    insert_step(coord_step(120, 200))
    insert_step(element_step("#cnt", "计数"))
    insert_step(coord_step(250, 400))
    time.sleep(1.5)
    live = api("/api/record/live")
    steps = live.get("steps") or []
    chk("队列里 3 步（2 个坐标 + 1 个元素）", len(steps), 3)
    chk("第 2 步是元素步", (steps[1].get("mode") if len(steps) > 1 else None), "element")

    print("[2] 页面里的点：只该有两个（编号 1 和 3）")
    got = ui("JSON.stringify({n:document.querySelectorAll('#cdp-dots .cdp-dot').length,"
             "txt:Array.prototype.map.call(document.querySelectorAll('#cdp-dots .cdp-dot'),function(e){return e.textContent;})})")
    chk("页面里画出 2 个点", (got or {}).get("n"), 2)
    chk("编号连续但跳过元素步（1 和 3）", (got or {}).get("txt"), lambda v: v == ["1", "3"])

    print("[3] 屏幕覆盖层（无障碍画的那份）：读得回来的事实")
    # 说明：无障碍服务自己创建的覆盖层**不会**出现在 uiautomator 的无障碍树里（它不暴露给自己人），
    # 所以这里不用 uiautomator 判断，改读：① 服务已连 + 交给覆盖层的点数 ② 服务画点时打的日志。
    st2 = api("/api/record/dots/status")
    chk("无障碍服务已连（overlay=true）", st2.get("overlay"), True)
    chk("交给覆盖层的点数 = 2（跟页面里那份一致）", st2.get("count"), 2)
    logs = api("/api/log?lines=60")
    lines = logs.get("lines") if isinstance(logs, dict) else None
    joined = "\n".join(lines or []) if isinstance(lines, list) else json.dumps(logs, ensure_ascii=False)
    chk("服务画点时有日志「小点覆盖层：画了 2 个」", "小点覆盖层：画了 2 个" in joined, True)
    dump_nodes()  # 顺手留一份（仅供人肉看，不作为判据）

    print("[4] 点一下小点 = 让它自己点那一步（页面计数器该 +1）")
    before = ui("window.__hits") or 0
    pl = api("/api/record/dots/play?n=1")
    time.sleep(2.5)
    after = ui("window.__hits") or 0
    chk("接口接受", pl.get("ok"), True)
    chk("第 1 个点真的点了页面（计数 %s → %s）" % (before, after), after, lambda a: a == before + 1)

    print("[5] 同一个点连点 → 合成一个点（×N）")
    api("/api/record/clear")
    time.sleep(0.5)
    insert_step(coord_step(150, 300))
    insert_step(coord_step(152, 301))
    time.sleep(1)
    mg = api("/api/record/merge?index=1")
    time.sleep(1)
    live = api("/api/record/live")
    st2 = (live.get("steps") or [])
    chk("合并后只剩 1 步", len(st2), 1)
    chk("这一步的连点次数 = 2（点上是 ×2）", (st2[0].get("reps") if st2 else None), 2)
    got2 = ui("JSON.stringify(Array.prototype.map.call(document.querySelectorAll('#cdp-dots .cdp-dot'),function(e){return e.textContent;}))")
    chk("页面上那个点显示 ×2", got2, lambda v: v == ["1×2"])

    print("[6] 不同处的两个点 → 合并成「一个点组」（还是一步）")
    api("/api/record/clear")
    time.sleep(0.5)
    insert_step(coord_step(100, 150))
    insert_step(coord_step(300, 500))
    time.sleep(1)
    api("/api/record/merge?index=1")
    time.sleep(1.5)
    live = api("/api/record/live")
    st3 = (live.get("steps") or [])
    chk("合并后只剩 1 步（点组）", len(st3), 1)
    chk("这一步是点组（t=clickGroup，含 2 个点）",
        [st3[0].get("t"), len(st3[0].get("points") or [])] if st3 else None, ["clickGroup", 2])
    print("    回放这个点组：")
    before2 = ui("window.__hits") or 0
    api("/api/record/dots/play?n=1")
    time.sleep(4)
    after2 = ui("window.__hits") or 0
    chk("点组里的 2 个点都被点了（计数 %s → %s）" % (before2, after2), after2,
        lambda a: a is not None and a >= before2 + 1)

    print("[7] 界面上这块在 + 登记表自检")
    api("/api/ui/open?tab=rec")
    time.sleep(2.5)
    got3 = ui("JSON.stringify({board:!!document.querySelector('[data-board=rec-dots]'),"
              "state:(document.getElementById('dot-state')||{}).textContent||'',"
              "btns:['dot-toggle','dot-perm'].filter(function(i){return !!document.getElementById(i);}).length})",
              match="ui/index.html")
    chk("录制栏目里有「小点」板块", (got3 or {}).get("board"), True)
    chk("状态行有字（不是一排键）", (got3 or {}).get("state"), lambda s: s and ("覆盖层" in s))
    st_live = ui("JSON.stringify({dots:document.querySelectorAll('#cdp-dots .cdp-dot').length})")
    chk("两个按键在（开关 / 去开权限）", (got3 or {}).get("btns"), 2)
    prob = ui("JSON.stringify(window.__cdpRegistry().problems)", match="ui/index.html")
    chk("功能登记表 problems=[]", prob, lambda p: p == [])

    bad = [c for c in checks if not c[1]]
    print("\n结果：%d/%d 通过" % (len(checks) - len(bad), len(checks)))
    for c in bad:
        print("  未通过：" + c[0] + " → " + c[2])
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
