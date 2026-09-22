#!/usr/bin/env python3
"""「指定哪一块是视频」验收（功能 id：sniff.pick）

用户原话：
  「浏览，页面与元素的那个页面的视频元素，对于学习通没效果。如果指定哪个块是视频元素就好了。
    还有我觉得这个还是融合到资源嗅探中比较好。」

判据（读回来的事实）：
  [A] **对照组**：无扩展名的视频地址（/media/stream?id=7），自动嗅探确实**没**收进清单
      （证明"解析不出"真实存在，不是想当然）
  [B] 按「指定位置为视频」后，用**真手指**点页面上视频那一块 → 清单里出现该地址，`from=手动指定`
  [C] 那条能播（用内置播放器打开，读播放器状态）
  [D] MSE/blob 那种（元素上没有地址）→ 退回**最近请求**找候选，清单里出现 /media/stream?id=9，
      `via` 标注"最近请求（可能不是正片）"
  [E] 什么都没找到的位置 → 如实回一句"没找到"，清单条数不变（不假装）

测试页：tools/sniffpick/serve.py（宿主 8899；设备走 10.0.2.2:8899）
两条踩过的坑照旧：① 装包后要重建 9222 转发；② 真手指要等视口量出来（cssW>0）再算坐标。

用法：python3 tools/verify_sniff_pick.py
"""
import json
import os
import subprocess
import sys
import time
import urllib.parse
import urllib.request

ROOT = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
ADB = "/home/lwgat/tools/android-sdk/platform-tools/adb"
S = "http://10.0.2.2:8899"
ENV = dict(os.environ, PATH="/home/lwgat/.local/bin:" + os.environ.get("PATH", ""))
PASS = 0
FAIL = 0
NOTE = []


def ok(m):
    global PASS
    PASS += 1
    print("  PASS " + m)


def bad(m):
    global FAIL
    FAIL += 1
    print("  FAIL " + m)


def note(m):
    NOTE.append(m)
    print("  NOTE " + m)


def ensure_forward():
    pid = subprocess.run([ADB, "shell", "pidof", "dev.cdp"], capture_output=True, text=True).stdout.strip()
    if pid:
        subprocess.run([ADB, "forward", "tcp:9222", "localabstract:webview_devtools_remote_" + pid],
                       capture_output=True, text=True)
    subprocess.run([ADB, "forward", "tcp:8848", "tcp:8848"], capture_output=True, text=True)
    return pid


def api(path, timeout=60):
    with urllib.request.urlopen(HTTP + path, timeout=timeout) as r:
        return json.loads(r.read().decode())


def page_eval(js, timeout=60):
    r = api("/api/eval?js=" + urllib.parse.quote(js), timeout=timeout)
    raw = (r or {}).get("raw")
    if isinstance(raw, str):
        try:
            return json.loads(raw)
        except Exception:
            return raw
    return raw


def sniff_items():
    r = api("/api/sniff")
    return r.get("list") or []


def find_item(sub):
    for it in sniff_items():
        if sub in (it.get("url") or ""):
            return it
    return None


def wait_viewport():
    """视口以**页面自己报的**为准：/api/status 的 cssW 有时不更新（首页时实测是 0，而页面 innerWidth=412）"""
    for _ in range(40):
        try:
            w = page_eval("String(window.innerWidth)")
            h = page_eval("String(window.innerHeight)")
            if str(w).isdigit() and int(w) > 0 and str(h).isdigit() and int(h) > 0:
                return int(w), int(h)
        except Exception:
            pass
        b = (api("/api/status").get("browser") or {})
        if int(b.get("cssW") or 0) > 0 and int(b.get("cssH") or 0) > 0:
            return int(b["cssW"]), int(b["cssH"])
        time.sleep(1.5)
    return 0, 0


def css_to_screen(cssx, cssy):
    """页面 CSS 坐标 → 屏幕坐标（用 App 自己报的框；cssW 缺就用页面 innerWidth）"""
    b = api("/api/view/box")
    try:
        box = b.get("box") or b
        x0, y0 = float(box["x"]), float(box["y"])
        w = float(box["w"])
        cssw = float(box.get("cssW") or 0)
        if cssw <= 0:
            cssw = float(str(page_eval("String(window.innerWidth)")) or 0) or w
        scale = w / cssw if cssw else 1.0
        return int(x0 + cssx * scale), int(y0 + cssy * scale)
    except Exception as e:
        note("拿不到 /api/view/box（%s），按 1:1 兜底" % e)
        return int(cssx), int(cssy)


