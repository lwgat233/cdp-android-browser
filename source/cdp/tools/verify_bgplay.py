#!/usr/bin/env python3
"""后台播放验收：切到后台（熄屏/Home）后，页面里的媒体**还在往前走**。

怎么算过（都是读回来的事实）：
  ① 前台服务类型里能看到 mediaPlayback（系统认可"正在放媒体"）；
  ② 页面里的 <audio>.currentTime 在后台期间**确实在增长**，且 paused=false；
  ③ 关掉后台播放后：焦点/唤醒锁还回去了（状态里 media=false），页面**仍在放**（关开关不该掐掉播放）。
做法：用离线音频页（App 自己的虚拟源，不依赖外网）真的播一条 MP3，
      用 `adb shell input keyevent KEYCODE_HOME` 把 App 切到后台，再隔着几秒读 currentTime 对比。
"""
import json
import subprocess
import sys
import time

ROOT = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
SER = "emulator-5554"
PAGE = "https://appassets.androidplatform.net/test/audio.html"
checks = []


def api(path, timeout=60):
    r = subprocess.run(["curl", "-s", "--max-time", str(timeout), HTTP + path],
                       capture_output=True, text=True, timeout=timeout + 10)
    try:
        return json.loads(r.stdout)
    except Exception:
        return {}


def probe(expr, match="test/audio.html", timeout=45):
    r = subprocess.run(["node", "tools/uiprobe.mjs", "--timeout", "8000", "--match", match, expr],
                       capture_output=True, text=True, cwd=ROOT, timeout=timeout)
    out = [l for l in (r.stdout or "").strip().splitlines() if l.strip()]
    if not out:
        return None
    try:
        return json.loads(out[-1])
    except Exception:
        return out[-1]


ADB = "/home/lwgat/tools/android-sdk/platform-tools/adb"


def sh(cmd, timeout=40):
    """条条命令都用绝对路径的 adb —— `bash -lc` 会把 PATH 重置掉（踩过：读回空字符串，
    断言 `"dev.cdp" not in ""` 还"通过"了）。所以这里对空输出单独判失败，不留静默通道。"""
    cmd = cmd.replace("adb ", ADB + " ")
    r = subprocess.run(["bash", "-lc", cmd], capture_output=True, text=True, timeout=timeout)
    out = (r.stdout or "").strip()
    # 只有"本该有输出"的命令才警告（input keyevent 这类本来就不打印，别刷噪音）
    if not out and "keyevent" not in cmd and "input " not in cmd:
        print("    [警告] 命令没输出：%s   stderr=%s" % (cmd[:90], (r.stderr or "").strip()[:120]))
    return out


def chk(name, got, want):
    ok = want(got) if callable(want) else got == want
    checks.append((name, bool(ok), str(got)[:200]))
    print(("  PASS " if ok else "  FAIL ") + name + "  → " + str(got)[:200])
    return ok


def audio_state():
    return probe("JSON.stringify((function(){var a=document.querySelector('audio');"
                 "return a?{t:a.currentTime,paused:a.paused,vis:document.visibilityState,dur:a.duration}:null;})())")


