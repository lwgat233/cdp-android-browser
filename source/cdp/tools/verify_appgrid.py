#!/usr/bin/env python3
"""首页小 app 网格：三面验收（API 面 / 控件面=真手指点 / 渲染面）。

用户的要求：像手机桌面一样的小 app 网格，**加号添加**，图标 + 名字，点开，长按改/删。
这个脚本按"真手指"走一遍：
  ① 首页能打开，网格有格子（＝默认那 4 个入口 + 「＋」）
  ② 点「＋」→ 出现小窗（读它真的在屏幕上）
  ③ 小窗里填名字+网址 → 点「确定」→ API 面多一条、渲染面多一格
  ④ 长按某一格 → 小窗里出现「删除」，点它 → API 面少一条、渲染面少一格
  ⑤ 点某一格 → 真的跳走（拿当前页 URL 判，不看提示文字）
用法：python3 tools/verify_appgrid.py
"""
import json
import os
import re
import subprocess
import sys
import time

SRC = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
ENV = dict(os.environ, PATH="/home/lwgat/.local/bin:/home/lwgat/tools/android-sdk/platform-tools:" + os.environ.get("PATH", ""))
HOME = "http://127.0.0.1:8848/ui/start.html"
ADB = "/home/lwgat/tools/android-sdk/platform-tools/adb"
P = F = 0
FAILS = []


def chk(name, got, want):
    global P, F
    ok = (got == want) if not callable(want) else bool(want(got))
    print("  %s %s  → %s" % ("PASS" if ok else "FAIL", name, json.dumps(got, ensure_ascii=False)[:150]))
    if ok:
        P += 1
    else:
        F += 1
        FAILS.append(name)


def sh(cmd, t=60):
    return subprocess.run(["bash", "-lc", cmd], capture_output=True, text=True, timeout=t, env=ENV).stdout.strip()


def api(path, t=25):
    try:
        return json.loads(sh("curl -s --max-time %d %s%s" % (t, HTTP, path), t + 8))
    except Exception:
        return {}


def ui(expr, t=60, match="ui/start.html"):
    r = subprocess.run(["node", "tools/uiprobe.mjs", "--timeout", "12000", "--match", match, expr],
                       capture_output=True, text=True, cwd=SRC, env=ENV, timeout=t)
    out = [l for l in (r.stdout or "").strip().splitlines() if l.strip()]
    return out[-1] if out else None


def dump_xml():
    sh("%s -s emulator-5554 shell uiautomator dump /sdcard/ag.xml" % ADB)
    return sh("%s -s emulator-5554 shell cat /sdcard/ag.xml" % ADB)


def webview_box():
    """WebView 在屏幕上的位置（uiautomator 量，别用 dumpsys 猜偏移）"""
    xml = dump_xml()
    for m in re.finditer(r"<node ([^>]+?)/?>", xml):
        a = dict(re.findall(r'([a-zA-Z\-]+)="([^"]*)"', m.group(1)))
        if a.get("class", "").endswith("WebView") and a.get("bounds"):
            n = re.findall(r"-?\d+", a["bounds"])
            if len(n) == 4 and (int(n[3]) - int(n[1])) > 500:
                return int(n[0]), int(n[1]), int(n[2]) - int(n[0])
    return None


def css_width():
    v = ui("String(window.innerWidth)")
    try:
        w = int(str(v))
        if w > 100:
            return float(w)
    except Exception:
        pass
    return 412.0


def tap_css(cx, cy, hold=0):
    """真手指＝设备侧真实触摸（adb shell input）。坐标＝WebView 屏幕位置 + CSS 坐标×缩放。"""
    vb = webview_box()
    if not vb:
        return False
    vx, vy, vw = vb
    s = vw / css_width()
    x, y = int(vx + cx * s), int(vy + cy * s)
    if hold > 0:
        sh("%s -s emulator-5554 shell input swipe %d %d %d %d %d" % (ADB, x, y, x, y, hold))
    else:
        sh("%s -s emulator-5554 shell input tap %d %d" % (ADB, x, y))
    time.sleep(1.6)
    return True


def center(expr):
    r = ui("(function(){var e=%s;if(!e)return '';var b=e.getBoundingClientRect();"
           "return JSON.stringify({x:Math.round(b.left+b.width/2),y:Math.round(b.top+b.height/2)});})()" % expr)
    try:
        return json.loads(r)
    except Exception:
        return None


