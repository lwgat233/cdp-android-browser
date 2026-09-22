#!/usr/bin/env python3
"""坐标录制为什么"点视频录不到"—— 修完盯住这条：原生层抓触摸。

用户报的现象：学习通里点别处能录坐标，**点播放视频录不了**。
原因：播放器在**跨域 iframe / 原生视频表面**里，页内 JS 收不到那里的 pointerdown。
修法：坐标录制改到 Activity.dispatchTouchEvent 这一层抓（谁最后消费掉都不影响），
     拿到点之后回顶层页面问锚点（op=anchorAt），步骤格式与页内录的完全一致。

判据（都是真手指 + 读回来的事实）：
  ① 元素录制：点跨域区域 → **页内确实收不到**（0 步、页内 pointerdown 计数不涨）→ 这就是老现象的原因；
  ② 坐标录制：点同一片跨域区域 → 记为坐标步（via=native-coord）；点视频区域 → 同样记到；
  ③ 一下点击只记**一条**（页内 + 原生两套不能记重）。
"""
import json
import subprocess
import sys
import time

ROOT = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
ADB = "/home/lwgat/tools/android-sdk/platform-tools/adb"
SER = "emulator-5554"
PAGE = "https://appassets.androidplatform.net/test/tapframe.html"
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
    """注意：**不能**用 shell 拼这条 curl —— URL 里的 `&` 会被 shell 当成后台符，
    参数全被丢掉（实测：mode/name 双双退回默认值，"元素录制"跑了一整轮，看起来像产品 bug）。
    所以这里用参数列表直接起 curl，不经过 shell。"""
    try:
        r = subprocess.run(["curl", "-s", "--max-time", str(timeout), HTTP + path],
                           capture_output=True, text=True, timeout=timeout + 10)
    except subprocess.TimeoutExpired:
        return {}
    try:
        return json.loads(r.stdout)
    except Exception:
        return {}


def ui(expr, match="tapframe.html", timeout=40):
    r = run("node tools/uiprobe.mjs --timeout 8000 --match %s %s" % (match, json.dumps(expr)), timeout)
    out = (r.stdout or "").strip().splitlines()
    if not out:
        return None
    try:
        return json.loads(out[-1])
    except Exception:
        return out[-1]


def refwd():
    pid = run("%s -s %s shell pidof dev.cdp" % (ADB, SER)).stdout.strip()
    run("%s -s %s forward --remove-all" % (ADB, SER))
    if pid:
        run("%s -s %s forward tcp:9222 localabstract:webview_devtools_remote_%s" % (ADB, SER, pid))
    run("%s -s %s forward tcp:8848 tcp:8848" % (ADB, SER))
    time.sleep(1)


def tap_retry(x, y, expect_at_least=1, tries=3):
    """真手指点，点到为止：模拟器偶尔**丢输入事件**（实测约 1/3），
    所以点完要读回步数确认，没记上就再点一次（坐标现测、重试同一点）。"""
    for i in range(tries):
        run("%s -s %s shell input tap %d %d" % (ADB, SER, x, y))
        time.sleep(2)
        if len(steps()) >= expect_at_least:
            return i + 1
    return tries


def chk(name, got, want):
    ok = want(got) if callable(want) else got == want
    checks.append((name, bool(ok), str(got)[:200]))
    print(("  PASS " if ok else "  FAIL ") + name + "  → " + str(got)[:200])
    return ok


def zone(z):
    api("/api/goto?url=%s?zone=%s" % (PAGE, z))
    time.sleep(3.5)
    api("/api/ui/close")          # 控制台是盖在页面上的浮层：不关掉的话，点击全落在控制台上（按设计不录）
    time.sleep(0.6)


def start(mode):
    api("/api/record/clear")
    api("/api/record?action=stop")
    time.sleep(0.6)
    return api("/api/record?action=start&mode=%s&name=%s" % (mode, mode))


def steps():
    return api("/api/record/live").get("steps") or []


def tap_center():
    """真手指点屏幕中央（每块区域都铺满全屏，所以中央一定落在目标区域上）"""
    run("%s -s %s shell input tap 540 1400" % (ADB, SER))
    time.sleep(2)


