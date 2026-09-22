#!/usr/bin/env python3
"""控制口安全（本轮新增）的验收：绑定范围 / 访问令牌 / 敏感接口开关。

为什么这么测：
  · "本机免令牌"和"局域网必须带令牌"是两条相反的要求，必须分别验；
  · 模拟器自己的地址是 10.0.2.15 —— 让 **App 的浏览器**去访问 10.0.2.15:8848，
    服务端看到的来源地址就不是 127.0.0.1，正好当"局域网里的另一台设备"用（不用真开第二台机器）；
  · 判据读回来的都是响应正文（403 里的原因 / 200 里的 ok），不是"没报错"。
测完把状态恢复成安全态：只绑本机 + 敏感接口关闭。
"""
import json
import re
import subprocess
import sys
import time

ROOT = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
LAN_IP = "10.0.2.15"          # 模拟器自己在容器网络里的地址（= 服务端眼里的"非本机"）
checks = []


def api(path, timeout=40, raw=False):
    """取一次接口结果；空回包/解析失败时重试一次（实测：切换绑定范围的那一瞬间会偶发空回包）。

    注意：subprocess 的 text=True 会做通用换行转换，CRLF 到手里已经是 LF 了 ——
    按 CRLF+CRLF 切会切不开（正文里带着响应头，json 解析必然失败）。本轮实测踩过这个坑：
    服务器回的其实是标准 CRLF，是测试脚本自己切错了，所以这里两种换行都容忍。
    """
    last = None
    for _ in range(2):
        r = subprocess.run(["curl", "-s", "-i", "--max-time", str(timeout), HTTP + path],
                           capture_output=True, text=True, timeout=timeout + 10)
        if raw:
            return r.stdout
        body = re.split(r"\r?\n\r?\n", r.stdout, maxsplit=1)[-1]
        try:
            d = json.loads(body)
            if isinstance(d, dict):
                return d
        except Exception:
            last = {"ok": False, "raw": body[:200]}
        time.sleep(0.5)
    return last or {"ok": False}


def browser_text(url, wait=6, timeout=90):
    """让 App 自己的浏览器打开一个地址，读回页面文字（用来当"局域网里的另一台设备"）"""
    subprocess.run(["curl", "-s", "--max-time", "30", HTTP + "/api/goto?url=" + url],
                   capture_output=True, text=True, timeout=40)
    time.sleep(wait)
    # 注意：导航之后 target 的 URL 就是那个 JSON 地址了（不再是 start.html）——按 IP 找才对；
    # 而且 WebView 顶层导航到 application/json 时不一定会渲染出 innerText，所以 textContent 一起看。
    r = subprocess.run(["node", "tools/uiprobe.mjs", "--timeout", "8000", "--match", LAN_IP,
                        "String((document.body&&document.body.innerText)||document.documentElement.textContent||'').slice(0,400)"],
                       capture_output=True, text=True, cwd=ROOT, timeout=timeout)
    return (r.stdout or "").strip()


def chk(name, got, want):
    ok = want(got) if callable(want) else got == want
    checks.append((name, bool(ok), str(got)[:200]))
    print(("  PASS " if ok else "  FAIL ") + name + "  → " + str(got)[:200])
    return ok


