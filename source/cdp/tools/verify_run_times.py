#!/usr/bin/env python3
"""「跑几次」的验收 —— 用户要的两条：
   ① 回放脚本要有"运行多少次"；
   ② 运行步骤（单步执行 / 跑当前队列）也要有"运行多少次"。

两条硬规矩（这一轮踩出来的）：
  · **回放会收起控制台**（页面重载）→ 结果不能存在页面变量里读；一律读
    `/api/play/last`（最近一次运行的完整记录）与 `/api/scripts`（脚本累计 stats）。
  · 界面输入框读回来是**字符串**，断言要比字符串（"1" 而不是 1），否则假红。

卡的点：
  [1] 界面有「次数」输入框，默认 1；
  [2] 次数非法（0 / abc / 空）→ 按 1 次跑，并把生效值写回框里；
  [3] 回放脚本 3 次（走界面按钮）→ last.times=3、runs 长度 3、脚本累计 runs +3；
  [4] 「▶ 跑当前队列」2 次（走界面按钮）→ last.times=2、runs 长度 2；
  [5] 单步执行「跑几次=3」（走步骤小窗的按钮）→ last.times=3、okTimes=3、总耗时 ≥ 3×200ms；
  [6] 上限：times=999 → 夹到 50。
"""
import json
import os
import subprocess
import sys
import urllib.parse
import time

ROOT = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
ENV = dict(os.environ, PATH="/home/lwgat/.local/bin:" + os.environ.get("PATH", ""))
checks = []


def ui(expr, timeout=45):
    r = subprocess.run(["node", "tools/uiprobe.mjs", "--timeout", "12000", "--match", "ui/index.html", expr],
                       capture_output=True, text=True, cwd=ROOT, timeout=timeout, env=ENV)
    out = [l for l in (r.stdout or "").strip().splitlines() if l.strip()]
    if not out:
        return None
    for line in reversed(out):
        try:
            v = json.loads(line)
        except Exception:
            continue
        if isinstance(v, str):
            try:
                return json.loads(v)
            except Exception:
                return v
        return v
    return out[-1]


def api(path, timeout=180):
    try:
        r = subprocess.run(["curl", "-s", "--max-time", str(timeout), HTTP + path],
                           capture_output=True, text=True, timeout=timeout + 15)
        return json.loads(r.stdout)
    except Exception:
        return {}


def chk(name, got, want):
    ok = want(got) if callable(want) else got == want
    checks.append((name, bool(ok), str(got)[:240]))
    print(("  PASS " if ok else "  FAIL ") + name + "  → " + str(got)[:240])
    return ok


def js(expr):
    return ui("(function(){" + expr + "return 'ok';})()")


def times_in():
    # JSON.stringify：不然 "1" 会以裸文本回来、被解析成数字 1 → 字符串断言假红
    return ui("(function(){var e=document.getElementById('r-times');return JSON.stringify(e?String(e.value):null);})()")


def set_times(n):
    js("var e=document.getElementById('r-times');e.value='%s';" % n)


def last():
    return (api("/api/play/last") or {}).get("run") or {}


def open_tab(tab, wait=2.5):
    api("/api/ui/open?tab=" + tab)
    time.sleep(wait)


def wait_last(times, since, timeout=60):
    """等"这一轮"跑完：finishedAt 变了 **且** times 对得上（避免读到上一轮的结果）"""
    t0 = time.time()
    while time.time() - t0 < timeout:
        r = last()
        if (r.get("finishedAt") or 0) > (since or 0) and r.get("times") == times:
            return r
        time.sleep(1.2)
    return last()


def script_of(sid):
    for s in ((api("/api/scripts") or {}).get("list") or []):
        if s.get("id") == sid:
            return s
    return {}


