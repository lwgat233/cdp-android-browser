#!/usr/bin/env python3
"""多站点资源嗅探实测（广度验证）。

对每个站点：清空嗅探清单 → 用 App 自己的浏览器打开 → 等它自己加载/取片 →
读回清单（类型 / 归类 / 来源页）→ 再跑一次"从页面里再找一遍" → 最后真的下载一条音频比字节。

判据都是读回来的事实（清单条目 / 归类计数 / 下载字节），不是"看起来抓到了"。
离线两个靶子（同源/控制口自带）是为了**不依赖外网也能复现**：
  · HLS 分片页：像 hls.js 那样把分片取一遍 → 验"同一路分片合并成一条 ×N"
  · 音频页：真的取一条 MP3 → 验音频嗅探 + 下载字节对得上
"""
import json
import subprocess
import sys
import time

ROOT = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
AUDIO_BYTES = 198658          # assets/test/sample.mp3 的实际字节数（对账用）

SITES = [
    ("离线 · HLS 分片页（像 hls.js 那样取片）", "http://127.0.0.1:8848/api/_test/hls/page.html", 12),
    ("离线 · 音频页（一条真 MP3）", "https://appassets.androidplatform.net/test/audio.html", 8),
    ("离线 · 功能测试靶", "https://appassets.androidplatform.net/test/harness.html", 8),
    ("B 站（视频页）", "https://www.bilibili.com/video/BV1GJ411x7h7", 20),
    ("公开 HLS 直链（境外）", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", 12),
]


def api(path, timeout=90):
    r = subprocess.run(["curl", "-s", "--max-time", str(timeout), HTTP + path],
                       capture_output=True, text=True, timeout=timeout + 10)
    try:
        return json.loads(r.stdout)
    except Exception:
        return {"ok": False, "raw": (r.stdout or "")[:200], "error": r.stderr[:200]}


def one(name, url, wait):
    out = {"site": name, "url": url}
    api("/api/sniff/clear")
    api("/api/goto?url=" + url)
    for _ in range(wait):
        time.sleep(1)
        if (api("/api/sniff/stats").get("stats") or {}).get("total", 0) > 0:
            break
    time.sleep(2)
    st1 = api("/api/sniff/stats").get("stats") or {}
    scan = api("/api/sniff/scan")
    lst = api("/api/sniff")
    items = lst.get("list") or []
    out["stats"] = lst.get("stats") or st1
    out["scan"] = {"added": scan.get("added"), "scanned": scan.get("scanned"), "mse": scan.get("mse")}
    out["items"] = [{"kind": i.get("kind"), "group": i.get("group"), "host": i.get("host"),
                     "name": (i.get("name") or "")[:50], "segs": i.get("segs"), "page": (i.get("page") or "")[:60]}
                    for i in items[:10]]
    return out


def main():
    print("== 多站点嗅探实测 ==")
    results = []
    for name, url, wait in SITES:
        r = one(name, url, wait)
        results.append(r)
        st = r["stats"]
        print("\n[%s]\n  %s" % (name, url))
        print("  归类：播放列表 %s / 视频 %s / 音频 %s / 分片 %s / 字幕 %s（共 %s）"
              % (st.get("playlist"), st.get("video"), st.get("audio"), st.get("frag"), st.get("sub"), st.get("total")))
        print("  从页面里再扫：新增 %s（扫到 %s 条，MSE=%s）" % (r["scan"]["added"], r["scan"]["scanned"], r["scan"]["mse"]))
        for i in r["items"][:6]:
            print("    - [%s/%s] %s %s%s" % (i["kind"], i["group"], i["host"], i["name"],
                                             (" 分片×" + str(i["segs"])) if i.get("segs") else ""))
        if not st.get("total"):
            print("    （0 条：环境不通时如实记 0，不编造）")

    print("\n== 音频下载对账（离线靶子，字节要相等） ==")
    api("/api/sniff/clear")
    api("/api/goto?url=https://appassets.androidplatform.net/test/audio.html")
    time.sleep(6)
    # 注意：下载要用**真 http** 地址。App 自己的虚拟源（appassets）只活在 WebView 的请求回调里，
    # 外部下载器解析不了那个主机名（实测 UnknownHostException），所以控制口另开了一条离线音频源。
    audio_url = HTTP + "/api/_test/audio/sample.mp3"
    api("/api/download?url=" + audio_url + "&name=probe-audio.mp3")
    got = None
    for _ in range(20):
        time.sleep(2)
        recs = api("/api/downloads").get("list") or []
        hit = [x for x in recs if "probe-audio" in (x.get("name") or "")]
        if hit and "完成" in (hit[0].get("state") or ""):
            got = hit[0]
            break
    sniff = api("/api/sniff")
    print("  嗅探清单：%s" % json.dumps(sniff.get("stats") or {}, ensure_ascii=False))
    print("  下载记录：%s" % json.dumps(got, ensure_ascii=False))
    ok = bool(got) and int(got.get("bytes") or 0) == AUDIO_BYTES
    print("  字节对账：%s（期望 %d，实际 %s）" % ("通过" if ok else "没对上", AUDIO_BYTES, (got or {}).get("bytes")))
    results.append({"audio_download": got, "expect_bytes": AUDIO_BYTES, "ok": ok, "sniff": sniff.get("stats")})

    print("\n== 外网异常时的现场（如实记录，不当成 App 缺陷） ==")
    log = api("/api/log?n=200")
    for line in (log.get("lines") or [])[-200:]:
        if any(k in line for k in ("下载失败", "SocketTimeout", "UnknownHost")):
            print("  " + line[:160])
    open("/tmp/sniff_sites.json", "w").write(json.dumps(results, ensure_ascii=False, indent=1))
    print("\n明细已写 /tmp/sniff_sites.json")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
