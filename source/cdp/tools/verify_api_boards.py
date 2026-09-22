#!/usr/bin/env python3
"""两个"一个按键都没有"的板块补完后的验收（覆盖自检报出来的缺口 N-01 / N-02）。

用户的要求：**没有人机交互就是开发不足**。所以这两个板块现在必须真的能点出东西来：
  · 接口清单 `api-endpoints`：看清单 / 复制全部 / 试一下控制口 —— 三个键都要有可读的反馈；
  · 外部 CDP `api-cdp`：看状态 / 复制调试地址 —— 状态里要含**真的 pid**（不是占位符）。

判据：控制台 DOM 里状态行的文本（读回来）+ 接口返回里的 pid 与当前进程 pid 一致。
"""
import json
import subprocess
import sys
import time

ROOT = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
ADB = "/home/lwgat/tools/android-sdk/platform-tools/adb"
SER = "emulator-5554"
checks = []


def ui(expr, timeout=40):
    r = subprocess.run(["node", "tools/uiprobe.mjs", "--timeout", "8000", "--match", "ui/index.html",
                        expr], capture_output=True, text=True, cwd=ROOT, timeout=timeout)
    out = [l for l in (r.stdout or "").strip().splitlines() if l.strip()]
    if not out:
        print("      [probe 没输出] stderr=" + (r.stderr or "")[-160:])
        return None
    for line in reversed(out):
        try:
            v = json.loads(line)
        except Exception:
            continue
        if isinstance(v, str):        # 表达式里 JSON.stringify 过一层，再解一次
            try:
                return json.loads(v)
            except Exception:
                return v
        return v
    return out[-1]


def api(path, timeout=40):
    try:
        r = subprocess.run(["curl", "-s", "--max-time", str(timeout), HTTP + path],
                           capture_output=True, text=True, timeout=timeout + 10)
        return json.loads(r.stdout)
    except Exception:
        return {}


def chk(name, got, want):
    ok = want(got) if callable(want) else got == want
    checks.append((name, bool(ok), str(got)[:200]))
    print(("  PASS " if ok else "  FAIL ") + name + "  → " + str(got)[:200])
    return ok


def click(js):
    return ui("(function(){" + js + "return 'ok';})()")


def main():
    print("[0] 打开接口栏目")
    api("/api/ui/open?tab=api")
    time.sleep(2.5)
    st = ui("JSON.stringify({epBtns:['ep-list','ep-copy','ep-probe'].filter(function(i){return !!document.getElementById(i);}).length,"
            "cdpBtns:['cdp-state','cdp-copy'].filter(function(i){return !!document.getElementById(i);}).length})")
    chk("接口清单板块有 3 个按键", (st or {}).get("epBtns"), 3)
    chk("外部 CDP 板块有 2 个按键", (st or {}).get("cdpBtns"), 2)

    print("[1] 「看清单」→ 清单真的列出来（并给出条数）")
    click("document.getElementById('ep-list').click();")
    time.sleep(1.5)
    got = ui("JSON.stringify({out:(document.getElementById('ep-out')||{}).textContent||'',"
             "list:(document.getElementById('a-list')||{}).textContent||''})")
    chk("状态行报出条数", (got or {}).get("out"), lambda s: s and "个接口" in s)
    chk("清单正文里有真正的接口路径", (got or {}).get("list"), lambda s: s and s.count("/api/") >= 20)

    print("[2] 「试一下控制口」→ 报出运行状态与绑定")
    click("document.getElementById('ep-probe').click();")
    time.sleep(1.5)
    got2 = ui("(document.getElementById('ep-out')||{}).textContent||''")
    chk("状态行里能看到控制口状态", got2, lambda s: s and ("控制口" in s) and ("本机" in s))

    print("[3] 外部 CDP「看状态」→ 必须是**真的 pid**（跟当前进程一致）")
    pid = subprocess.run(["bash", "-lc", "%s -s %s shell pidof dev.cdp" % (ADB, SER)],
                         capture_output=True, text=True).stdout.strip()
    click("document.getElementById('cdp-state').click();")
    time.sleep(1.5)
    got3 = ui("(document.getElementById('cdp-out')||{}).textContent||''")
    chk("状态行里有 pid", got3, lambda s: s and "pid=" in s)
    chk("pid 跟设备上真实进程一致（%s）" % pid, got3, lambda s: pid and pid in (s or ""))

    print("[4] 「复制调试地址」→ 给出可用的 forward 命令")
    click("document.getElementById('cdp-copy').click();")
    time.sleep(1.5)
    got4 = ui("(document.getElementById('cdp-out')||{}).textContent||''")
    chk("给出了 forward 命令且带真实 pid", got4,
        lambda s: s and "webview_devtools_remote_" in s and (pid in s if pid else True))

    print("[5] 覆盖自检：这两个功能不再是缺口")
    mod = ui("JSON.stringify({v:window.__cdpModules().violations, d:window.__cdpModules().duplicateIds,"
             "r:window.__cdpRegistry().problems})")
    chk("模块/登记表自检干净", [(mod or {}).get("v"), (mod or {}).get("d"), (mod or {}).get("r")], [[], [], []])

    bad = [c for c in checks if not c[1]]
    print("\n结果：%d/%d 通过" % (len(checks) - len(bad), len(checks)))
    for c in bad:
        print("  未通过：" + c[0] + " → " + c[2])
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