def tap(expr, tries=3, hold=0):
    """真手指点某个元素：坐标现测（运行时算），点完没变化就重试"""
    for _ in range(tries):
        c = center(expr)
        if not c:
            time.sleep(1)
            continue
        tap_css(c["x"], c["y"], hold)
        return c
    return None


def sheet_text():
    v = ui("(document.getElementById('cdp-modal')||{}).textContent||''")
    return "" if v is None else str(v)


def n_apps():
    r = api("/api/apps/list")
    return len(r.get("apps") or [])


def n_tiles():
    v = ui("String(document.querySelectorAll('#apps .app').length)")
    try:
        return int(v)
    except Exception:
        return -1


print("[0] 打开首页（先按掉可能盖在上面的系统弹窗）")
for _ in range(2):
    sh("%s -s emulator-5554 shell input keyevent KEYCODE_BACK" % ADB)
    time.sleep(0.8)
api("/api/nav/open?url=" + HOME.replace(":", "%3A").replace("/", "%2F"))
time.sleep(4)
chk("首页真的打开了", api("/api/status").get("browser", {}).get("url", ""), lambda u: "ui/start.html" in u)
chk("网格渲染出来了（默认 4 个 + 「＋」）", n_tiles(), 5)
chk("渲染器只有一个（appgrid.js）", ui("(document.getElementById('apps')||{}).getAttribute('data-renderer')"), "appgrid.js")

print("[1] 点「＋」→ 小窗出现（控件面：真手指）")
before = n_apps()
tap("document.getElementById('app-add')")
chk("小窗里有「名字」「确定」", sheet_text(), lambda s: "名字" in s and "确定" in s)

print("[2] 填名字+网址 → 点「确定」→ 加进去了（API 面 + 渲染面）")
ui("(function(){var i=document.querySelectorAll('#cdp-modal input');"
   "if(i[0]){i[0].value='测试站点';i[0].dispatchEvent(new Event('input',{bubbles:true}));}"
   "if(i[1]){i[1].value='http://127.0.0.1:8848/ui/start.html?t=1';i[1].dispatchEvent(new Event('input',{bubbles:true}));}"
   "return 'ok';})()")
time.sleep(0.6)
tap("(function(){var b=document.querySelectorAll('#cdp-modal button');for(var i=0;i<b.length;i++){if((b[i].textContent||'').indexOf('确定')>=0)return b[i];}return null;})()")
time.sleep(1.5)
chk("API 面多了一条", n_apps(), before + 1)
chk("新那条的名字对", [a.get("name") for a in (api("/api/apps/list").get("apps") or [])], lambda l: "测试站点" in l)
chk("渲染面多了一格", n_tiles(), lambda n: n >= 6)

print("[3] 长按某一格 → 小窗里有「删除」→ 删掉（控件面 + API 面）")
n0 = n_apps()
# 长按＝按下 0.8 秒再抬手（用真实输入：按下/等待/抬起）
got = ""
for hold in (800, 1500):
    tap("document.querySelectorAll('#apps .app')[4]", hold=hold)
    got = sheet_text()
    if "删除" in got:
        break
chk("长按后小窗里有「删除」", got, lambda s: "删除" in s)
if "删除" in got:
    tap("(function(){var b=document.querySelectorAll('#cdp-modal button');for(var i=0;i<b.length;i++){if((b[i].textContent||'').indexOf('删除')>=0)return b[i];}return null;})()")
    time.sleep(1.5)
    chk("删掉后 API 面少一条", n_apps(), n0 - 1)
    chk("渲染面也少一格", n_tiles(), lambda n: n <= 5)

print("[4] 点一格 → 真的打开了它对应的地方")
# 判据说明（踩过一次）：格子里的 cdpctl:// 是**自有协议**，原生接到后打开的是**控制台浮层**并切栏目，
# 浏览器地址栏不会变（所以不能拿 browser.url 去判，会假红）。判据要读：浮层开着 + 当前栏目对了。
tap("document.querySelectorAll('#apps .app')[1]")          # 第 2 格 = 历史
time.sleep(3)
st = api("/api/status")
chk("点「历史」格 → 控制台浮层真的开着", st.get("consoleOpen"), True)
tab = ui("(function(){var t=document.querySelector('.tab.on');return t?t.id:'';})()", match="ui/index.html")
chk("并且切到了「历史」栏目", tab, lambda v: str(v) == "tab-history")
api("/api/ui/open?tab=page")

print("\n结果：%d/%d 通过" % (P, P + F))
if FAILS:
    print("  未通过：" + "；".join(FAILS))
sys.exit(1 if F else 0)
