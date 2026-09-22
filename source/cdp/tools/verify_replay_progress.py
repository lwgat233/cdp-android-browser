#!/usr/bin/env python3
"""回放进度可见性验收（功能 id：rec.progress）

用户原话：「对于录制脚本的播放，我觉得应该需要有显示出正在执行，包括点，包括执行那一步。」
规格：docs/新需求规格-回放进度与视频指定位置.md

判据（全是读回来的事实）：
  [A] 回放过程中，页面顶部那行小字出现且匹配「回放 第 k/3 步 · 坐标 …」
  [B] 回放过程中，页面上至少有一步的小点 data-state == "current"（橙）
  [C] 回放过程中（出现进度小字的那些帧里），小点 pointer-events == "none"（锁住不接触摸 → 防 B-09 成环）
  [D] 回放结束后，三个点全是 "done"（绿），顶部小字消失
  [E] 回放结束后，小点恢复可点（pointer-events != "none"）
  [F] 真点到位：测试页计数器 window.__hits == 3（点少了=某步没生效；点多了=成环）
  [G] 不成环：日志里「回放第 k/3 步」每个 k 只出现 1 次

两条踩过的坑（写进脚本，别重犯）：
  ① 装包/重启 App 后 devtools socket 名变了（webview_devtools_remote_<pid>）→ 不重建 9222 转发，
     uiprobe 会静默拿不到控制台页面，插入步骤全部无效 → 假 FAIL。脚本开头自己重建转发。
  ② 坐标步必须带**位置锚点**（topPx/bottomPx/ratio…），只给 cx/cy 的裸坐标步回放时定位不到，
     会失败回落成 idle（那不是"没显示"，是"没点成"）。

用法：python3 tools/verify_replay_progress.py
"""
import json
import os
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request

ROOT = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
PAGE = "https://appassets.androidplatform.net/test/tap.html"
ADB = "/home/lwgat/tools/android-sdk/platform-tools/adb"
ENV = dict(os.environ, PATH="/home/lwgat/.local/bin:" + os.environ.get("PATH", ""))
PTS = [(120, 240), (170, 300), (220, 360)]
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


def uiprobe(js, timeout=40):
    r = subprocess.run(["node", "tools/uiprobe.mjs", "--timeout", "12000", "--match", "ui/index.html", js],
                       capture_output=True, text=True, cwd=ROOT, timeout=timeout, env=ENV)
    out = [l for l in (r.stdout or "").strip().splitlines() if l.strip()]
    return out[-1] if out else ""


def console_call(op, args):
    js = ("(function(){try{CDPT.call(%s,%s);}catch(e){}return 'sent';})()"
          % (json.dumps(op), json.dumps(args)))
    return uiprobe(js)


PROBE = ("(function(){var o={prog:null,dots:{},pe:null};"
         "var p=document.getElementById('cdp-replay-progress');if(p)o.prog=p.textContent;"
         "var L=document.getElementById('cdp-dots');var ds=L?L.querySelectorAll('.cdp-dot'):[];"
         "for(var i=0;i<ds.length;i++){o.dots[ds[i].getAttribute('data-n')]=ds[i].getAttribute('data-state');}"
         "if(ds.length)o.pe=getComputedStyle(ds[0]).pointerEvents;"
         "o.hits=window.__hits||0;return JSON.stringify(o);})()")


def coord_step(x, y, vw, vh):
    """一条带位置锚点的坐标步（锚点字段与页面 anchorOf 一致）"""
    box = {"cx": x, "cy": y, "x": x, "y": y, "w": 1, "h": 1}
    anchor = {
        "mode": "bottom" if (vh - y) <= y else "top",
        "topPx": round(y), "bottomPx": round(vh - y), "leftPx": round(x), "rightPx": round(vw - x),
        "cx": x, "cy": y, "vw": vw, "vh": vh,
        "ratioY": round(y / vh, 4), "ratioX": round(x / vw, 4),
        "ratioBottom": round((vh - y) / vh, 4), "ratioTop": round(y / vh, 4),
        "scrollY": 0, "docH": round(vh),
    }
    return {"t": "click", "ts": int(time.time() * 1000), "url": PAGE, "title": "回放进度测试页",
            "mode": "coord", "selector": "",
            "target": {"selector": "", "id": "", "tag": "div", "text": "", "attrs": {}, "box": box},
            "box": box, "anchor": anchor, "via": "verify-replay-progress", "pauseAfter": 650}


print("== 0. 环境 ==")
print("   重建转发（pid=%s）" % ensure_forward())
st0 = api("/api/status")
if not st0.get("ok"):
    print("控制口不通，先起环境。")
    sys.exit(2)
# 刚装完包 / 刚启动时控制口已经在听，但页面还没量出尺寸（cssW=0）——等到有视口再继续
vw = vh = 0
for _ in range(40):
    b = (api("/api/status").get("browser") or {})
    vw, vh = int(b.get("cssW") or 0), int(b.get("cssH") or 0)
    if vw > 0 and vh > 0:
        break
    time.sleep(1.5)
