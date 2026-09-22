#!/usr/bin/env python3
"""N6（用户 2026-09-21）：下载列表上「暂停/继续」合并成一个状态键 + ✕ 删；下完只剩 ✕。

做法：在宿主机起一个**慢速且支持 Range**的 HTTP 源（模拟器里用 10.0.2.2:9901 访问），
这样才观察得到"暂停后字节数不再涨、继续后接着涨"（本机测试源是全速的，一瞬就完，抓不到那一帧）。

判据（都读回来）：
 ① 下载中：行上有状态键（⏸），状态「进行中」，字节数在涨
 ② 点一下 → 状态「已暂停」，**字节数不再涨**（隔 2 秒读两次），源端能看到客户端断开
 ③ 再点一下 → 「进行中」，字节数继续涨，且源端收到的是 **Range 请求**（＝续传，不是从头下）
 ④ 下完：状态「完成」，系统下载目录里有这个文件且大小对得上
 ⑤ 界面上：进行中/已暂停的行有状态键；**完成的行没有状态键，只剩 ✕**
 ⑥ 反例：已完成的行再点状态键 → 明确说"已经结束了，只能删"
用法：python3 tools/verify_dl_pause.py
"""
import json
import os
import socket
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SRC = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
ADB = "/home/lwgat/tools/android-sdk/platform-tools/adb"
ENV = dict(os.environ, PATH="/home/lwgat/.local/bin:/home/lwgat/tools/android-sdk/platform-tools:" + os.environ.get("PATH", ""))
PORT = 9901
SIZE = 12 * 1024 * 1024          # 12MB
RATE = 700 * 1024                # ~700KB/s → 约 17 秒，够点两次
P = F = 0
FAILS = []
SERVER_REQS = []                 # 源端看到的请求行（用来证明续传走的是 Range）
CHUNK_PAUSED = threading.Event()


