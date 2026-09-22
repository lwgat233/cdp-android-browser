#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
tools/verify_home_grid.py —— 首页「小 app 网格」验收（议题 #1）

判据（四条都读**页内真实状态**与控制口真实返回，不看截图、不看"应该"）：
  1) 首页 start.html 里 `window.cdpNative` 是 object       —— 原生桥真的注入了
  2) `#apps` 里的 app 磁贴数 == 控制口 /api/apps/list 的个数 —— 数据真的渲染出来了
  3) 「＋ 添加应用」(#app-add) 存在且可见（宽高 > 0）        —— 添加入口在
  4) 页面没有"原生桥没响应 / 读不到小 app 列表"这类报错行   —— 没有再静默失败

用法（App 已在跑、adb 已连；模拟器/真机都行）：
    python3 tools/verify_home_grid.py
退出码：0 = 全过；非 0 = 有 FAIL（打印每条判据的实测值）。
"""
import json
import os
import re
import subprocess
import sys
import urllib.request

ADB = os.environ.get("ADB", os.path.expanduser("~/tools/android-sdk/platform-tools/adb"))
PORT = os.environ.get("CTRL_PORT", "8848")
DEVTOOLS_PORT = os.environ.get("DEVTOOLS_PORT", "9222")
HERE = os.path.dirname(os.path.abspath(__file__))
CDP = os.path.join(HERE, "cdp.mjs")

PROBE = (
    "JSON.stringify({"
    "url: location.href,"
    "native: typeof window.cdpNative,"
    "tiles: document.querySelectorAll('#apps .app:not(.add)').length,"
    "add: (function(){var e=document.getElementById('app-add');if(!e)return null;"
    "var r=e.getBoundingClientRect();return {w:Math.round(r.width),h:Math.round(r.height)};})(),"
    "note: (((document.getElementById('appgrid-note')||{}).textContent)||'').trim(),"
    "renderer: (function(){var b=document.getElementById('apps');return b&&b.getAttribute?b.getAttribute('data-renderer'):null;})()"
    "})"
)


def sh(*args, timeout=60):
    return subprocess.run(args, capture_output=True, text=True, timeout=timeout)


def main():
    results, fails = [], 0

    def check(name, ok, detail):
        nonlocal fails
        results.append((name, ok, detail))
        if not ok:
            fails += 1

    pid = sh(ADB, "shell", "pidof", "dev.cdp").stdout.strip()
    if not pid:
        print("FAIL App 没在跑（pidof dev.cdp 为空）——先启动 App 再验收")
        return 2
    sh(ADB, "forward", f"tcp:{PORT}", f"tcp:{PORT}")
    sh(ADB, "forward", "--remove", f"tcp:{DEVTOOLS_PORT}")
    fwd = sh(ADB, "forward", f"tcp:{DEVTOOLS_PORT}", f"localabstract:webview_devtools_remote_{pid}")
    if fwd.returncode != 0:
        print("FAIL devtools 转发建不起来：", fwd.stderr.strip()[:120])
        return 2

    # 控制口真值
    api_count, api_err = None, ""
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/api/apps/list", timeout=8) as r:
            api = json.loads(r.read().decode("utf-8"))
        api_count = len(api.get("apps") or [])
    except Exception as e:  # noqa: BLE001
        api_err = str(e)

    # 页内真值
    out = sh("node", CDP, "eval", "--target", "start.html", "--expr", PROBE, timeout=90).stdout
    page = None
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("{"):
            try:
                page = json.loads(line)
            except Exception:  # noqa: BLE001
                pass
    if page is None:
        print("FAIL 读不到首页（start.html）的页内状态，原始输出：")
        print(out[-600:])
        return 2

    check("首页已就绪且用 appgrid.js 渲染", page.get("renderer") == "appgrid.js",
          f"renderer={page.get('renderer')!r} url={(page.get('url') or '')[:60]}")
    check("原生桥 window.cdpNative 已注入", page.get("native") == "object",
          f"typeof window.cdpNative = {page.get('native')!r}（undefined 说明桥没挂到这个 WebView）")
    check("小 app 磁贴数与后端一致", api_count is not None and page.get("tiles") == api_count,
          f"页面磁贴={page.get('tiles')} / 控制口返回={api_count if api_count is not None else '读不到(' + api_err + ')'}")
    add = page.get("add") or {}
    check("「＋ 添加应用」入口在且可见", bool(add.get("w")) and bool(add.get("h")),
          f"#app-add 尺寸 = {add.get('w')}x{add.get('h')}")
    check("页面上没有取数失败提示", not re.search(r"原生桥没响应|读不到小 app 列表", page.get("note") or ""),
          f"note={page.get('note')!r}")

    print("== 首页小 app 网格验收（议题 #1） ==")
    for name, ok, detail in results:
        print(("  PASS  " if ok else "  FAIL  ") + name + " | " + detail)
    print(f"-- {len(results) - fails}/{len(results)} 通过")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
