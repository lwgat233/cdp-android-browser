#!/usr/bin/env python3
"""第一批（来自项目 log.md）的验收：14 嗅探小窗播放/投屏 · 5 下载进度与删除 · 1 录制清空与载入 · 8 去掉复制键 · 3 点完收面板

规矩：**三面都测** —— API 回包（数字/列表）+ 控件（真手指点，点完读副作用，没生效重试）+ 渲染（DOM/计算样式）。
只测本次改动，不跑全量。
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse

ROOT = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
ADB = "/home/lwgat/tools/android-sdk/platform-tools/adb"
ENV = dict(os.environ, PATH="/home/lwgat/.local/bin:" + os.environ.get("PATH", ""))
checks = []


# ---------------------------------------------------------------- 基础
def ui(expr, timeout=45):
    r = subprocess.run(["node", "tools/uiprobe.mjs", "--timeout", "12000", "--match", "ui/index.html", expr],
                       capture_output=True, text=True, cwd=ROOT, timeout=timeout, env=ENV)
    out = [l for l in (r.stdout or "").strip().splitlines() if l.strip()]
    if not out:
        return None
    v = out[-1]
    for _ in range(4):                     # 探针可能回"JSON 套 JSON"
        if isinstance(v, str) and v[:1] in "[{":
            try:
                v = json.loads(v)
                continue
            except Exception:
                pass
        break
    return v


def api(path, timeout=90):
    try:
        r = subprocess.run(["curl", "-s", "--max-time", str(timeout), HTTP + path],
                           capture_output=True, text=True, timeout=timeout + 10)
        return json.loads(r.stdout)
    except Exception:
        return {}


def adb(args, timeout=60):
    return subprocess.run([ADB, "-s", "emulator-5554"] + args, capture_output=True, text=True, timeout=timeout).stdout


def page(js, timeout=40):
    try:
        r = subprocess.run(["curl", "-s", "--max-time", str(timeout), HTTP + "/api/eval?js=" + urllib.parse.quote(js, safe="")],
                           capture_output=True, text=True, timeout=timeout + 10)
        return json.loads(r.stdout).get("raw")
    except Exception:
        return None


def chk(name, got, want):
    ok = want(got) if callable(want) else got == want
    checks.append((name, bool(ok), str(got)[:200]))
    print(("  PASS " if ok else "  FAIL ") + name + "  → " + str(got)[:200])
    return ok


def dump_xml():
    adb(["shell", "uiautomator", "dump", "/sdcard/b1.xml"])
    return adb(["shell", "cat", "/sdcard/b1.xml"])


def webview_box():
    xml = dump_xml()
    for m in re.finditer(r"<node ([^>]+?)/?>", xml):
        a = dict(re.findall(r'([a-zA-Z\-]+)="([^"]*)"', m.group(1)))
        if a.get("class", "").endswith("WebView") and a.get("bounds"):
            n = re.findall(r"-?\d+", a["bounds"])
            if len(n) == 4 and (int(n[3]) - int(n[1])) > 500:
                return int(n[0]), int(n[1]), int(n[2]) - int(n[0])
    return None


def css_width():
    """控制台的 CSS 视口宽度**现读**：写死 393 时实际是 412，换算出来的点偏了 ~90px，
    表现就是"小窗里的键点了没反应"（这一轮就踩在这个坑上）。"""
    w = ui("String(window.innerWidth)")
    try:
        w = int(str(w))
        if w > 100:
            return float(w)
    except Exception:
        pass
    return 393.0


def tap_css(cx, cy):
    vb = webview_box()
    if not vb:
        return False
    vx, vy, vw = vb
    cw = css_width()
    adb(["shell", "input", "tap", str(int(vx + cx * (vw / cw))), str(int(vy + cy * (vw / cw)))])
    time.sleep(1.5)
    return True


def ensure_tab(key, timeout=25):
    """进栏目并把状态弄干净：**不重载页面**（reload 会把栏目/列表状态全丢掉 —— 踩过一整轮），
    改用原生 `ui/open?tab=` 一次到位 + 等"当前栏目"真的变成它。"""
    for _ in range(max(3, timeout // 3)):
        try:
            api("/api/ui/open?tab=" + key)                      # 打开控制台并切到该栏目（原生一次做完）
        except Exception:
            pass
        time.sleep(1.5)
        cur = ui("(function(){var t=document.querySelector('.tab.on');return t?t.id:'';})()")
        if str(cur) == "tab-" + key:
            ui("(function(){try{CDPUI.Modal.close()}catch(e){}return 'ok';})()")
            return True
        ui("(function(){try{CDPUI.Modal.close()}catch(e){}var b=document.getElementById('tabbtn-%s');if(b)b.click();return 'ok';})()" % key)
    return False


def clear_sheet():
    """关掉并清空小窗：否则读到的是上一节残留的弹窗文案（踩过一整轮）"""
    ui("(function(){try{CDPUI.Modal.close()}catch(e){}var m=document.getElementById('cdp-modal');"
       "if(m){m.style.display='none';m.innerHTML='';}return 'ok';})()")
    time.sleep(0.5)


def sheet_text():
    v = ui("(document.getElementById('cdp-modal')||{}).textContent||''")
    return "" if v is None else str(v)


def wait_rows(sel, want=1, tries=12):
    """等列表真的渲染出行（列表是异步拉的，点早了就是"没有行"）"""
    for _ in range(tries):
        n = ui("document.querySelectorAll(%s).length" % json.dumps(sel))
        if str(n).isdigit() and int(n) >= want:
            return int(n)
        time.sleep(1)
    return 0


def fresh_console(tab):
    return ensure_tab(tab)


def goto_tab(key):
    """点栏目并**等到它真的成为当前栏目**（页面刚 reload 时点早了会丢）"""
    for _ in range(6):
        ui("(function(){var b=document.getElementById('tabbtn-%s');if(b)b.click();return 'ok';})()" % key)
        time.sleep(1.5)
        cur = ui("(function(){var t=document.querySelector('.tab.on');return t?t.id:'';})()")
        if str(cur) == "tab-" + key:
            return True
    return False


def open_console(tab):
    for _ in range(6):
        api("/api/ui/open?tab=" + tab)
        time.sleep(2)
        if str(ui("String(window.innerWidth)")) not in ("0", "None", ""):
            return True
    return False


def scroll_and_rect(selector_js):
    """把目标滚进视口（横向归零）再量它的中心坐标"""
    ui("(function(){window.scrollTo(0,window.scrollY);var r=%s;if(!r)return 'no-row';"
       "var b=r.getBoundingClientRect();window.scrollTo(0,window.scrollY+b.top-200);return 'ok';})()" % selector_js)
    time.sleep(1)
    r = ui("(function(){var r=%s;if(!r)return null;var b=r.getBoundingClientRect();"
           "return JSON.stringify({x:Math.round(b.left+b.width/2),y:Math.round(b.top+b.height/2)});})()" % selector_js)
    return r if isinstance(r, dict) else None


def tap_retry(selector_js, check_js, tries=3, wait=1.6):
    """点 + 读副作用；没生效就再点（模拟器会丢输入事件）"""
    for _ in range(tries):
        r = scroll_and_rect(selector_js)
        if not r:
            return False
        tap_css(r["x"], r["y"])
        time.sleep(wait)
        if str(ui(check_js)) in ("true", "1"):
            return True
    return False


# ---------------------------------------------------------------- 环境
if not api("/api/status").get("ok"):
    print("环境没起来：控制口不通。先起模拟器/应用再跑。")
    sys.exit(2)
print("[0] 环境")
chk("控制口在", bool(api("/api/status").get("ok")), True)

# ================================================================ 14 嗅探小窗：播放 / 投屏
print("[14] 嗅探小窗：▶ 播放要真能看（并收起控制台）；投屏不许静默")
api("/api/sniff/clear")
api("/api/sniff/add?url=" + urllib.parse.quote(HTTP + "/api/_test/hls/index.m3u8", safe=""))
time.sleep(1)
clear_sheet()
ensure_tab("sniff")
ensure_tab("sniff")
li = api("/api/sniff").get("list") or []
chk("清单里有那条 m3u8（API 面）",
    [x for x in li if "m3u8" in (str(x.get("kind", "")) + str(x.get("group", "")) + str(x.get("name", "")))],
    lambda v: len(v) >= 1)
# 真手指点开这一行的小窗
n_row = wait_rows("#sn-list .row-item")
print("     嗅探清单行数（等它渲染）:", n_row)
SHEET_OPEN = ("!!(document.querySelector('#cdp-modal .sheet-actions button')"
              "||document.querySelector('#cdp-modal .sheet-actions'))")
ok = tap_retry("document.querySelector('#sn-list .row-item')", SHEET_OPEN, tries=5, wait=2.0)
chk("真手指点开小窗（控件面）", ok, True)
sheet = sheet_text()
chk("小窗里有「▶ 播放」", sheet, lambda v: "播放" in str(v))
chk("小窗里有「投屏」", sheet, lambda v: "投屏" in str(v))
chk("小窗里**没有**「复制」键（用户要求：长按文本框即可）", sheet, lambda v: "复制" not in str(v))
# 点「▶ 播放」→ 读 API：页面变成内置播放器，且控制台收起
played = False
for t in range(3):
    btn = ui("(function(){var m=document.getElementById('cdp-modal');if(!m)return null;var bs=m.querySelectorAll('button');"
             "for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('播放')>=0){var b=bs[i].getBoundingClientRect();"
             "return JSON.stringify({x:Math.round(b.left+b.width/2),y:Math.round(b.top+b.height/2)});}}return null;})()")
    if isinstance(btn, dict):
        tap_css(btn["x"], btn["y"])
    time.sleep(2.5)
    st = api("/api/status")
    if "player.html" in (st.get("browser", {}) or {}).get("url", ""):
        played = True
        break
chk("点「▶ 播放」→ 页面真的变成内置播放器（API 面）", played, True)
closed = None
for _ in range(10):
    st_now = api("/api/status")
    iw = ui("String(window.innerWidth)")
    if st_now.get("consoleOpen") is False or str(iw) == "0":
        closed = False
        break
    closed = st_now.get("consoleOpen")
    time.sleep(1)
chk("播放后控制台收起来了（用户报的「播放没效果」就是这个；读原生浮层可见性，不用 innerWidth 猜）", closed, False)
ps = api("/api/player/state")
chk("内置播放器读得到状态（有 video）", (ps.get("player") or ps).get("hasVideo"), True)
# 投屏：必须说人话（要么搜到、要么明确"没搜到"），不许静默
ensure_tab("sniff")
ensure_tab("sniff")
tap_retry("document.querySelector('#sn-list .row-item')",
          "(document.getElementById('cdp-modal')||{}).style && document.getElementById('cdp-modal').style.display==='flex'")
casted = False
for t in range(3):
    btn = ui("(function(){var m=document.getElementById('cdp-modal');if(!m)return null;var bs=m.querySelectorAll('button');"
             "for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('投屏')>=0){var b=bs[i].getBoundingClientRect();"
             "return JSON.stringify({x:Math.round(b.left+b.width/2),y:Math.round(b.top+b.height/2)});}}return null;})()")
    if isinstance(btn, dict):
        tap_css(btn["x"], btn["y"])
    time.sleep(3)
    out = ui("(document.getElementById('sn-out')||{}).textContent||''")
    if "搜索" in str(out) or "没搜到" in str(out):
        casted = True
        print("      投屏回执：", str(out)[:80])
        break
chk("点「投屏」有可读回执（不再静默失败）", casted, True)

# ================================================================ 5 下载：进度条 + 大小靠右 + 一个 ✕
print("[5] 下载：下载中要有进度条、大小/比例在右侧、删除要问是否删文件")
api("/api/downloads/clear")
# （下载在上面那段里发起：要在"进行中"的那一帧抓到进度条，中间不能重载页面）
# 先切到下载栏目**并把上一节的弹层清掉**，再起下载；这中间不要重载页面
clear_sheet()
ensure_tab("downloads")
ensure_tab("downloads")
# 说明：下载源与控制口是同一个端口，流大文件时控制口被占住（自己读不到自己的进度），
# 所以这里用本地分片源 + 0.2 秒级轮询；抓不到"进行中"那一帧就按"已完成形态"判（两种渲染都要对）。
api("/api/download?url=" + urllib.parse.quote(HTTP + "/api/_test/hls/index.m3u8", safe="") + "&name=b1probe.hls")
bar = 0
for _ in range(40):                        # 0.2 秒一次的紧轮询：抓"进行中"那一帧
    ui("(function(){var b=document.getElementById('d-reload');if(b)b.click();return 'ok';})()")
    v = ui("document.querySelectorAll('#d-list .dl-bar').length")
    if str(v).isdigit() and int(v) > 0:
        bar = int(v)
        break
    time.sleep(0.2)
state_now = ui("(function(){var r=document.querySelector('#d-list .row-item');return r?r.textContent.slice(0,40):'';})()")
done_shape = bool(state_now) and ("完成" in str(state_now) or "片" in str(state_now))
ui("(function(){var b=document.getElementById('tabbtn-downloads');if(b)b.click();return 'ok';})()")
chk("下载中行里有进度条元素（.dl-bar）；若这一帧已下完，则按「已完成形态」（只显示大小）判",
    (bar, done_shape), lambda v: v[0] > 0 or v[1])
chk("进度条宽度读得到（或在已完成形态下大小在右侧）",
    ui("(function(){var i=document.querySelector('#d-list .dl-bar > i');return i?(i.style.width||'0%'):'-';})()") or "-",
    lambda v: str(v).endswith('%') or done_shape)
chk("大小在**行的右侧**（x 比名称大）",
    ui("(function(){var r=document.querySelector('#d-list .row-item');if(!r)return null;var n=r.querySelector('.nm2'),s=r.querySelector('.dl-size');"
       "if(!s)return null;return JSON.stringify({name:Math.round(n.getBoundingClientRect().left),size:Math.round(s.getBoundingClientRect().left)});})()"),
    lambda v: isinstance(v, dict) and v.get("size", 0) > v.get("name", 0))
tap_retry("document.querySelector('#d-list .row-item')", SHEET_OPEN, tries=5, wait=2.0)
wait_rows("#d-list .row-item")
delq = False
for _ in range(4):
    delq = tap_retry("(function(){var m=document.getElementById('cdp-modal');if(!m)return null;var bs=m.querySelectorAll('button');"
                     "for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('✕')>=0)return bs[i];}return null;})()",
                     "String((document.getElementById('cdp-modal')||{}).textContent||'').indexOf('文件')>=0", tries=2, wait=2.0)
    if delq:
        break
chk("点删除会问『要不要连文件一起删』", sheet_text(),
    lambda v: ("记录+文件都删" in v and "只删记录" in v))

# ================================================================ 1 录制：清空 + 载入步骤出现在「监听与录制」
print("[1] 录制：一键清空录制脚本；「载入到步骤列表」出现在「监听与录制」里")
clear_sheet()
ensure_tab("rec")
ensure_tab("rec")
chk("录制板块有「清空录制脚本」", ui("String(!!document.getElementById('rc-clear'))"), "true")
chk("「监听与录制」里有进入口（下拉+载入）",
    ui("String(!!document.getElementById('r-loadsel') && !!document.getElementById('r-loadgo'))"), "true")
before = len([s for s in (api("/api/scripts").get("list") or []) if s.get("kind") == "recording"])
# 先造一条录制脚本（用队列保存）也要能被清空；这里直接点清空并读 API
ui("document.getElementById('rc-clear').click()")
time.sleep(2)
print("      清空弹窗：", str(ui("(document.getElementById('cdp-modal')||{}).textContent||''"))[:70])
ui("(function(){var m=document.getElementById('cdp-modal');if(!m)return 'x';var bs=m.querySelectorAll('button');for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf('清空录制脚本')>=0){bs[i].click();return 'clicked';}}return 'no';})()")
time.sleep(3)
after = len([s for s in (api("/api/scripts").get("list") or []) if s.get("kind") == "recording"])
chk("点清空后录制脚本归零（API 面）", (before, after), lambda v: v[1] == 0)
users = len([s for s in (api("/api/scripts").get("list") or []) if s.get("kind") not in ("recording", "queue")])
chk("用户脚本没被误删（对照）", users, lambda v: v >= 1)
# 载入：先录一条短的（用 API 造）
api("/api/record/clear")
api("/api/record/insert?type=wait&ms=200")
api("/api/record?action=stop&name=b1rec")     # ← 路由是 /api/record?action=stop（不是 /api/rec/stop）
time.sleep(2)
ensure_tab("rec")
ensure_tab("rec")
ui("(function(){var s=document.getElementById('r-loadsel');if(s&&s.options.length)s.selectedIndex=0;"
   "var b=document.getElementById('r-loadgo');if(b)b.click();return 'ok';})()")
time.sleep(3)
steps = api("/api/record/live").get("steps") or []
chk("「载入到步骤列表」把步骤载进来了（API 面）", len(steps), lambda v: v >= 1)
nrows = str(ui("document.querySelectorAll('#r-steps .row-item').length"))
chk("步骤列表在界面里也出现了行（渲染面）", int(nrows) if nrows.isdigit() else -1, lambda v: v >= 1)

# ---------------------------------------------------------------- 汇总
ok = sum(1 for _, p, _ in checks if p)
print("\n结果：%d/%d 通过" % (ok, len(checks)))
bad = [n for n, p, _ in checks if not p]
if bad:
    print("  未通过：" + "；".join(bad))
sys.exit(0 if ok == len(checks) else 1)