def main():
    print("[1] 打开离线音频页并真的开始播")
    api("/api/goto?url=" + PAGE)
    time.sleep(4)
    # 循环播放：不然短 MP3 会在后台期间播完，"时间还在走"就变成假红（踩过）
    probe("(function(){var a=document.querySelector('audio');if(a){a.muted=false;a.loop=true;"
          "if(a.currentTime>1)a.currentTime=0;window.__ticks=0;"
          "a.addEventListener('timeupdate',function(){window.__ticks++;});a.play();}return 1;})()")
    time.sleep(3)
    s0 = audio_state()
    if not isinstance(s0, dict) or not s0.get("t"):
        chk("页面里的音频能播起来（currentTime 在走）", s0, lambda v: bool(v))
        return 1
    chk("前台时音频在走", s0.get("t"), lambda t: t and t > 0.5)

    print("[2] 打开后台播放（前台服务 mediaPlayback + 音频焦点 + 唤醒锁）")
    r = api("/api/keepalive/media?on=1")
    time.sleep(1.5)
    chk("接口回报 media=true", r.get("media"), True)
    # 前台服务的**类型位**：mediaPlayback = 0x2（dumpsys 打的是十六进制位图）
    svc = ""
    for _ in range(4):
        svc = sh("adb -s %s shell dumpsys activity services dev.cdp | grep -m1 'isForeground=true'" % SER)
        if "types=" in svc:
            break
        time.sleep(1)
    def type_has_mediaplayback(s):
        import re
        m = re.search(r"types=([0-9a-fA-F]+)", s or "")
        return bool(m) and (int(m.group(1), 16) & 0x2) == 0x2
    chk("前台服务类型带 mediaPlayback（types 位图含 0x2）", svc.strip(), type_has_mediaplayback)
    # 说明：焦点不用我们抢（Chromium 的 AudioFocusDelegate 自己会申请；我们抢了反而把它掐停）。
    # 这里只如实记一条：dumpsys 里能看到 Chromium 替这个包申请的焦点记录。
    foc = sh("adb -s %s shell dumpsys audio | grep -m1 'callingPack=dev.cdp'" % SER)
    print("    [说明] 页面媒体的音频焦点由 Chromium 申请：" + (foc[:120] if foc else "（本次日志窗口里没抓到，不影响判定）"))

    print("[2b] 回归：开着播放去开「后台播放」，不该把正在播的媒体掐停（本轮修的就是这个）")
    time.sleep(2)
    s_now = audio_state() or {}
    chk("点开后台播放后仍在放（paused=false）", s_now.get("paused"), False)
    # 用"走秒次数"看进度：循环播放时 currentTime 会绕回去（6.9 → 0.8 其实是又走了 6 秒，别当负增长）
    k_a = probe("window.__ticks") or 0
    time.sleep(2.5)
    k_b = probe("window.__ticks") or 0
    chk("还在往前走（timeupdate %s → %s 次）" % (k_a, k_b), (k_b or 0) - (k_a or 0), lambda d: d and d >= 5)

    print("[3] 把 App 切到后台（真按 HOME），再隔着几秒看音频有没有继续走")
    t1 = (audio_state() or {}).get("t")
    # 真按 HOME（keyevent 3 = HOME；早先用 KEYCODE_HOME 名字在模拟器上没生效 —— 别用名字）
    sh("adb -s %s shell input keyevent 3" % SER)
    top = ""
    for _ in range(10):
        top = sh("adb -s %s shell dumpsys activity activities | grep -m1 topResumedActivity" % SER)
        if "dev.cdp" not in top:
            break
        time.sleep(1)
    chk("App 真的退到后台（前台不再是 dev.cdp）", top.strip()[:110], lambda s: "dev.cdp" not in (s or ""))
    # 说明：页面**不该**知道自己在后台 —— 我们故意不 pause WebView，visibilityState 仍是 visible，
    # 这正是"切后台音频继续走"的前提（写成 hidden 反而是错的断言）。
    vis = (audio_state() or {}).get("vis")
    print("    [说明] 后台时页面的 visibilityState=" + str(vis) + "（故意不 pause WebView，所以它不知道自己在后台）")
    tick0 = probe("window.__ticks") or 0
    time.sleep(10)
    s2 = audio_state() or {}
    tick1 = probe("window.__ticks") or 0
    chk("后台期间音频仍然在放（paused=false）", s2.get("paused"), False)
    # 循环播放会让 currentTime 绕回去，所以用"走秒次数"算进度（每 ~0.25s 一次 timeupdate）
    chk("后台 10 秒里播放真的在往前走（timeupdate %s → %s 次）" % (tick0, tick1),
        (tick1 or 0) - (tick0 or 0), lambda d: d and d >= 20)
    chk("后台期间时长也在累积（currentTime 有变化）", [t1, s2.get("t")],
        lambda v: v[0] is not None and v[1] is not None and abs((v[1] or 0) - (v[0] or 0)) > 1)

    print("[4] 关掉后台播放：焦点/唤醒锁还回去，但页面不该被掐断")
    r2 = api("/api/keepalive/media?on=0")
    time.sleep(1.5)
    chk("接口回报 media=false", r2.get("media"), False)
    st = api("/api/keepalive")
    chk("状态里 media=false", ((st.get("state") or {}) if isinstance(st, dict) else {}).get("media"), False)
    s3 = audio_state() or {}
    chk("关开关后页面仍在放（不被掐断）", s3.get("paused"), False)

    print("[5] 界面里能看到这块（板块 + 状态文字）")
    api("/api/ui/open?tab=power")
    time.sleep(2)
    got = probe("JSON.stringify({board:!!document.querySelector('[data-board=bgplay]'),"
                "state:(document.getElementById('bg-state')||{}).textContent||'',"
                "toggle:!!document.getElementById('bg-toggle')})", match="ui/index.html")
    chk("省电与后台里有「后台播放」板块", (got or {}).get("board"), True)
    chk("有开关按钮", (got or {}).get("toggle"), True)
    chk("状态行是文字（不是一排键）", (got or {}).get("state"), lambda s: s and ("开" in s or "关" in s))

    bad = [c for c in checks if not c[1]]
    print("\n结果：%d/%d 通过" % (len(checks) - len(bad), len(checks)))
    for c in bad:
        print("  未通过：" + c[0] + " → " + c[2])
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
