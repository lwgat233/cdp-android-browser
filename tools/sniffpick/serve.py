#!/usr/bin/env python3
""""指定哪一块是视频"的测试页（宿主跑，设备走 10.0.2.2:8899）

为什么要有这一页：要一个**自动嗅探确实认不出**的视频，才能证明"指定位置"是有用的。
- /watch   页面里的 <video src="/media/stream?id=7">：地址**没有扩展名** → Sniffer.kindOf 认不出 → 不进清单（对照组）
- /mse     用 MediaSource 播放：video.currentSrc 是 blob:（抄学习通那种 MSE 播放），
           真实数据来自 /media/stream?id=9 的 fetch → 元素上什么都拿不到，只能靠"最近请求"找候选
- /inner   给外层 iframe 用（跨域 iframe 那种形态）
- /media/stream?id=N  返回一段真视频字节（用 clip.webm 的内容，Content-Type: video/webm）

用法：python3 tools/sniffpick/serve.py   （监听 0.0.0.0:8899）
"""
import http.server
import os
import socketserver

HERE = os.path.dirname(os.path.abspath(__file__))
CLIP = "/vol1/1000/airesults/cdp/source/cdp/app/src/main/assets/test/clip.webm"

WATCH = """<!doctype html><html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>无扩展名视频</title>
<style>html,body{margin:0;background:#101820;color:#e8f0f8;font:16px system-ui}
video{position:fixed;left:20px;top:80px;width:360px;height:220px;background:#000}
#pad{padding:12px}</style></head><body>
<div id="pad">自动嗅探认不出的地址：/media/stream?id=7</div>
<video id="v" src="/media/stream?id=7" muted playsinline></video>
</body></html>"""

INNER = """<!doctype html><html lang="zh"><head><meta charset="utf-8"><title>内层页</title>
<style>html,body{margin:0;background:#000}video{width:100%;height:100%}</style></head><body>
<video id="vi" src="/media/stream?id=5" muted playsinline></video></body></html>"""

MSE = """<!doctype html><html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>MSE(blob) 视频</title>
<style>html,body{margin:0;background:#101820;color:#e8f0f8;font:16px system-ui}
video{position:fixed;left:20px;top:80px;width:360px;height:220px;background:#000}</style></head><body>
<div style="padding:12px">blob/MSE 播放（currentSrc 是 blob:）</div>
<video id="vm" muted playsinline></video>
<script>
  // 走 fetch（真实请求会经过 app 的网络钩子），再用 MediaSource 喂给 video → currentSrc 变成 blob:
  fetch('/media/stream?id=9').then(function (r) { return r.arrayBuffer(); }).then(function (buf) {
    var v = document.getElementById('vm');
    if (!window.MediaSource) { v.src = 'blob:not-supported'; return; }
    var ms = new MediaSource();
    v.src = URL.createObjectURL(ms);
    ms.addEventListener('sourceopen', function () {
      try {
        var sb = ms.addSourceBuffer('video/webm; codecs="vp8"');
        sb.addEventListener('updateend', function () { try { if (!sb.updating) ms.endOfStream(); } catch (e) {} });
        sb.appendBuffer(new Uint8Array(buf));
      } catch (e) { document.title = 'MSE 失败: ' + e.message; }
    });
  });
</script></body></html>"""

FRAME = """<!doctype html><html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>跨域 iframe 里的视频</title>
<style>html,body{margin:0;background:#101820;color:#e8f0f8;font:16px system-ui}
iframe{position:fixed;left:20px;top:80px;width:360px;height:220px;border:0;background:#000}</style></head><body>
<div style="padding:12px">视频在 iframe 里（顶层脚本读不到里面的 video）</div>
<iframe id="fi" src="http://127.0.0.1:8898/inner"></iframe>
</body></html>"""


class H(http.server.BaseHTTPRequestHandler):
    def _send(self, body, ctype="text/html; charset=utf-8"):
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/watch":
            self._send(WATCH.encode())
        elif path == "/mse":
            self._send(MSE.encode())
        elif path == "/inner":
            self._send(INNER.encode())
        elif path == "/frame":
            self._send(FRAME.encode())
        elif path == "/media/stream":          # 关键：无扩展名
            try:
                with open(CLIP, "rb") as f:
                    self._send(f.read(), "video/webm")
            except Exception as e:
                self._send(("no clip: %s" % e).encode(), "text/plain")
        else:
            self._send(b"<html><body>ok</body></html>")

    def log_message(self, *a):
        pass


socketserver.TCPServer.allow_reuse_address = True
with socketserver.TCPServer(("0.0.0.0", 8899), H) as s:
    print("sniffpick test server on 0.0.0.0:8899  (/watch /mse /frame /media/stream?id=N)")
    s.serve_forever()