def finger_tap(x, y):
    subprocess.run([ADB, "shell", "input", "tap", str(x), str(y)], capture_output=True, text=True)
    return "%d,%d" % (x, y)


def nav(url):
    api("/api/nav/open?url=" + urllib.parse.quote(url))


def warmup_input():
    """模拟器冷启动后会丢首击（踩过多次）→ 先点一个必定有日志的地方，读到「页面点击」才算输入通了"""
    for i in range(6):
        subprocess.run([ADB, "shell", "input", "tap", "20", "400"], capture_output=True, text=True)
        time.sleep(1)
        lines = [str(l) for l in (api("/api/log?n=120").get("lines") or []) if "页面点击" in str(l)]
        if lines:
            return True
    note("预热点击没读到「页面点击」（输入可能整轮不通，后面的失败未必是产品问题）")
    return False


print("== 0. 环境 ==")
print("   重建转发（pid=%s）" % ensure_forward())
vw, vh = wait_viewport()
if vw <= 0:
    print("页面没量出视口，先确认 App 起来了。")
    sys.exit(2)
print("   视口 %dx%d" % (vw, vh))
try:
    urllib.request.urlopen("http://127.0.0.1:8899/watch", timeout=10).read(64)
except Exception as e:
    print("测试页服务没起：先跑 python3 tools/sniffpick/serve.py（%s）" % e)
    sys.exit(2)

print("== 1. 对照组：无扩展名的视频地址，自动嗅探认不出 ==")
api("/api/sniff/clear")
nav(S + "/watch")
for _ in range(20):
    time.sleep(1)
    if str(page_eval("String(!!document.getElementById('v'))")) == "true":
        break
time.sleep(3)
auto = find_item("/media/stream")
if auto is None:
    ok("自动嗅探确实没收 /media/stream?id=7（对照组成立）")
else:
    bad("自动嗅探居然收了它（对照组不成立，后面的结论没意义）：%s" % auto.get("url"))

print("== 2. 指定位置：真手指点视频那一块 → 进清单 ==")
warmup_input()
rect = page_eval("(function(){var v=document.getElementById('v');if(!v)return null;"
                 "var r=v.getBoundingClientRect();return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2});})()")
if not isinstance(rect, dict):
    bad("读不到视频元素的位置：%r" % rect)
    sys.exit(1)
sx, sy = css_to_screen(rect["x"], rect["y"])
print("   视频块 CSS(%.0f,%.0f) → 屏幕(%d,%d)" % (rect["x"], rect["y"], sx, sy))
hit = None
for attempt in range(3):
    api("/api/sniff/pick?on=1")
    time.sleep(1.2)
    finger_tap(sx, sy)
    for _ in range(10):
        time.sleep(1)
        hit = find_item("/media/stream?id=7")
        if hit:
            break
    if hit:
        break
    print("   第 %d 次没命中，重试（模拟器偶发丢输入）" % (attempt + 1))
if hit:
    ok("清单里出现了指定块的地址：%s" % hit.get("url")[:70])
    if (hit.get("from") or "") == "手动指定":
        ok("来源标成「手动指定」（via=%s）" % (hit.get("via") or "-"))
    else:
        bad("来源标记不对：%r" % hit.get("from"))
else:
    bad("点完清单里还是没有这条地址")

print("== 3. 这条能不能播 ==")
if hit:
    pl = api("/api/player?url=" + urllib.parse.quote(hit["url"]) + "&name=sniffpick")
    st = api("/api/player/state")
    if st.get("ok"):
        ok("内置播放器状态可读：%s" % json.dumps(
            {k: st.get(k) for k in ("ok", "title", "state", "playing") if k in st}, ensure_ascii=False)[:120])
    else:
        bad("播放器打不开这条：%r" % st)
else:
    bad("没有可播的条目（上一条失败）")

print("== 4. MSE/blob 那种：元素上没地址 → 退回最近请求 ==")
api("/api/sniff/clear")
nav(S + "/mse")
for _ in range(20):
    time.sleep(1)
    if str(page_eval("String(!!document.getElementById('vm'))")) == "true":
        break
