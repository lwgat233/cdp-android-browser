#!/usr/bin/env python3
"""只验两条（配套 verify_sniff_yhtn.py 用）：内置播放器状态 + 下载进度。

为什么要单独一套：整套跑要"开页面 → iframe 起播 → 内置播放器 → 下 1999 个分片"，
这个负载下**模拟器容器会 segfault**（本例实测死了两次），于是后面那两条读到空，看着像产品坏了。
所以这两条单独跑、跑完立刻停掉下载线程，环境压力小、结果才可信。

判据（读回来的事实）：
  [1] 内置播放器打开嗅探到的 m3u8 后，`/api/player/state` 读得到：是播放器页、有 video、正在放、总时长>1s
  [2] m3u8 下载期间，下载记录里 segsDone/segsTotal/bytes 会涨（不是一直 0）
"""
import json
import os
import subprocess
import sys
import time
import urllib.parse

ROOT = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
ADB = "/home/lwgat/tools/android-sdk/platform-tools/adb"
ENV = dict(os.environ, PATH="/home/lwgat/.local/bin:" + os.environ.get("PATH", ""))
checks = []


def api(path, timeout=90):
    try:
        r = subprocess.run(["curl", "-s", "--max-time", str(timeout), HTTP + "/api/" + path],
                           capture_output=True, text=True, timeout=timeout + 10)
        return json.loads(r.stdout)
    except Exception:
        return {}


def sh(c, t=90):
    try:
        return subprocess.run(["bash", "-lc", c], capture_output=True, text=True, timeout=t).stdout.strip()
    except subprocess.TimeoutExpired:
        return ""


def chk(name, got, want):
    ok = want(got) if callable(want) else got == want
    checks.append((name, bool(ok), str(got)[:200]))
    print(("  PASS " if ok else "  FAIL ") + name + "  → " + str(got)[:200])
    return ok


def main():
    st = api("status")
    if not st.get("ok"):
        print("控制口不通（模拟器/App 没起来）——先起环境再跑，别把它算成产品问题")
        return 2
    print("[0] 打开樱花动漫播放页，让嗅探拿到真链")
    api("sniff/clear")
    api("net/clear")
    # 要像人一样进去：先进详情页（带上 referer），再点「立即播放」——直接开播放页时 iframe 里的播放器不起播
    api("goto?url=" + urllib.parse.quote("https://yhtn.cc/v/1374711/229", safe=""), timeout=60)
    time.sleep(9)
    r = api("eval?js=" + urllib.parse.quote(
        "(function(){var a=Array.prototype.filter.call(document.querySelectorAll('a'),"
        "function(x){return (x.textContent||'').indexOf('立即播放')>=0})[0];return a?a.href:'';})()", safe=""))
    href = str((r or {}).get("raw") or "").strip()
    print("    立即播放 → " + href[:80])
    api("goto?url=" + urllib.parse.quote(href or "https://yhtn.cc/p/1374711/229/0", safe=""), timeout=60)
    time.sleep(16)
    m3 = [x for x in (api("sniff").get("list") or []) if x.get("kind") == "m3u8"]
    chk("嗅探拿到 m3u8", len(m3), lambda n: n >= 1)
    if not m3:
        return 1
    u = m3[0]["url"]

    print("[1] 内置播放器状态（新能力：以前只支持「打开」、读不到状态）")
    api("player?url=" + urllib.parse.quote(u, safe=""), timeout=60)
    stt = {}
    for _ in range(10):
        time.sleep(2)
        stt = api("player/state") or {}
        if stt.get("playerPage") and stt.get("hasVideo") and (stt.get("ms") or 0) > 0:
            break
    print("    player.state = " + json.dumps(stt, ensure_ascii=False)[:220])
    chk("读到的就是播放器页面", stt.get("playerPage"), True)
    chk("有 video 且已在放（ms>0）", [stt.get("hasVideo"), (stt.get("ms") or 0) > 0], [True, True])
    chk("读得到总时长（>1 秒）", (stt.get("durMs") or 0), lambda n: n and n > 1000)

    print("[2] m3u8 下载进度（要看得见，不能一直 0）")

    def keys():
        return set((r.get("name"), r.get("ts")) for r in (api("downloads").get("list") or []))

    before = keys()
    api("sniff/download?url=" + urllib.parse.quote(u, safe=""), timeout=60)
    rec = {}
    for _ in range(15):
        time.sleep(3)
        fresh = [r for r in (api("downloads").get("list") or [])
                 if (r.get("name"), r.get("ts")) not in before]
        if fresh:
            rec = fresh[-1]
        if (rec.get("segsDone") or 0) >= 10 and (rec.get("bytes") or 0) > 0:
            break
    print("    记录：" + json.dumps({k: rec.get(k) for k in ("name", "state", "bytes", "segsDone", "segsTotal")},
                                  ensure_ascii=False)[:220])
    chk("有分片进度（segsDone>=10 且 segsTotal 有值）",
        [rec.get("segsTotal") is not None, rec.get("segsDone")], lambda v: v[0] and (v[1] or 0) >= 10)
    chk("bytes 不再是 0", rec.get("bytes") or 0, lambda n: n and n > 0)

    print("[3] 收尾：停掉下载线程 + 清下载目录（别让它把 1999 片拉完、也别把模拟器压崩）")
    api("downloads/delete?name=" + urllib.parse.quote(str(rec.get("name") or ""), safe=""), timeout=60)
    sh("%s -s emulator-5554 shell am force-stop dev.cdp; sleep 2; "
       "%s -s emulator-5554 shell run-as dev.cdp rm -rf files/downloads 2>/dev/null; "
       "%s -s emulator-5554 shell am start -n dev.cdp/.MainActivity >/dev/null 2>&1; sleep 8"
       % (ADB, ADB, ADB))
    print("    已停掉并清干净")

    bad = [c for c in checks if not c[1]]
    print("\n结果：%d/%d 通过" % (len(checks) - len(bad), len(checks)))
    for c in bad:
        print("  未通过：" + c[0] + " → " + c[2])
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
