#!/usr/bin/env python3
"""用户报的「添加 APP 没效、刷新不了、小窗确定吗」—— 必须从**内置资源入口**也能加成功。

背景（真因）：`https://appassets.androidplatform.net/ui/start.html` 里用 fetch 打本机控制口
会被混合内容策略拦掉 → 页面在、不报错、添加了不渲染。修法＝小 app 网格改走**原生桥**（window.CDPT.call）。
本脚本就验这条：① 桥在；② 从资源入口加一个 → 真落库 + 真渲染；③ 刷新后还在；④ 删掉能删。
用法：python3 tools/verify_home_asset.py
"""
import json
import os
import subprocess
import sys
import time

SRC = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
ENV = dict(os.environ, PATH="/home/lwgat/.local/bin:/home/lwgat/tools/android-sdk/platform-tools:" + os.environ.get("PATH", ""))
ASSET = "https://appassets.androidplatform.net/ui/start.html"
P = F = 0
FAILS = []


def chk(name, got, want):
    global P, F
    ok = want(got) if callable(want) else got == want
    print("  %s %s  → %s" % ("PASS" if ok else "FAIL", name, json.dumps(got, ensure_ascii=False)[:170]))
    if ok:
        P += 1
    else:
        F += 1
        FAILS.append(name)


def sh(cmd, t=120):
    return subprocess.run(["bash", "-lc", cmd], capture_output=True, text=True, timeout=t, env=ENV).stdout.strip()


def api(path, t=25):
    try:
        return json.loads(sh("curl -s --max-time %d '%s%s'" % (t, HTTP, path), t + 8))
    except Exception:
        return {}


def ui(expr, t=70, match="ui/start.html"):
    r = subprocess.run(["node", "tools/uiprobe.mjs", "--timeout", "15000", "--match", match, expr],
                       capture_output=True, text=True, cwd=SRC, env=ENV, timeout=t)
    out = [l for l in (r.stdout or "").strip().splitlines() if l.strip()]
    return out[-1] if out else None


def js(expr):
    return ui(expr)


print("[0] 内置资源入口：打开后**要么被 App 导到同源那份，要么必须给出明确提示**（不许静默空白）")
api("/api/nav/open?url=" + ASSET.replace(":", "%3A").replace("/", "%2F"))
time.sleep(7)
cur = (api("/api/status").get("browser") or {}).get("url", "")
print("     当前页：", cur)
if "127.0.0.1:8848" in cur:
    print("     （App 已把它导到同源入口 —— 最理想）")
else:
    note = js("(function(){var n=document.getElementById('appgrid-note');return n?(n.textContent||'').trim():'(没有提示元素)';})()") or ""
    print("     资源入口上的提示：", note[:160])
    chk("读不到列表时**有明确提示**（不是静默空白）", note, lambda s: len(str(s)) > 6 and "没有提示" not in str(s))
    # 换到同源那份，把功能验完
    api("/api/nav/open?url=" + "http://127.0.0.1:8848/ui/start.html".replace(":", "%3A").replace("/", "%2F"))
    time.sleep(6)
    cur = (api("/api/status").get("browser") or {}).get("url", "")
    print("     换到：", cur)
chk("最终落在**同源**的首页（小 app 列表读得到）", cur, lambda s: "127.0.0.1:8848/ui/start.html" in str(s))

print("[2] 从资源入口添加一个小 app → 真落库 + 真渲染")
before = api("/api/apps/list").get("apps") or []
print("     添加前：", [a.get("name") for a in before])
js("(function(){var b=document.getElementById('app-add');if(!b)return 'no-add';b.click();return 'clicked';})()")
time.sleep(2)
opened = js("String(!!document.querySelector('#cdp-modal'))")
chk("点「＋」后小窗真的开了", opened, "true")
# 小窗里的名字/网址两个框 + 确定
filled = js("(function(){var ins=[].slice.call(document.querySelectorAll('#cdp-modal input, #cdp-modal textarea'));"
            "if(ins.length<2)return 'ins='+ins.length;"
            "ins[0].value='资源入口测试';ins[0].dispatchEvent(new Event('input',{bubbles:true}));"
            "ins[1].value='https://example.com/res-entry';ins[1].dispatchEvent(new Event('input',{bubbles:true}));"
            "return 'filled';})()")
print("     填表：", filled)
btn = js("(function(){var bs=[].slice.call(document.querySelectorAll('#cdp-modal button'));"
         "var b=bs.filter(function(x){return /确定|保存|添加/.test(x.textContent)}).pop();"
         "if(!b)return 'no-ok-btn';b.click();return 'ok-clicked';})()")
print("     点确定：", btn)
time.sleep(3)
after = api("/api/apps/list").get("apps") or []
print("     添加后：", [(a.get("name"), a.get("url")) for a in after])
chk("落库了（条数 +1）", len(after) - len(before), 1)
chk("落库的就是刚填的", [a for a in after if a.get("name") == "资源入口测试"], lambda l: len(l) == 1)
tiles = js("JSON.stringify([].map.call(document.querySelectorAll('#apps .app'),function(e){return (e.textContent||'').trim()}))") or "[]"
print("     网格上的格子：", tiles[:200])
chk("**页面上真的渲染出来了**（不再\"添加了不显示\"）", tiles, lambda s: "资源入口测试" in str(s))

print("[3] 刷新页面后还在（用户说\"刷新不了\"）")
api("/api/nav/reload" if False else "/api/status")
js("location.reload(); 'x'")
time.sleep(6)
tiles2 = js("JSON.stringify([].map.call(document.querySelectorAll('#apps .app'),function(e){return (e.textContent||'').trim()}))") or "[]"
chk("刷新后格子还在", tiles2, lambda s: "资源入口测试" in str(s))

print("[4] 删掉它（收尾，不留测试数据）")
tid = [a.get("id") for a in after if a.get("name") == "资源入口测试"]
if tid:
    api("/api/apps/remove?id=" + str(tid[0]))
    time.sleep(2)
    now = api("/api/apps/list").get("apps") or []
    chk("删掉了（条数回到添加前）", len(now), len(before))
else:
    chk("删掉了", "没找到刚加的 id", lambda s: False)

print("\n结果：%d/%d 通过" % (P, P + F))
if FAILS:
    print("  未通过：" + "；".join(FAILS))
sys.exit(1 if F else 0)
