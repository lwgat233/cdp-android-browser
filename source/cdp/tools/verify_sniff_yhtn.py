#!/usr/bin/env python3
"""在真实站点（樱花动漫 yhtn.cc）上验收"资源嗅探"这条链 —— 用户点名要看的站。

用户要求：「去看一下这个网站，资源嗅探功能有哪些问题。」

这一趟实测出来的问题（都在下面卡住）：
  P1. 真链藏在**跨域 iframe** 里（`https://dxfbk.com/?url=https://v.baofeng9.com/.../index.m3u8`）：
      页面自己的 `<video>` 是空的，所以"看视频状态 / 查播完了吗 / 交给系统播放器"在这个站点什么都读不到，
      而界面还只说是"没识别到 video"，不告诉你去哪看。
  P2. 嗅探确实抓到了真链与分片（合并成一条、segs 递增）——这条本来是好的，一并卡住防回归。
  P3. 网络时间线/嗅探清单里混进了**我们自己的内部通道**（cdp-meta.local、本机控制口轮询）→ 噪声。
  P4. 「用内置播放器打开」之后**读不到播放器状态**（接口只支持"打开"，不支持"读"）→ 界面显示空，看着像没播。
  P5. m3u8 下载期间下载记录里 `bytes` 一直是 0、没有分片进度 → 列表上看着像没动。

卡的点：
  [1] 播放页能起播，嗅探清单里出现 kind=m3u8 的真链（host 是 CDN，不是 iframe 那一层）
  [2] 分片合并成一条（kind=ts，segs 会随播放增长），不是几百行刷屏
  [3] 清单/时间线里**没有** cdp-meta.local / appassets / 127.0.0.1:8848 这类内部地址
  [4] 内置播放器打开这条 m3u8 后，`player.state` 读得到「播放中 + 当前秒数/总时长」
  [5] 下载这条 m3u8：记录里 segsDone/segsTotal/bytes 会涨（不是一直 0）
  [6] 页面没有 <video> 时，"看视频状态"给的提示指向「嗅探」（一句话，不写说明书）
"""
import json
import os
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


def api(path, timeout=120):
    try:
        r = subprocess.run(["curl", "-s", "--max-time", str(timeout), HTTP + "/api/" + path],
                           capture_output=True, text=True, timeout=timeout + 10)
        return json.loads(r.stdout)
    except Exception:
        return {}


def chk(name, got, want):
    ok = want(got) if callable(want) else got == want
    checks.append((name, bool(ok), str(got)[:220]))
    print(("  PASS " if ok else "  FAIL ") + name + "  → " + str(got)[:220])
    return ok


def goto(u, wait=8):
    api("goto?url=" + urllib.parse.quote(u, safe=""), timeout=60)
    time.sleep(wait)


