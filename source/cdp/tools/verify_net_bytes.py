#!/usr/bin/env python3
"""网络统计（字节）验收：上行恒 0 的修复 + "没开代理就没字节"要如实标出来

用户原话（2026-09-22）：「继续，网络统计问题等等的」

本轮实测出来的两个真问题：
  ① **上行恒为 0**：请求头是直接写出去的、没进计数（ProxyRelay 只数了 tunnel/body）→ 修：请求头计入上行，
     并把上行那条 pipe 的 join 从 500ms 放宽到 800ms（keep-alive 时计数还没读完）；
  ② **没挂代理时字节恒为 0**（口径限制：页面内的流量 WebView 不暴露，只能数应用自己中继搬运过的）→
     修：`net.list` 回包带上 `relay`，界面那一行如实标「（没开代理·无字节）/（当前没开代理）」。

判据（读回来的事实）：
  [1] 关掉自代理 → `net.list` relay=false，字节 0，网络面板那行含「没开代理」
  [2] 打开自代理 → relay=true、`proxyPort` > 0
  [3] 经中继加载一个已知大小的资源 → 下行 > 0
  [4] **上行 > 0**（这就是本轮修的；修之前恒为 0）
  [5] 按站点统计里出现该主机名
  [6] CONNECT（HTTPS 隧道）那条路也有上行（用一个"只会说 HTTP"的端口造隧道，TLS 失败也算走通）

需要：App 在跑 + tools/sniffpick/serve.py（宿主 8899）。用法：python3 tools/verify_net_bytes.py
"""
import json
import os
import subprocess
import sys
import time
import urllib.parse
import urllib.request

HTTP = "http://127.0.0.1:8848"
ADB = "/home/lwgat/tools/android-sdk/platform-tools/adb"
S = "http://10.0.2.2:8899"
PASS = 0
FAIL = 0


def ok(m):
    global PASS
    PASS += 1
    print("  PASS " + m)


def bad(m):
    global FAIL
    FAIL += 1
    print("  FAIL " + m)


def api(path, timeout=60):
    with urllib.request.urlopen(HTTP + path, timeout=timeout) as r:
        return json.loads(r.read().decode())


def ui(js, timeout=40):
    r = subprocess.run(["node", "tools/uiprobe.mjs", "--timeout", "12000", "--match", "ui/index.html", js],
                       capture_output=True, text=True, cwd="/vol1/1000/airesults/cdp/source/cdp",
                       timeout=timeout, env=dict(os.environ, PATH="/home/lwgat/.local/bin:" + os.environ.get("PATH", "")))
    out = [l for l in (r.stdout or "").strip().splitlines() if l.strip()]
    return out[-1] if out else ""


def ensure_forward():
    pid = subprocess.run([ADB, "shell", "pidof", "dev.cdp"], capture_output=True, text=True).stdout.strip()
    if pid:
        subprocess.run([ADB, "forward", "tcp:9222", "localabstract:webview_devtools_remote_" + pid],
                       capture_output=True, text=True)
    subprocess.run([ADB, "forward", "tcp:8848", "tcp:8848"], capture_output=True, text=True)


def net():
    return api("/api/net")


def stats():
    return net().get("stats") or {}


def load(url):
    api("/api/nav/open?url=" + urllib.parse.quote(url))
    time.sleep(4)


print("== 0. 环境 ==")
for _ in range(30):
    try:
        if api("/api/status").get("ok"):
            break
    except Exception:
        pass
    time.sleep(2)
ensure_forward()
try:
    urllib.request.urlopen("http://127.0.0.1:8899/watch", timeout=8).read(32)
except Exception as e:
    print("测试页服务没起（先跑 tools/sniffpick/serve.py）：%s" % e)
    sys.exit(2)
print("   ok")

print("== 1. 关掉自代理：字节应为 0，并且界面如实标出来 ==")
api("/api/settings/set?selfProxy=0")
time.sleep(3)
api("/api/net/clear")
n = net()
s = stats()
if not n.get("relay") and int(s.get("bytesDown") or 0) == 0 and int(s.get("bytesUp") or 0) == 0:
    ok("relay=false、字节 0（没开代理就是这个口径）")
else:
    bad("relay=%s bytesDown=%s bytesUp=%s（期望 false / 0 / 0）" % (n.get("relay"), s.get("bytesDown"), s.get("bytesUp")))
api("/api/ui/open?tab=net")
time.sleep(3)
line = ui("(function(){var e=document.getElementById('n-state');return e?e.textContent:'-';})()")
if "没开代理" in line:
    ok("网络面板如实标注：%s" % line[:80])
else:
    bad("面板那行没有如实标注：%r" % line[:80])

print("== 2. 打开自代理：relay=true、端口 > 0 ==")
api("/api/settings/set?selfProxy=1")
time.sleep(3)
api("/api/net/clear")
n = net()
if n.get("relay") and int(n.get("proxyPort") or 0) > 0:
    ok("relay=true、中继端口 %s" % n.get("proxyPort"))
else:
    bad("relay=%s port=%s" % (n.get("relay"), n.get("proxyPort")))

print("== 3/4. 经中继加载已知资源：下行 > 0，**上行也要 > 0** ==")
for _ in range(2):
    load(S + "/media/stream?id=7")
s = stats()
d, u = int(s.get("bytesDown") or 0), int(s.get("bytesUp") or 0)
if d > 0:
    ok("下行有数：%d 字节" % d)
else:
    bad("下行还是 0（中继没在数？）")
if u > 0:
    ok("**上行有数：%d 字节**（修之前恒为 0）" % u)
else:
    bad("上行还是 0 —— 请求头的计数没生效")

print("== 5. 按站点统计（在 /api/net/stats 里，不在 net.list） ==")
ns = api("/api/net/stats?range=day")
hosts = ns.get("byHost") or ns.get("slices") or ns.get("bars") or []
names = [x.get("name") for x in hosts if isinstance(x, dict)]
if "10.0.2.2" in names:
    ok("按站点里有 10.0.2.2：%s" % json.dumps(hosts[:3], ensure_ascii=False))
else:
    bad("按站点里没有 10.0.2.2：%s" % json.dumps(names[:6], ensure_ascii=False))

print("== 6. CONNECT（HTTPS 隧道）也有上行 ==")
api("/api/net/clear")
load("https://10.0.2.2:8899/watch")     # TLS 会失败，但 CONNECT 隧道走过 → 上行该有数
s = stats()
u = int(s.get("bytesUp") or 0)
if u > 0:
    ok("隧道那条路的上行也有数：%d 字节" % u)
else:
    bad("CONNECT 路径上行还是 0")

print("\n结果：%d PASS / %d FAIL" % (PASS, FAIL))
sys.exit(0 if FAIL == 0 else 1)