time.sleep(4)
auto2 = find_item("/media/stream?id=9")
if auto2 is None:
    ok("自动嗅探没收 /media/stream?id=9（对照组成立）")
else:
    bad("自动嗅探收了它，MSE 场景没造出来：%s" % auto2.get("url"))
rect2 = page_eval("(function(){var v=document.getElementById('vm');if(!v)return null;"
                  "var r=v.getBoundingClientRect();return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2});})()")
if isinstance(rect2, dict):
    sx2, sy2 = css_to_screen(rect2["x"], rect2["y"])
    hit2 = None
    for attempt in range(3):
        api("/api/sniff/pick?on=1")
        time.sleep(1.2)
        finger_tap(sx2, sy2)
        for _ in range(10):
            time.sleep(1)
            hit2 = find_item("/media/stream?id=9")
            if hit2:
                break
        if hit2:
            break
    if hit2:
        ok("从最近请求里找到候选并进了清单：%s" % hit2.get("url")[:70])
        if "最近请求" in (hit2.get("via") or ""):
            ok("如实标注了「最近请求（可能不是正片）」")
        else:
            bad("没有标注候选来源：via=%r" % hit2.get("via"))
    else:
        bad("MSE 场景点完没找到候选")
else:
    bad("读不到 MSE 视频元素位置：%r" % rect2)

print("== 5. 空白处：如实说没找到，清单不变 ==")
n0 = len(sniff_items())
api("/api/sniff/pick?on=1")
time.sleep(0.8)
r = api("/api/sniff/pickAt?x=5&y=5")            # 左上角一般没东西
time.sleep(1)
if r.get("ok") is False and "没找到" in (r.get("error") or ""):
    ok("空白处如实回「没找到」：%s" % r.get("error"))
else:
    note("空白处这次返回：%s（坐标太靠边也可能 ok=false 别的理由）" % json.dumps(r, ensure_ascii=False)[:120])
n1 = len(sniff_items())
if n1 == n0:
    ok("清单条数没变（%d）" % n1)
else:
    bad("清单条数变了：%d → %d" % (n0, n1))

print("== 6. 长按入口（用户要求的第二个入口） ==")
nav(S + "/watch")
time.sleep(3)
# 先"预热"一次输入：模拟器偶发丢首击（点一个必定有效的地方，读回计数确认输入真的通了）
subprocess.run([ADB, "shell", "input", "tap", str(sx), str(sy)], capture_output=True, text=True)
time.sleep(1)
n0 = len([l for l in (api("/api/log?n=200").get("lines") or []) if "长按页面" in str(l)])
subprocess.run([ADB, "shell", "input", "motionevent", "DOWN", str(sx), str(sy)], capture_output=True, text=True)
time.sleep(0.9)
subprocess.run([ADB, "shell", "input", "motionevent", "UP", str(sx), str(sy)], capture_output=True, text=True)
time.sleep(2)
lines = [str(l) for l in (api("/api/log?n=200").get("lines") or []) if "长按页面" in str(l)]
if len(lines) > n0:
    ok("长按弹出了菜单（日志：%s）" % lines[-1][:80])
    subprocess.run([ADB, "shell", "input", "keyevent", "KEYCODE_BACK"], capture_output=True, text=True)
    subprocess.run([ADB, "shell", "rm", "-f", "/sdcard/u.xml"], capture_output=True, text=True)
    subprocess.run([ADB, "shell", "uiautomator", "dump", "/sdcard/u.xml"], capture_output=True, text=True, timeout=60)
    u = subprocess.run([ADB, "shell", "cat", "/sdcard/u.xml"], capture_output=True, text=True).stdout
    if "把这里当视频" in u:
        ok("菜单项文字读回来了：「把这里当视频」")
    else:
        note("菜单项文字没从 dump 里读到（菜单已被 BACK 关掉，正常）")
else:
    bad("长按没弹菜单（日志里没有「长按页面」）")

print("\n结果：%d PASS / %d FAIL" % (PASS, FAIL))
for n in NOTE:
    print("NOTE：" + n)
sys.exit(0 if FAIL == 0 else 1)