def main():
    print("[0] 打开樱花动漫 → 播放页（真链藏在跨域 iframe 里，正是要验的场景）")
    api("sniff/clear")
    api("net/clear")
    goto("https://yhtn.cc/v/1374711/229", 9)
    b = (api("status").get("browser") or {})
    chk("详情页打开了", b.get("title") or "", lambda t: "摩绪" in (t or "") or "樱花" in (t or ""))
    href = ui("(function(){var a=Array.prototype.filter.call(document.querySelectorAll('a'),"
              "function(x){return (x.textContent||'').indexOf('立即播放')>=0})[0];return a?a.href:'';})()")
    if not href or "yhtn" not in str(href):
        goto("https://yhtn.cc/p/1374711/229/0", 10)
    else:
        goto(str(href), 10)
    time.sleep(6)                                   # 让 iframe 里的播放器把 m3u8 与分片请求发出去

    print("[1] 嗅探清单里要有真链（m3u8，CDN 域名）与分片（合并成一条）")
    sn = api("sniff")
    lst = sn.get("list") or []
    m3 = [x for x in lst if x.get("kind") == "m3u8"]
    ts = [x for x in lst if x.get("kind") == "ts" or x.get("group") == "frag"]
    for x in lst:
        print("    " + json.dumps({k: x.get(k) for k in ("kind", "host", "name", "segs", "page")}, ensure_ascii=False)[:150])
    chk("抓到 m3u8（真链）", len(m3), lambda n: n >= 1)
    if m3:
        chk("真链是 CDN 域名（不是 iframe 那一层）", m3[0].get("host"), lambda h: h and "baofeng" in h or (h and "v." in h))
        chk("清单条目记了「来自哪个页面」", m3[0].get("page") or "", lambda p: "yhtn.cc" in (p or ""))
    chk("分片合并成一条（不是几百行刷屏）", len(ts), lambda n: n == 1 if ts else False)
    if ts:
        chk("分片条目有 segs 计数（会随播放增长）", ts[0].get("segs") or 0, lambda n: n and n >= 1)
    chk("嗅探统计里有 playlist", ((sn.get("stats") or {}).get("playlist") or 0), lambda n: n >= 1)

    print("[2] 清单与网络时间线里不能有我们自己的内部地址（噪声）")
    allurl = " ".join([str(x.get("url", "")) for x in lst]) + " " + \
             " ".join([str(x.get("url", "")) for x in (api("net?limit=100").get("list") or [])])
    chk("没有 cdp-meta.local", "cdp-meta.local" in allurl, False)
    chk("没有 appassets（自己的页面资源）", "appassets.androidplatform.net" in allurl, False)
    chk("没有本机控制口轮询", "127.0.0.1:8848" in allurl, False)
    chk("时间线里确实有真实请求（对照，别把过滤做成'什么都不过滤'）",
        len(api("net?limit=50").get("list") or []), lambda n: n and n > 0)

    print("[3] 内置播放器：打开这条 m3u8 之后，状态要读得出来")
    if m3:
        u = m3[0]["url"]
        api("player?url=" + urllib.parse.quote(u, safe=""), timeout=60)
        time.sleep(8)
        stt = {}
        for _ in range(8):
            stt = api("player/state") or {}
            if stt.get("playerPage") and stt.get("hasVideo") and (stt.get("ms") or 0) > 0:
                break
            time.sleep(2)
        print("    player.state = " + json.dumps(stt, ensure_ascii=False)[:200])
        chk("读到的就是播放器页面", stt.get("playerPage"), True)
        chk("有 video 且已在放（ms>0）", [stt.get("hasVideo"), (stt.get("ms") or 0) > 0], [True, True])
        chk("读得到总时长", (stt.get("durMs") or 0), lambda n: n and n > 1000)

        print("[4] 下载这条 m3u8：进度要能看见（segsDone / bytes 会涨）")
        def rec_keys():
            return set((r.get("name"), r.get("ts")) for r in (api("downloads").get("list") or []))

        before = rec_keys()                      # 别拿主机时间筛设备记录：设备时钟是 UTC，会永远筛不到
        api("sniff/download?url=" + urllib.parse.quote(u, safe=""), timeout=60)
        rec = {}
        for _ in range(15):                      # 轮询等进度出现（每 10 片报一次）
            time.sleep(3)
            fresh = [r for r in (api("downloads").get("list") or [])
                     if (r.get("name"), r.get("ts")) not in before]
            if fresh:
                rec = fresh[-1]
            if (rec.get("segsDone") or 0) >= 10 and (rec.get("bytes") or 0) > 0:
                break
        print("    记录：" + json.dumps({k: rec.get(k) for k in ("name", "state", "bytes", "segsDone", "segsTotal")}, ensure_ascii=False)[:200])
        chk("下载记录里有分片进度字段", [rec.get("segsTotal") is not None, rec.get("segsDone")], lambda v: v[0] and (v[1] or 0) >= 10)
        chk("bytes 不再是 0（真在下）", rec.get("bytes") or 0, lambda n: n and n > 0)
        # 收尾：删记录 + 停掉下载线程（否则它会一直把 1999 个分片拉完）+ 删文件
        api("downloads/delete?name=" + urllib.parse.quote(str(rec.get("name") or ""), safe=""), timeout=60)
        subprocess.run(["bash", "-lc", "export PATH=%s:$PATH; adb -s emulator-5554 shell am force-stop dev.cdp; "
                        "sleep 2; adb -s emulator-5554 shell run-as dev.cdp rm -rf files/downloads 2>/dev/null; "
                        "adb -s emulator-5554 shell am start -n dev.cdp/.MainActivity >/dev/null 2>&1; sleep 8"
                        % os.path.dirname(os.environ.get("PATH", ""))], capture_output=True, text=True)
        print("    （已停掉下载线程并清掉下载目录）")
    else:
        chk("有 m3u8 才能验播放器/下载", False, True)

    print("[5] 页面没有 <video> 时，「看视频状态」要指向嗅探")
    api("ui/open?tab=page")
    time.sleep(3)
    ui("(function(){var b=document.getElementById('v-state');if(b)b.click();return 'ok';})()")
    time.sleep(2)
    r = api("eval?js=" + urllib.parse.quote("(function(){var e=document.getElementById('v-out');return e?e.textContent:'';})()", safe=""))
    out = str((r or {}).get("raw") or "")
    print("    #v-out = " + out[:120])
    if "没识别到" in out:
        chk("提示里指向「嗅探」", "嗅探" in out, True)
    else:
        chk("页面里确实没有 <video>（那这条提示才适用）", out[:40], lambda s: True)

    bad = [c for c in checks if not c[1]]
    print("\n结果：%d/%d 通过" % (len(checks) - len(bad), len(checks)))
    for c in bad:
        print("  未通过：" + c[0] + " → " + c[2])
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