def main():
    print("[0] 准备：清空 → 插 2 步（等待 200ms ×2）→ 跑一次队列（顺便存出队列脚本）")
    api("/api/record?action=stop")
    api("/api/record/clear")
    open_tab("rec")
    for _ in range(2):
        api("/api/record/insert?step=" + urllib.parse.quote(json.dumps({"t": "wait", "ms": 200})))
    chk("队列里有 2 步", len((api("/api/record/live").get("steps") or [])), 2)
    api("/api/run?what=queue&times=1", timeout=90)
    recs = [s for s in ((api("/api/scripts") or {}).get("list") or []) if s.get("kind") in ("recording", "queue")]
    recs.sort(key=lambda s: s.get("updated") or 0, reverse=True)
    sid = recs[0]["id"] if recs else None
    runs_before = (script_of(sid).get("runs", 0)) if sid else 0        # 列表条目顶层的 runs 就是累计运行次数
    print("    脚本 %s；跑之前累计 runs=%s" % (sid, runs_before))

    print("[1] 界面有「次数」输入框，默认 1")
    open_tab("page", 1.5)
    open_tab("rec")
    # uiprobe 对"看起来像数字的字符串"会原样打成 1 → 断言用 str() 比，别比字面量类型
    chk("次数框在、默认 1", str(times_in()), "1")

    print("[2] 次数非法（0 / abc / 空）→ 按 1 次跑，并把生效值写回框里")
    for bad in ("0", "abc", ""):
        set_times(bad)
        since = last().get("finishedAt") or 0
        js("document.getElementById('r-replay').click();")
        r = wait_last(1, since)
        chk("填「%s」→ 框里规范成 1，且真跑 1 次" % bad,
            str(times_in()) == "1" and (r.get("finishedAt") or 0) > since, True)

    print("[3] 回放脚本 3 次（走界面按钮）")
    open_tab("page", 1.5)
    open_tab("rec")
    before3 = script_of(sid).get("runs", 0)
    set_times("3")
    since3 = last().get("finishedAt") or 0
    js("document.getElementById('r-replay').click();")
    r3 = wait_last(3, since3)
    chk("last.times=3", r3.get("times"), 3)
    chk("runs 里有 3 次记录", len(r3.get("runs") or []), 3)
    chk("每次都有 ok 且 ms>0", [(x.get("ok"), (x.get("ms") or 0) > 0) for x in (r3.get("runs") or [])],
        lambda v: len(v) == 3 and all(a is True and b for a, b in v))
    after3 = script_of(sid).get("runs", 0)
    chk("脚本累计 runs 增加 3（%s → %s）" % (before3, after3), after3,
        lambda n: n == before3 + 3)

    print("[4] 「▶ 跑当前队列」2 次（走界面按钮）")
    open_tab("page", 1.5)
    open_tab("rec")
    set_times("2")
    since4 = last().get("finishedAt") or 0
    js("document.getElementById('r-runall').click();")
    r4 = wait_last(2, since4)
    chk("last.times=2", r4.get("times"), 2)
    chk("runs 里有 2 次记录", len(r4.get("runs") or []), 2)

    print("[5] 单步执行「跑几次=3」（走步骤小窗里的按钮，不是直接调 op）")
    open_tab("rec")
    js("document.querySelectorAll('#r-steps .row-item')[0].click();")
    time.sleep(1.5)
    chk("小窗里有「跑几次」字段", ui("!!document.getElementById('fld-runs')"), True)
    js("document.getElementById('fld-runs').value='3';")
    since5 = last().get("finishedAt") or 0
    js("Array.prototype.filter.call(document.querySelectorAll('#cdp-modal .sheet-actions button'),"
       "function(x){return x.textContent.indexOf('只执行这一步')>=0;})[0].click();")
    r5 = wait_last(3, since5)
    chk("last.times=3 / okTimes=3", [r5.get("times"), r5.get("okTimes")], [3, 3])
    chk("3 次记录都有 ms>0", [(x.get("ms") or 0) > 0 for x in (r5.get("runs") or [])],
        lambda v: len(v) == 3 and all(v))
    chk("总耗时 ≥ 3×200ms（真跑了三遍）", r5.get("ms"), lambda n: n is not None and n >= 600)

    print("[6] 上限：times=999 → 夹到 50")
    r6 = api("/api/run?what=one&i=0&times=999", timeout=180)
    chk("999 被夹到 50 次", r6.get("times"), 50)
    chk("确实跑了 50 次（记录条数）", len(r6.get("runs") or []), 50)

    bad = [c for c in checks if not c[1]]
    print("\n结果：%d/%d 通过" % (len(checks) - len(bad), len(checks)))
    for c in bad:
        print("  未通过：" + c[0] + " → " + c[2])
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
