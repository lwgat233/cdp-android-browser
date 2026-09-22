#!/usr/bin/env python3
"""测试环境体检（用户要求："特别是验证测试环境，再做一遍"）。

这一夜模拟器容器 SIGSEGV 崩了 6 次，每次都把正在跑的验收打断成假红 —— 所以**每轮验收前先跑这个**：
它把"环境到底行不行"一次查清，能自愈的就自愈（起模拟器 / 装应用 / 重建转发 / 拉起应用），
最后给一句明确的结论：READY（可以跑验收）还是 NOT-READY（先修环境，别跑验收）。

检查项与判据：
  ① 宿主机资源：可用内存 / 磁盘（避免触发系统级保护）
  ② 模拟器容器在跑 + adb 设备在线
  ③ 应用装了、进程在、两个转发都在、控制口 /api/status 通
  ④ 控制台 WebView 能 eval（innerWidth>0）＋ 浮层可见性（consoleOpen）
  ⑤ 构件一致：本地构建产物 sha == 归档 apk 的 sha（防止"测的不是存的那份"）
  ⑥ 浏览器页面真的能动（打开一个自带测试页并读回 URL）
用法：
  python3 tools/env_health.py            # 只体检，能自愈就自愈
  python3 tools/env_health.py --no-heal  # 只体检，不动任何东西
"""
import json
import os
import re
import subprocess
import sys
import time

ROOT = "/vol1/1000/airesults/cdp"
SRC = ROOT + "/source/cdp"
HTTP = "http://127.0.0.1:8848"
ENV = dict(os.environ, PATH="/home/lwgat/.local/bin:/home/lwgat/tools/android-sdk/platform-tools:" + os.environ.get("PATH", ""))
ADB = "/home/lwgat/tools/android-sdk/platform-tools/adb"
SER = "emulator-5554"
HEAL = "--no-heal" not in sys.argv
problems = []
done_actions = []


def sh(cmd, timeout=60):
    try:
        return subprocess.run(["bash", "-lc", cmd], capture_output=True, text=True, timeout=timeout,
                              env=ENV).stdout.strip()
    except Exception:
        return ""


def adb(args, timeout=60):
    return sh("%s -s %s %s" % (ADB, SER, args), timeout)


def api(path, timeout=20):
    try:
        return json.loads(sh("curl -s --max-time %d %s%s" % (timeout, HTTP, path), timeout + 5))
    except Exception:
        return {}


def ui(expr, timeout=45):
    r = subprocess.run(["node", "tools/uiprobe.mjs", "--timeout", "10000", "--match", "ui/index.html", expr],
                       capture_output=True, text=True, cwd=SRC, env=ENV, timeout=timeout)
    out = [l for l in (r.stdout or "").strip().splitlines() if l.strip()]
    return out[-1] if out else None


def ok(msg):
    print("  PASS " + msg)


def bad(msg):
    print("  FAIL " + msg)
    problems.append(msg)


# ---------------------------------------------------------------- ① 宿主资源
print("[1] 宿主机资源（内存/磁盘）")
mem = sh("free -m | awk 'NR==2{print $7}'")
disk = sh("df -h /vol1 | awk 'NR==2{print $4}'")
print("      可用内存 %s MB ｜ /vol1 可用 %s" % (mem or "?", disk or "?"))
if mem.isdigit() and int(mem) < 800:
    bad("可用内存不足 1GB（%s MB）→ 先停掉重活再跑验收" % mem)
else:
    ok("内存够用（%s MB）" % (mem or "?"))

# ---------------------------------------------------------------- ② 容器与设备
print("[2] 模拟器容器与设备")
cup = sh("docker ps --format '{{.Names}}' | grep -c notifbridge-emu")
dev = sh("%s devices | grep -c '%s	device'" % (ADB, SER))
if cup == "0" and HEAL:
    print("      容器不在 → 拉起（emu.sh start，AVD=test35）")
    sh("bash /vol1/1000/aicache/docker/emu.sh start >/dev/null 2>&1", timeout=300)
    done_actions.append("起了模拟器容器")
    for _ in range(40):
        if sh("%s devices | grep -c '%s	device'" % (ADB, SER)) != "0":
            break
        time.sleep(10)
    dev = sh("%s devices | grep -c '%s	device'" % (ADB, SER))
if dev != "0":
    ok("容器在跑、%s 在线" % SER)
else:
    bad("模拟器不在线（容器 %s）→ 跑不动任何验收" % ("有" if cup != "0" else "没有"))

# ---------------------------------------------------------------- ③ 应用与控制口
print("[3] 应用与控制口")
pid = adb("shell pidof dev.cdp")
if not pid and dev != "0" and HEAL:
    print("      应用没在跑 → 拉起（并等 10 秒）")
    adb("shell am start -n dev.cdp/.MainActivity")
    done_actions.append("拉起了应用")
    time.sleep(10)
    pid = adb("shell pidof dev.cdp")
if pid:
    ok("应用进程在（pid %s）" % pid)
else:
    bad("应用没在跑")

if pid and HEAL:
    # 转发可能因为进程重启失效 → 每轮重建（这是最容易假红的一处）
    adb("forward --remove-all")
    adb("forward tcp:9222 localabstract:webview_devtools_remote_%s" % pid)
    adb("forward tcp:8848 tcp:8848")
    done_actions.append("重建了 9222/8848 转发")
fw = sh("%s forward --list | tr '\\n' ' '" % ADB)
st = api("/api/status")
if st.get("ok"):
    ok("控制口 /api/status 通（%s）" % (st.get("app", "?") + " " + str(st.get("version", ""))))
else:
    bad("控制口不通（转发：%s）" % (fw[:80] or "无"))
