#!/usr/bin/env python3
"""B-82：点下载行的 ✕ → 弹小窗（信息「是否删除本地」+ 勾选框 + 是/否）。

判据：
 ① 点 ✕ 后小窗真的开（DOM 里有 #cdp-modal），标题是「删除「名字」？」
 ② 小窗里有一个 **checkbox**，标签写着「是否删除本地文件」
 ③ 下面两个键：**是**（主键）与 **否**
 ④ 勾上「是」→ 文件和记录都没了（设备上 /sdcard/Download/cdp/<名字> 不存在）
 ⑤ 不勾直接「是」→ 记录没了、**文件还在**（keepFile=true）
 ⑥ 点「否」→ 什么都没删（记录还在）
用法：python3 tools/verify_dl_delete_sheet.py
"""
import json
import os
import socket
import subprocess
import sys
import threading
import time

SRC = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
ADB = "/home/lwgat/tools/android-sdk/platform-tools/adb"
ENV = dict(os.environ, PATH="/home/lwgat/.local/bin:/home/lwgat/tools/android-sdk/platform-tools:" + os.environ.get("PATH", ""))
PORT = 9902
P = F = 0
FAILS = []


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


def ui(expr, t=70):
    r = subprocess.run(["node", "tools/uiprobe.mjs", "--timeout", "15000", "--match", "ui/index.html", expr],
                       capture_output=True, text=True, cwd=SRC, env=ENV, timeout=t)
    out = [l for l in (r.stdout or "").strip().splitlines() if l.strip()]
    return out[-1] if out else None


from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Tiny(BaseHTTPRequestHandler):
    """极小的本地源，够下一次「小文件」用。"""
    protocol_version = "HTTP/1.0"

    def log_message(self, *a):
        pass

    def do_GET(self):
        body = b"z" * 4096
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


srv = ThreadingHTTPServer(("0.0.0.0", PORT), Tiny)
threading.Thread(target=srv.serve_forever, daemon=True).start()
print("本地小源：0.0.0.0:%d（模拟器用 10.0.2.2:%d）" % (PORT, PORT))


def start_dl(name):
    for d in (api("/api/downloads") or {}).get("list") or []:
        if d.get("name") == name:
            api("/api/downloads/delete?name=" + name)
    u = "http://10.0.2.2:%d/f.bin" % PORT
    api("/api/download?url=" + u.replace(":", "%3A").replace("/", "%2F") + "&name=" + name)
    for _ in range(20):
        time.sleep(1)
        d = next((x for x in (api("/api/downloads") or {}).get("list") or [] if x.get("name") == name), None)
        if d and d.get("state") == "完成":
            return d
    return {}


def file_exists(name):
    return sh("%s -s emulator-5554 shell 'ls /sdcard/Download/cdp/%s 2>/dev/null | wc -l'" % (ADB, name)).strip()


print("[1] 下一个小文件，然后在界面上点它的 ✕")
d1 = start_dl("del-keep.bin")
chk("文件已下到系统目录", file_exists("del-keep.bin"), "1")
api("/api/ui/open?tab=downloads")
time.sleep(2)
ui("(function(){var b=document.getElementById('d-reload');if(b)b.click();return 'x'})()")
time.sleep(3)
CLICK_X = ("(function(){var rows=document.querySelectorAll('#d-list .row-item');"
           "for(var i=0;i<rows.length;i++){if((rows[i].textContent||'').indexOf('%s')>=0){"
           "var b=rows[i].querySelector('button[data-row-keep]');"
           "if(!b)return 'no-x-in-row';b.click();return 'clicked';}}return 'not-found'})()")
opened = ui(CLICK_X % "del-keep")
chk("找到这一行的 ✕ 并点开", opened, "clicked")
time.sleep(2)
chk("小窗开了", ui("String(!!document.getElementById('cdp-modal'))"), "true")
chk("标题写清删哪条", ui("(function(){var t=document.querySelector('#cdp-modal b');return t?(t.textContent||'').trim():''})()"),
    lambda s: "del-keep.bin" in str(s))
chk("有勾选框", ui("String(!!document.querySelector('#cdp-modal input[type=checkbox]'))"), "true")
chk("勾选框标签是「是否删除本地文件」",
    ui("(function(){var boxes=document.querySelectorAll('#cdp-modal .kv');for(var i=0;i<boxes.length;i++){"
       "if(boxes[i].querySelector('input[type=checkbox]'))return (boxes[i].querySelector('.kv-k')||{}).textContent||''}return ''})()"),
    lambda s: "删除本地" in str(s))
keys = ui("JSON.stringify([].map.call(document.querySelectorAll('#cdp-modal button'),function(b){return (b.textContent||'').trim()}))")
print("     小窗里的键：", keys)
chk("下面有「是」和「否」", keys, lambda s: "是" in str(s) and "否" in str(s))

print("[2] 不勾 → 点是：记录删掉、**文件留着**")
ui("(function(){var b=document.getElementById('cdp-modal-ok');if(b)b.click();return 'x'})()")
time.sleep(3)
chk("记录没了", [d for d in (api("/api/downloads") or {}).get("list") or [] if d.get("name") == "del-keep.bin"], lambda l: len(l) == 0)
chk("文件还在（没勾就不删本地）", file_exists("del-keep.bin"), "1")

print("[3] 再下一个，勾上 → 点是：文件也删掉")
d2 = start_dl("del-gone.bin")
chk("第二个文件在", file_exists("del-gone.bin"), "1")
api("/api/ui/open?tab=downloads")
time.sleep(2)
ui("(function(){var b=document.getElementById('d-reload');if(b)b.click();return 'x'})()")
time.sleep(3)
ui(CLICK_X % "del-gone")
time.sleep(2)
ui("(function(){var c=document.querySelector('#cdp-modal input[type=checkbox]');if(c){c.checked=true;c.dispatchEvent(new Event('change',{bubbles:true}))}return 'x'})()")
ui("(function(){var b=document.getElementById('cdp-modal-ok');if(b)b.click();return 'x'})()")
time.sleep(3)
chk("记录没了", [d for d in (api("/api/downloads") or {}).get("list") or [] if d.get("name") == "del-gone.bin"], lambda l: len(l) == 0)
chk("文件也被删了", file_exists("del-gone.bin"), "0")

print("[4] 反例：点「否」→ 什么都没删")
d3 = start_dl("del-no.bin")
api("/api/ui/open?tab=downloads")
time.sleep(2)
ui("(function(){var b=document.getElementById('d-reload');if(b)b.click();return 'x'})()")
time.sleep(3)
ui(CLICK_X % "del-no")
time.sleep(2)
ui("(function(){var b=document.getElementById('cdp-modal-no');if(b)b.click();return 'x'})()")
time.sleep(3)
chk("记录还在", [d for d in (api("/api/downloads") or {}).get("list") or [] if d.get("name") == "del-no.bin"], lambda l: len(l) == 1)
chk("文件还在", file_exists("del-no.bin"), "1")
api("/api/downloads/delete?name=del-no.bin")

srv.shutdown()
print("\n结果：%d/%d 通过" % (P, P + F))
if FAILS:
    print("  未通过：" + "；".join(FAILS))
sys.exit(1 if F else 0)