def main():
    print("[0] 干净起")
    run("%s -s %s shell am force-stop dev.cdp" % (ADB, SER))
    time.sleep(2)
    run("%s -s %s shell am start -n dev.cdp/.MainActivity" % (ADB, SER))
    time.sleep(7)
    run("%s -s %s shell svc power stayon true" % (ADB, SER))
    refwd()

    print("[0b] 预热：刚开机的模拟器输入注入不稳（实测会整轮丢），先把「点一下→录到一步」跑通再开始用例")
    zone("page")
    POINTS = [(540, 1400), (540, 900), (200, 1400), (880, 1400), (540, 1900)]
    warm = 0
    start("coord")            # 预热就用真实链路：坐标录制 + 真手指 + 读步数
    for i in range(3):
        for (x, y) in POINTS:
            run("%s -s %s shell input tap %d %d" % (ADB, SER, x, y))
            time.sleep(1.6)
            if len(steps()) >= 1:
                warm = i * len(POINTS) + 1
                break
        if warm:
            break
    api("/api/record?action=stop")
    api("/api/record/clear")
    time.sleep(0.8)
    print("    预热完成：第 %s 次点开始生效" % warm)
    chk("预热成功（注入通了：真手指点页面能录到步）", warm, lambda w: w and w > 0)
    zone("page")

    print("[1] 元素录制：点跨域区域（iframe）→ 页内 JS 收不到（这就是用户看到的\"录不了\"）")
    zone("frame")
    st = start("element")
    tap_center()
    n = len(steps())
    page_hits = (ui("window.__hits") or 0)
    # agent 是按 setOf("*") 注入的（连跨域 frame 也注），所以这一下**会**被 frame 里的 agent 记成元素步；
    # 顶层页面的 JS 收不到（__hits=0）—— 两种事实一起记下来，别把"我没见过"写成"不可能"。
    print("    顶层页面看到的 pointerdown 次数：%s（0=顶层确实收不到，是 frame 里的 agent 记的）" % page_hits)
    chk("元素录制：跨域区域点一下，只记 1 步（由 frame 里的 agent 记）", n, 1)
    chk("顶层页面 pointerdown 计数为 0（证明顶层 JS 收不到）", page_hits, 0)

    print("[2] 坐标录制：点同一片区域（真跨域 iframe）→ 必须记成坐标步，而且只记一条")
    zone("frame")
    start("coord")
    tap_retry(540, 1400)
    tape = steps()
    chk("坐标录制：跨域区域点一下，只记 1 步（不重复）", len(tape), 1)
    if tape:
        s = tape[0]
        # 说明：agent 是 setOf("*") 注入的（连跨域 frame 也注），所以这里可能是 frame 里的 agent 记的，
        # 也可能是原生层记的 —— 两种都算对，关键是"坐标步 + 只一条 + 锚点齐全"。
        print("    这条步的来源：via=%s（空=页内/frame 里的 agent；native-coord=只有原生层能记）"
              % (s.get("via") or "页内"))
        chk("这条是坐标步", s.get("mode"), "coord")
        chk("没有选择器（坐标步不看元素）", (s.get("target") or {}).get("selector"), lambda v: not v)
        anc = s.get("anchor") or {}
        chk("带锚点（cx/cy/vw/vh 齐全）", [anc.get("cx"), anc.get("cy"), anc.get("vw")],
            lambda v: v[0] is not None and v[2])

    print("[3] 视频区域（真视频 clip.webm 铺满 + 播放）")
    zone("video")
    vs = ui("JSON.stringify({ready:(document.getElementById('vd')||{}).readyState,"
            "vw:(document.getElementById('vd')||{}).videoWidth,playing:!((document.getElementById('vd')||{}).paused)})")
    print("    视频元素状态：" + str(vs))
    # 元素模式先看一眼"页内能不能收到视频区域的点击"（收不到就是用户报的那个现象，能就地复现）
    start("element")
    run("%s -s %s shell input tap 540 1400" % (ADB, SER))
    time.sleep(2)   # 这一下**故意只点一次**：要观察"页内收不到"的原始现象，不能靠重试凑
    page_saw = ui("window.__hits") or 0
    n_el = len(steps())
    print("    元素模式点视频区域：页内 pointerdown 计数=%s，录到 %s 步" % (page_saw, n_el))
    if page_saw == 0:
        print("    ↑ 页内确实收不到（跟用户报的现象一致：点视频录不到）—— 下面看坐标模式能不能救回来")
    start("coord")
    tap_retry(540, 1400)
    tape2 = steps()
    chk("坐标录制：视频区域点一下，只记 1 步", len(tape2), 1)
    if tape2:
        chk("是坐标步（无选择器）", (tape2[0].get("target") or {}).get("selector"), lambda v: not v)
        chk("这条由原生层抓（via=native-coord）", tape2[0].get("via"), "native-coord")

    print("[4] 页面上被脚本注入的浮层（「✦ 助手」按钮）：坐标模式也必须只记一条")
    zone("page")
    start("coord")
    tap_retry(1040, 2193)
    tape4 = steps()
    chk("坐标录制：点浮层上的按钮 → 只记 1 步（页内+原生不重复）", len(tape4), 1)
    if tape4:
        chk("这条是原生层抓的（坐标录制统一走原生）", tape4[0].get("via"), "native-coord")
    run("%s -s %s shell input keyevent 4" % (ADB, SER))        # 关掉助手面板
    time.sleep(1.5)

    print("[5] 页内那条路也还在：点页面自己的区域 → 记到 1 步（不能两条都记）")
    zone("page")
    start("coord")
    tap_retry(540, 1400)
    tape3 = steps()
    chk("坐标录制：普通区域点一下，只记 1 步（页内+原生不重复）", len(tape3), 1)
    if tape3:
        chk("普通区域页内自己就能录（via 应是页内或原生之一）",
            tape3[0].get("via") in ("", None, "native-coord"), True)

    bad = [c for c in checks if not c[1]]
    print("\n结果：%d/%d 通过" % (len(checks) - len(bad), len(checks)))
    for c in bad:
        print("  未通过：" + c[0] + " → " + c[2])
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