if vw <= 0 or vh <= 0:
    print("页面没量出视口（cssW/cssH 还是 0），先确认 App 起来了。")
    sys.exit(2)
print("   视口 %dx%d" % (vw, vh))

print("== 1. 准备：测试页 + 清空队列 + 插 3 个带锚点的坐标步 ==")
api("/api/nav/open?url=" + urllib.parse.quote(PAGE))
for _ in range(20):
    time.sleep(1)
    if str(page_eval("String(!!document.body)")) == "true":
        break
time.sleep(1)
api("/api/ui/open?tab=rec")
for _ in range(15):
    time.sleep(1.5)
    if "true" in uiprobe("String(!!window.CDPT)"):
        break
console_call("rec.clear", {})
time.sleep(0.8)
for (x, y) in PTS:
    console_call("rec.insert", {"step": coord_step(x, y, vw, vh)})
    time.sleep(0.4)
time.sleep(1)
cnt = api("/api/record/dots/status").get("count")
if cnt == len(PTS):
    ok("队列里有 %d 个坐标步（测试数据就位）" % cnt)
else:
    bad("队列里只有 %s 个步（插步骤没成功，后面结论不可信）" % cnt)
    sys.exit(1)
before = page_eval(PROBE)
print("   回放前：", before)

print("== 2. 回放，同时按帧采样页面 ==")
frames = []


def run():
    try:
        api("/api/run?what=queue&times=1", timeout=240)
    except Exception as e:
        frames.append({"err": str(e)})


th = threading.Thread(target=run, daemon=True)
th.start()
t0 = time.time()
while time.time() - t0 < 25:
    try:
        s = page_eval(PROBE, timeout=30)
        if isinstance(s, dict):
            frames.append(s)
    except Exception:
        pass
    if not th.is_alive() and time.time() - t0 > 2:
        break
    time.sleep(0.15)
th.join(timeout=60)
time.sleep(1)
final = page_eval(PROBE)
print("   采样 %d 帧；最后一帧：%s" % (len(frames), final))

withprog = [f for f in frames if isinstance(f.get("prog"), str) and "回放 第" in f["prog"]]
if withprog:
    ok("回放中读到进度小字：%r" % withprog[0]["prog"])
    if any("/%d 步" % len(PTS) in f["prog"] for f in withprog):
        ok("进度里带步数（第 k/%d 步）" % len(PTS))
    else:
        bad("进度里的步数不对：%r" % [f["prog"] for f in withprog][:3])
    if any("坐标" in f["prog"] for f in withprog):
        ok("进度里说清了这一步在干什么（坐标步给了坐标）")
    else:
        bad("进度里没说这一步在干什么：%r" % withprog[0]["prog"])
else:
    bad("回放过程中没读到进度小字")

cur = [f for f in frames if "current" in (f.get("dots") or {}).values()]
if cur:
    ok("回放中读到「正在执行」的小点：%s" % json.dumps(cur[0]["dots"], ensure_ascii=False))
else:
    bad("回放中没有出现 state=current 的小点：%r" % [f.get("dots") for f in frames][:3])

pe_prog = [f.get("pe") for f in withprog if f.get("pe")]
if pe_prog and all(v == "none" for v in pe_prog):
    ok("回放中小点被锁住（pointer-events=none，防成环）")
elif pe_prog:
    bad("回放中小点还是可点的：%r" % sorted(set(pe_prog)))
else:
    bad("没采到回放期间小点的 pointer-events")

fin = final if isinstance(final, dict) else {}
dots = fin.get("dots") or {}
if dots and all(v == "done" for v in dots.values()):
    ok("回放结束后三个点都是 done：%s" % json.dumps(dots, ensure_ascii=False))
else:
    bad("回放结束后点的状态不对：%s（replay 失败会回落 idle，先看下面那条真点击数）"
        % json.dumps(dots, ensure_ascii=False))
if not fin.get("prog"):
    ok("回放结束后顶部小字已收掉")
else:
    bad("回放结束后小字还在：%r" % fin.get("prog"))
if fin.get("pe") and fin["pe"] != "none":
    ok("回放结束后小点恢复可点（pointer-events=%s）" % fin["pe"])
else:
    bad("回放结束后小点还被锁着：%r" % fin.get("pe"))

hits = int(fin.get("hits") or 0)
if hits == len(PTS):
    ok("真点到位：测试页计数器 __hits=%d（既没漏点也没成环）" % hits)
else:
    bad("真点击数不对：__hits=%d，期望 %d" % (hits, len(PTS)))

print("== 3. 不成环（B-09 回归） ==")
blob = json.dumps(api("/api/log?n=400"), ensure_ascii=False)
for k in range(1, len(PTS) + 1):
    n = blob.count("回放第 %d/%d 步" % (k, len(PTS)))
    if n == 1:
        ok("第 %d 步只执行了一次（日志出现 1 次）" % k)
    else:
        bad("第 %d 步在日志里出现 %d 次（成环或没执行）" % (k, n))

print("\n结果：%d PASS / %d FAIL" % (PASS, FAIL))
sys.exit(0 if FAIL == 0 else 1)