def main():
    # 先把状态归位：上一轮如果在中途崩了（实测崩过两次），App 会被留在"绑局域网 + 敏感接口已开"，
    # 那后面的"默认态"断言全是假红 —— 同一类坑已经写进 skill（先把残留状态归位再断言）。
    print("[0] 状态归位（敏感接口关闭 + 只绑本机）")
    api("/api/security/sensitive?on=0")
    api("/api/http?action=start&lan=0")
    time.sleep(1.5)

    print("[1] 默认态：只绑本机 + 本机免令牌")
    st = api("/api/status")
    chk("控制口在跑，绑的是 127.0.0.1", (st.get("http") or {}).get("bind"), "127.0.0.1")
    tok = api("/api/security/token")
    tou = (tok.get("token") or "")   # 后面"从局域网带令牌"要用到
    chk("本机能取到令牌（只在本机可用）", tou, lambda t: len(t) >= 16)
    chk("安全状态里的判决是「只绑本机」", api("/api/security").get("verdict"), lambda v: v and "只绑本机" in v)

    print("[2] 绑到局域网（用 App 的浏览器访问 10.0.2.15，当「局域网里的另一台设备」）")
    api("/api/http?action=start&lan=1")
    time.sleep(1.5)
    chk("绑定范围变成 0.0.0.0", (api("/api/status").get("http") or {}).get("bind"), "0.0.0.0")
    noTok = browser_text("http://%s:8848/api/status" % LAN_IP)
    chk("非本机不带令牌 → 被拒（403 且说明要令牌）", noTok, lambda t: "令牌" in t and ("403" in t or "Forbidden" in t or "error" in t))
    withTok = browser_text("http://%s:8848/api/status?t=%s" % (LAN_IP, tou))
    chk("非本机带对令牌 → 放行（看得到 App 的版本）", withTok, lambda t: "CDP" in t or "version" in t)
    tokFromLan = browser_text("http://%s:8848/api/security/token?t=%s" % (LAN_IP, tou))
    # 判据升级成"响应里根本没有令牌"：无论被哪一道门拦住（敏感接口默认关 / 只允许本机取），
    # 只要没把令牌吐出去就算过 —— 这比匹配某一句文案更结实。
    chk("令牌本身不外带（非本机取令牌被拒，且响应里没有令牌）", tokFromLan,
        lambda t: (tou not in t) and ("ok\":false" in t or "error" in t))

    print("[3] 敏感接口：局域网下默认关闭，显式打开才放行")
    sen = browser_text("http://%s:8848/api/vault/state?t=%s" % (LAN_IP, tou))
    chk("未打开时：敏感接口被拒并说明原因", sen, lambda t: "敏感接口" in t and "默认关闭" in t)
    api("/api/security/sensitive?on=1")
    time.sleep(0.6)
    chk("打开后安全状态里是「已打开」", api("/api/security").get("sensitiveLan"), True)
    sen2 = browser_text("http://%s:8848/api/vault/state?t=%s" % (LAN_IP, tou))
    chk("打开后：敏感接口放行（返回 vault 的状态 JSON）", sen2, lambda t: "locked" in t or "ok" in t)

    print("[4] 安全头 + 被拒计数")
    head = api("/api/status", raw=True)
    chk("响应带 X-Content-Type-Options: nosniff", head, lambda h: "nosniff" in h.lower())
    chk("响应带 Referrer-Policy: no-referrer", head, lambda h: "no-referrer" in h.lower())
    rej = (api("/api/security").get("rejected") or 0)
    chk("被拒次数有记录（说明真的拒过）", rej, lambda n: int(n or 0) >= 3)

    print("[5] 控制台界面的 CSP 生效（页面里 eval 被拒），但界面本身照常工作")
    # 为什么不用 eval 验 CSP：CDP 的 Runtime.evaluate 是特权路径，**不受页面 CSP 约束**，
    # 用它 eval 一定"成功"，得到的是假结论（实测踩到）。改成让页面自己去取一个 CSP 不允许的资源，
    # 听 securitypolicyviolation 事件 —— 这是页面级行为，CSP 真的生效才会响。
    subprocess.run(["node", "tools/uiprobe.mjs", "--timeout", "8000",
                    "(function(){window.__cspV=null;document.addEventListener('securitypolicyviolation',function(e){window.__cspV={d:e.violatedDirective,b:e.blockedURI};});var i=new Image();i.src='http://example.com/csp-probe.png';return 'armed';})()"],
                   capture_output=True, text=True, cwd=ROOT, timeout=60)
    time.sleep(1.5)
    expr = ("JSON.stringify({v:window.__cspV,csp:!!document.querySelector(\"meta[http-equiv='Content-Security-Policy']\"),"
            "tabs:document.querySelectorAll('#tabs button.tabBtn').length})")
    got = {}
    for _ in range(3):      # 探针偶发空回包（页面正忙）→ 重试，别让一次空回包把整轮打断
        r = subprocess.run(["node", "tools/uiprobe.mjs", "--timeout", "8000", expr],
                           capture_output=True, text=True, cwd=ROOT, timeout=60)
        try:
            got = json.loads((r.stdout or "").strip() or "{}")
            if got:
                break
        except Exception:
            got = {}
        print("   （探针空回包，重试；stderr=" + (r.stderr or "")[:80] + "）")
        time.sleep(1.0)
    chk("CSP 真的拦住了页面里对外部图片的请求（收到 violation 事件）", (got.get("v") or {}).get("b", ""),
        lambda b: "example.com" in b)
    chk("CSP 的 meta 就在页面上", got.get("csp"), True)
    chk("界面照常（24 个栏目还在）", got.get("tabs"), 24)

    print("[6] 收尾：恢复安全态（只绑本机 + 敏感接口关闭）")
    api("/api/security/sensitive?on=0")
    api("/api/http?action=start&lan=0")
    time.sleep(1)
    st2 = api("/api/status").get("http") or {}
    chk("已恢复：只绑 127.0.0.1", st2.get("bind"), "127.0.0.1")
    chk("已恢复：敏感接口关闭", api("/api/security").get("sensitiveLan"), False)

    bad = [c for c in checks if not c[1]]
    print("\n结果：%d/%d 通过" % (len(checks) - len(bad), len(checks)))
    for c in bad:
        print("  未通过：" + c[0] + " → " + c[2])
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
