#!/usr/bin/env python3
"""log.md 第 6 条：aria2 等外部下载方式 —— 端到端验收（含一台本地假 aria2）。

做法：脚本自己在宿主机起一台**假 aria2**（JSON-RPC，127.0.0.1:6800），
App 从模拟器里访问 `http://10.0.2.2:6800/jsonrpc`（模拟器眼里的宿主机）。
两边都对账：App 要回 ok+gid；假 aria2 要真的收到 aria2.addUri、token、url、out 名字。
另附反例：把 RPC 指到一个没人听的端口 → 必须明确报"连不上"，不许假装成功。
用法：python3 tools/verify_aria2.py
"""
import json
import os
import re
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SRC = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
ADB = "/home/lwgat/tools/android-sdk/platform-tools/adb"
ENV = dict(os.environ, PATH="/home/lwgat/.local/bin:/home/lwgat/tools/android-sdk/platform-tools:" + os.environ.get("PATH", ""))
P = F = 0
FAILS = []
SEEN = []          # 假 aria2 收到的请求（对账用）


def chk(name, got, want):
    global P, F
    ok = want(got) if callable(want) else got == want
    print("  %s %s  → %s" % ("PASS" if ok else "FAIL", name, json.dumps(got, ensure_ascii=False)[:160]))
    if ok:
        P += 1
    else:
        F += 1
        FAILS.append(name)


def sh(cmd, t=90):
    return subprocess.run(["bash", "-lc", cmd], capture_output=True, text=True, timeout=t, env=ENV).stdout.strip()


def api(path, t=25):
    # 注意：URL **必须加引号**！不加的话 `&` 会被 bash 当成后台符，从第二个参数起全都没发出去
    # （我自己在这上面栽了一轮：以为"设置丢参数"，其实是参数压根没到服务端）。
    try:
        return json.loads(sh("curl -s --max-time %d '%s%s'" % (t, HTTP, path), t + 8))
    except Exception:
        return {}


class FakeAria2(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_POST(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n).decode("utf-8", "replace")
        try:
            req = json.loads(raw)
        except Exception:
            req = {"raw": raw}
        SEEN.append(req)
        method = req.get("method", "")
        if method == "aria2.addUri":
            out = {"jsonrpc": "2.0", "id": req.get("id", "cdp"), "result": "gid-fake-0001"}
        elif method == "aria2.tellStatus":
            out = {"jsonrpc": "2.0", "id": req.get("id", "cdp"),
                   "result": {"status": "active", "totalLength": "1234", "completedLength": "600", "downloadSpeed": "99"}}
        else:
            out = {"jsonrpc": "2.0", "id": req.get("id", "cdp"), "result": {"version": "1.36.0-fake"}}
        body = json.dumps(out).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


srv = ThreadingHTTPServer(("127.0.0.1", 6800), FakeAria2)
threading.Thread(target=srv.serve_forever, daemon=True).start()
print("假 aria2 已起在 127.0.0.1:6800（模拟器里用 10.0.2.2:6800 访问）")

print("[0] 环境")
chk("控制口在", api("/api/status").get("ok"), True)

print("[1] 配上 aria2（RPC 指向本地假服务器）+ 下载方式选 aria2")
WANT = {"aria2Rpc": "http://10.0.2.2:6800/jsonrpc", "aria2Token": "s3cret",
        "aria2Dir": "/downloads", "downloadVia": "aria2"}
st = {}
for _ in range(4):        # 写一次、读回来核对；不对就再写（应用刚重启时踩过一次读不到的）
    api("/api/settings/set?aria2Rpc=http%3A%2F%2F10.0.2.2%3A6800%2Fjsonrpc&aria2Token=s3cret&aria2Dir=%2Fdownloads&downloadVia=aria2")
    time.sleep(2)
    st = (api("/api/settings").get("settings") or {})
    if all(st.get(k) == v for k, v in WANT.items()):
        break
chk("四个设置都读回来一致", {k: st.get(k) for k in WANT}, WANT)

print("[2] 「试一下」：aria2 通不通（只读调用 aria2.getVersion）")
SEEN.clear()
ver = api("/api/dl/aria2/check")
chk("试连通返回 ok", ver.get("ok"), True)
chk("读到了 aria2 版本", ver.get("version"), lambda v: "fake" in str(v))
chk("假 aria2 收到的正是 getVersion", [x.get("method") for x in SEEN], lambda l: "aria2.getVersion" in l)

print("[3] 真提交一个地址：App 拿 gid，假 aria2 那边收到完整参数（两边对账）")
SEEN.clear()
sub = api("/api/dl/aria2?url=http%3A%2F%2F10.0.2.2%3A8848%2Fapi%2F_test%2Fhls%2Fpage.html&name=t.bin")
chk("App 侧 ok", sub.get("ok"), True)
chk("App 拿到了 gid", sub.get("gid"), "gid-fake-0001")
got = SEEN[-1] if SEEN else {}
params = got.get("params") or []
flat = json.dumps(params, ensure_ascii=False)
chk("假 aria2 收到的是 aria2.addUri", got.get("method"), "aria2.addUri")
chk("带上了 token", flat, lambda s: "token:s3cret" in s)
chk("带上了要下的地址", flat, lambda s: "_test/hls/page.html" in s)
chk("带上了文件名 out", flat, lambda s: "t.bin" in s)
chk("带上了目录 dir", flat, lambda s: "/downloads" in s)

print("[4] 走**应用真正的下载入口**（不是那个测试路由）：记录里要能看到「已交给 aria2（gid …）」")
SEEN.clear()
# 控制口里走应用真正下载入口的就是 /api/download（= op download.start，和界面「下载」键同一条路）
dl = api("/api/download?url=http%3A%2F%2F10.0.2.2%3A8848%2Fapi%2F_test%2Fhls%2Fpage.html&name=viaApp.bin")
print("     入口回包：", json.dumps(dl, ensure_ascii=False)[:160])
# 提交是**后台线程**做的 → 轮询等记录回填（不许读一次就下结论）
recs, hit = [], []
for _ in range(10):
    time.sleep(2)
    recs = api("/api/downloads").get("list") or api("/api/downloads").get("items") or []
    hit = [x for x in recs if "已交给 aria2" in str(x.get("state", ""))]
    if hit:
        break
chk("下载列表里有 aria2 那条", len(hit) >= 1, True)
chk("那条记了 gid（用户能看到东西去了哪）", (hit[-1].get("gid") if hit else ""), lambda g: "gid" in str(g))

print("[5] 反例：RPC 指到没人听的端口 → 必须明确报连不上（不许假装成功）")
api("/api/settings/set?aria2Rpc=http%3A%2F%2F10.0.2.2%3A6801%2Fjsonrpc")
time.sleep(2)
bad = api("/api/dl/aria2?url=http%3A%2F%2F10.0.2.2%3A8848%2Fx.bin")
chk("明确失败", bad.get("ok"), False)
chk("并给出了人看得懂的原因", bad.get("error"), lambda s: "连不上" in str(s) or "没接受" in str(s))

print("\n结果：%d/%d 通过" % (P, P + F))
if FAILS:
    print("  未通过：" + "；".join(FAILS))
srv.shutdown()
sys.exit(1 if F else 0)