if "consoleOpen" not in st:
    bad("这一版没有 consoleOpen 字段 → 可能装的是旧构件（重新构建安装）")
else:
    ok("consoleOpen 字段在（浮层%s）" % ("开着" if st.get("consoleOpen") else "关着"))

# ---------------------------------------------------------------- ③·B 有没有系统弹窗盖在上面
# 这一夜踩过：launcher 触发 ANR，弹窗**抢走焦点**，所有真手指点都打在它身上 →
# 验收全红，看着像"应用的按键坏了"。所以每轮先查焦点、有弹窗就清掉。
print("[3B] 焦点与遮挡（ANR/系统弹窗会吃掉所有点击）")
foc = sh("%s -s %s shell dumpsys window | grep -E 'mCurrentFocus' | head -1" % (ADB, SER))
print("      " + (foc.strip()[:120] or "(读不到焦点)"))
bad_focus = ("Not Responding" in foc) or ("Application Error" in foc) or ("dev.cdp" not in foc)
if bad_focus and HEAL:
    print("      焦点不在本应用（或被弹窗抢走）→ 清掉弹窗并拉回应用")
    sh("%s -s %s shell am force-stop com.google.android.apps.nexuslauncher" % (ADB, SER))
    time.sleep(2)
    sh("%s -s %s shell input keyevent KEYCODE_BACK" % (ADB, SER))
    sh("%s -s %s shell am start -n dev.cdp/.MainActivity" % (ADB, SER))
    time.sleep(6)
    done_actions.append("清掉了抢焦点的系统弹窗")
    foc = sh("%s -s %s shell dumpsys window | grep -E 'mCurrentFocus' | head -1" % (ADB, SER))
if "dev.cdp" in foc:
    ok("焦点在本应用（点击能落到页面上）")
else:
    bad("焦点被抢走（%s）→ 真手指点会全落在弹窗上，验收必然假红" % foc.strip()[:80])

# ---------------------------------------------------------------- ③·C 屏幕锁着 / 息屏了没
# 这一夜踩过：给模拟器设过锁屏 PIN，息屏锁上之后应用不在前台 →
# uiautomator 量不到 WebView 框、真手指点击全落空，验收看起来"全红但其实没人点"。
print("[3C] 屏幕状态（锁屏/息屏会让所有真手指点击落空）")
dream = sh("%s -s %s shell dumpsys window | grep -E 'mDreamingLockscreen|mShowingLockscreen' | head -2" % (ADB, SER))
print("      " + (dream.replace("\n", " ｜ ")[:120] or "(读不到锁屏状态)"))
if ("true" in dream.lower()) and HEAL:
    sh("%s -s %s shell input keyevent KEYCODE_WAKEUP" % (ADB, SER))
    time.sleep(1)
    done_actions.append("唤醒了屏幕")
    dream = sh("%s -s %s shell dumpsys window | grep -E 'mDreamingLockscreen|mShowingLockscreen' | head -2" % (ADB, SER))
if "true" in dream.lower():
    bad("屏幕还锁着（验收会全红但其实是没人点到）→ 先解锁再来")
else:
    ok("屏幕没锁（点击能落到页面上）")

# ---------------------------------------------------------------- ④ 控制台页面
print("[4] 控制台 WebView")
api("/api/ui/open?tab=page")
time.sleep(2)
iw = ui("String(window.innerWidth)")
if str(iw).isdigit() and int(iw) > 0:
    ok("控制台页面能 eval（innerWidth=%s）" % iw)
else:
    bad("控制台页面 eval 不通（innerWidth=%s）" % iw)
ver = ui("window.__cdpAppVer")
if ver:
    ok("界面版本串：%s" % ver)
else:
    bad("读不到界面版本串（页面没就绪）")

# ---------------------------------------------------------------- ⑤ 构件一致
print("[5] 构件一致（测的就是存的那份）")
build = SRC + "/app/build/outputs/apk/debug/app-debug.apk"
h1 = sh("sha256sum %s | awk '{print $1}'" % build)
# 归档有两路：主归档 apk/cdp-debug.apk 与 **测试版** apk/测试版/*.apk（专门测某个功能的）。
# 只要与其中一份精确一致就算一致；两路都没有就是真的没归档。
cands = [ROOT + "/apk/cdp-debug.apk"] + sorted(map(str, __import__("glob").glob(ROOT + "/apk/测试版/*.apk")))
hit = None
for c in cands:
    hc = sh("sha256sum '%s' | awk '{print $1}'" % c)
    if h1 and hc == h1:
        hit = c
        break
if hit:
    ok("构件已归档且哈希一致（%s… ← %s）" % (h1[:16], hit.split("/")[-1]))
else:
    bad("构建产物(%s…) 没有对应的归档（主归档或 apk/测试版/ 里都对不上）→ 归档一次再干活" % h1[:12])

# ---------------------------------------------------------------- ⑥ 浏览器页面能动
print("[6] 浏览器页面（换页 + 读回）")
api("/api/goto?url=" + "https%3A%2F%2Fappassets.androidplatform.net%2Ftest%2Ftap.html")
time.sleep(3)
cur = (api("/api/status").get("browser") or {}).get("url", "")
if "tap.html" in cur:
    ok("换页正常（当前页 %s）" % cur[:60])
else:
    bad("换页失败（当前页 %s）" % (cur[:60] or "读不到"))

# ---------------------------------------------------------------- 结论
print("")
if done_actions:
    print("本轮自愈动作：" + "；".join(done_actions))
if problems:
    print("结论：NOT-READY —— 先修上面 %d 个问题，再跑验收（不然全是假红）" % len(problems))
    for p in problems:
        print("   · " + p)
    sys.exit(1)
print("结论：READY —— 环境正常，可以跑验收")
sys.exit(0)