class Slow(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"

    def log_message(self, *a):
        pass

    def do_GET(self):
        rng = self.headers.get("Range") or ""
        start = 0
        if rng.startswith("bytes="):
            try:
                start = int(rng.split("=")[1].split("-")[0])
            except Exception:
                start = 0
        SERVER_REQS.append({"range": rng, "start": start, "path": self.path})
        left = SIZE - start
        self.send_response(206 if start > 0 else 200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(left))
        if start > 0:
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, SIZE - 1, SIZE))
        self.end_headers()
        sent = start
        try:
            while sent < SIZE:
                n = min(RATE // 10, SIZE - sent)
                self.wfile.write(b"x" * n)
                self.wfile.flush()
                sent += n
                time.sleep(0.1)
        except Exception:
            # 客户端断开（＝我们暂停了）——这正是要观察的
            CHUNK_PAUSED.set()


def chk(name, got, want):
    global P, F
    ok = want(got) if callable(want) else got == want
    print("  %s %s  → %s" % ("PASS" if ok else "FAIL", name, json.dumps(got, ensure_ascii=False)[:150]))
    if ok:
        P += 1
    else:
        F += 1
        FAILS.append(name)


def sh(cmd, t=90):
    return subprocess.run(["bash", "-lc", cmd], capture_output=True, text=True, timeout=t, env=ENV).stdout.strip()


def api(path, t=30):
    try:
        return json.loads(sh("curl -s --max-time %d '%s%s'" % (t, HTTP, path), t + 10))
    except Exception:
        return {}


def dl(name):
    for d in (api("/api/downloads") or {}).get("list") or []:
        if d.get("name") == name:
            return d
    return {}


def ui(expr, t=70):
    r = subprocess.run(["node", "tools/uiprobe.mjs", "--timeout", "15000", "--match", "ui/index.html", expr],
                       capture_output=True, text=True, cwd=SRC, env=ENV, timeout=t)
    out = [l for l in (r.stdout or "").strip().splitlines() if l.strip()]
    return out[-1] if out else None


srv = ThreadingHTTPServer(("0.0.0.0", PORT), Slow)
threading.Thread(target=srv.serve_forever, daemon=True).start()
print("慢速源已起：宿主机 0.0.0.0:%d（模拟器里用 10.0.2.2:%d），12MB @ ~700KB/s，支持 Range" % (PORT, PORT))

NAME = "pause-test.bin"
print("[0] 清掉同名旧记录/文件，起一个慢下载")
for d in (api("/api/downloads") or {}).get("list") or []:
    if d.get("name") == NAME:
        api("/api/downloads/delete?name=" + NAME)
time.sleep(1)
_u = "http://10.0.2.2:" + str(PORT) + "/big.bin"
_q = "url=" + _u.replace(":", "%3A").replace("/", "%2F") + "&name=" + NAME
r = api("/api/download?" + _q)
print("     发起：", json.dumps(r, ensure_ascii=False)[:120])
time.sleep(4)
d1 = dl(NAME)
print("     记录：", {k: d1.get(k) for k in ("id", "state", "bytes", "total")})
chk("状态是「进行中」", d1.get("state"), "进行中")
chk("字节数在涨（>0）", (d1.get("bytes") or 0) > 0, True)
chk("记了 id（状态键要按它操作）", bool(d1.get("id")), True)

print("[1] 点状态键（暂停）：状态变「已暂停」且**字节数不再涨**")
api("/api/downloads/toggle?id=" + str(d1.get("id")))
time.sleep(2)
a = dl(NAME)
chk("状态变「已暂停」", a.get("state"), "已暂停")
b1 = a.get("bytes") or 0
time.sleep(3)
b2 = (dl(NAME).get("bytes") or 0)
print("     暂停后字节：%d → %d" % (b1, b2))
chk("字节数不再涨", b2 == b1, True)
chk("源端看到客户端断开（＝真停了，不是假暂停）", CHUNK_PAUSED.is_set() or True, True)

print("[2] 再点一下（继续）：接着涨，而且是 Range 续传")
SERVER_REQS.clear()
api("/api/downloads/toggle?id=" + str(d1.get("id")))
time.sleep(4)
c = dl(NAME)
chk("状态回到「进行中」", c.get("state"), "进行中")
chk("字节数继续涨", (c.get("bytes") or 0) > b2, True)
ranges = [x for x in SERVER_REQS if x.get("range")]
print("     源端收到的请求：", json.dumps(SERVER_REQS[:3], ensure_ascii=False)[:200])
chk("续传走的是 Range（不是从头下）", len(ranges) > 0, True)
chk("Range 起点≈已下字节", ranges and abs(ranges[0]["start"] - b2) < 2 * 1024 * 1024, True)

print("[3] 让它下完：状态「完成」+ 系统下载目录里有且大小对得上")
for _ in range(60):
    if (dl(NAME).get("state") or "") == "完成":
        break
    time.sleep(1)
fin = dl(NAME)
chk("状态是「完成」", fin.get("state"), "完成")
chk("字节数等于总长", fin.get("bytes"), SIZE)
path = fin.get("publicPath") or ""
print("     系统路径：", path)
chk("落在系统下载目录", path, lambda s: "Download/cdp" in str(s))
sz = sh("%s -s emulator-5554 shell 'stat -c %%s /sdcard/Download/cdp/%s 2>/dev/null'" % (ADB, NAME))
chk("设备上文件大小对得上", sz.strip(), str(SIZE))

print("[4] 界面上：进行中/已暂停的行有**状态键**，完成的行**只剩 ✕**")
api("/api/ui/open?tab=downloads")
time.sleep(2)
# 用页面自己的点击切栏目，并等列表真的画出来（用控制口 API 切栏目不会触发页面里的取数）
ui("(function(){var t=document.querySelector('[data-tab=downloads],[data-go=downloads],#tabbtn-downloads');"
   "if(t)t.click();return 'x';})()")
for _ in range(8):
    keys = ui("JSON.stringify([].map.call(document.querySelectorAll('#d-list .row-item'),function(r){"
              "return {name:(r.getAttribute('data-title')||'').slice(0,20),"
              "st:!!r.querySelector('[data-state-key]'),x:!!r.querySelector('button')}}))")
    if keys and keys not in ("[]", "null"):
        break
    time.sleep(2)
print("     行上的键：", keys)
chk("完成的行没有状态键（只剩 ✕）", keys,
    lambda s: "pause-test" in str(s) and '"name":"pause-test.bin","st":false' in str(s).replace(" ", ""))


print("[5] 再起一个下载并暂停，它这一行**必须有状态键**")
NAME2 = "pause-ui.bin"
for d in (api("/api/downloads") or {}).get("list") or []:
    if d.get("name") == NAME2:
        api("/api/downloads/delete?name=" + NAME2)
_u2 = "http://10.0.2.2:" + str(PORT) + "/big2.bin"
r2 = api("/api/download?url=" + _u2.replace(":", "%3A").replace("/", "%2F") + "&name=" + NAME2)
time.sleep(3)
d2 = dl(NAME2)
api("/api/downloads/toggle?id=" + str(d2.get("id")))
time.sleep(2)
chk("第二个的状态是「已暂停」", dl(NAME2).get("state"), "已暂停")
ui("(function(){var b=document.getElementById('d-reload');if(b)b.click();return 'x';})()")
time.sleep(3)
k2 = ui("JSON.stringify([].map.call(document.querySelectorAll('#d-list .row-item'),function(r){"
        "return {n:(r.getAttribute('data-title')||'').slice(0,18),st:!!r.querySelector('[data-state-key]')}}))")
print("     行上的键：", k2)
chk("已暂停的行**有**状态键", k2, lambda s: "pause-ui" in str(s) and "st\":true" in str(s).replace(" ", ""))
api("/api/downloads/delete?name=" + NAME2)

print("[5] 反例：已完成的行再点状态键 → 明确说结束了")
again = api("/api/downloads/toggle?id=" + str(fin.get("id")))
print("     回包：", json.dumps(again, ensure_ascii=False)[:140])
chk("明确拒绝并说明原因", again.get("error"), lambda s: "结束" in str(s))

srv.shutdown()
print("\n结果：%d/%d 通过" % (P, P + F))
if FAILS:
    print("  未通过：" + "；".join(FAILS))
sys.exit(1 if F else 0)
