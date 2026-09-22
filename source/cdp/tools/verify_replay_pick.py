#!/usr/bin/env python3
"""回放"跑错脚本 + 报错"的验收（用户报：点运行、跑的是别的一条、还报错）。

用户原话（大意）："那个录制功能不是有回放吗？点击运行……运行当前搞错了，可能是时间的，然后对这个报错。"

查出来的因果（都是读回来的事实，不是猜的）：
  ① 进「录制」栏目时**没有刷新**脚本下拉框 → 下拉是空的 → 点「回放这条脚本」什么也跑不了（提示还被 old 文案写成「先选一条脚本」）；
  ② 去「脚本」栏目刷过之后，下拉框按登记顺序列：第一条是**内置用户脚本**（0 步），录制脚本排在最后 →
     点「运行脚本」跑的是用户脚本，后端回 `脚本没有步骤`（用户看到的就是这句"报错"）；
  ③ 「运行当前队列」和「运行脚本」两个键并排、文案也分不清谁是谁。

这次改完要卡住的四件事：
  [1] 进录制栏目就把下拉框刷出来（非空）
  [2] 下拉框**只列能回放的**（录制/队列），**默认选中最新那条**（＝刚录的那条），不列用户脚本
  [3] 用下拉框里选中的 id 真跑一次回放：拿到步骤结果、**不是** "脚本没有步骤"
  [4] 用户脚本走回放这条路的报错要能看懂（指向"这是用户脚本、该去脚本栏目运行"）
"""
import json
import os
import subprocess
import sys
import time

ROOT = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
PROBE_ENV = dict(os.environ, PATH="/home/lwgat/.local/bin:" + os.environ.get("PATH", ""))
checks = []


def ui(expr, timeout=40):
    r = subprocess.run(["node", "tools/uiprobe.mjs", "--timeout", "9000", "--match", "ui/index.html", expr],
                       capture_output=True, text=True, cwd=ROOT, timeout=timeout, env=PROBE_ENV)
    out = [l for l in (r.stdout or "").strip().splitlines() if l.strip()]
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
    # 兜底：uiprobe 对**普通字符串**是原样打印（不是 JSON），上面解析不了就取最后一行 ——
    # 少了这一句会把 "2026-09-20-步骤列表-4" 这种值读成 None，冤枉被测代码。
    return out[-1] if out else None


def api(path, timeout=60):
    try:
        r = subprocess.run(["curl", "-s", "--max-time", str(timeout), HTTP + path],
                           capture_output=True, text=True, timeout=timeout + 10)
        return json.loads(r.stdout)
    except Exception:
        return {}


def chk(name, got, want):
    ok = want(got) if callable(want) else got == want
    checks.append((name, bool(ok), str(got)[:240]))
    print(("  PASS " if ok else "  FAIL ") + name + "  → " + str(got)[:240])
    return ok


def main():
    print("[0] 先回到网页栏目，再进录制栏目（模拟用户从别处进来）")
    api("/api/ui/open?tab=page")
    time.sleep(2)
    api("/api/ui/open?tab=rec")
    time.sleep(3)

    print("[1] 进录制栏目后，回放下拉框要有内容（以前是空的）")
    opts = ui("JSON.stringify(Array.prototype.map.call(document.querySelectorAll('#r-script option'),"
              "function(o){return {v:o.value,t:o.textContent};}))") or []
    sel = ui("(function(){var s=document.getElementById('r-script');return JSON.stringify({v:s?s.value:null,"
             "disabled:s?s.disabled:null});})()") or {}
    chk("下拉框不是空的（有录制脚本可选）", len(opts), lambda n: n >= 1)
    print("    选项：" + " ｜ ".join((o.get("t") or "") for o in opts))
    chk("下拉框可用（没被置灰）", sel.get("disabled"), False)

    print("[2] 只列能回放的脚本 + 默认选中最新那条（＝刚录的）")
    allsc = (api("/api/scripts") or {}).get("list") or []
    playable = [s for s in allsc if s.get("kind") in ("recording", "queue")]
    playable.sort(key=lambda s: s.get("updated") or 0, reverse=True)
    want = playable[0]["id"] if playable else None
    names = [o.get("t") or "" for o in opts]
    chk("选项里没有内置用户脚本", [n for n in names if "用户脚本" in n], [])
    chk("选项数量 = 可回放脚本数量", len(opts), len(playable))
    chk("默认选中最新那条（%s）" % want, sel.get("v"), want)
    newest = playable[0] if playable else {}
    chk("选项文案带步数与时间（认得出是哪条）",
        (names[0] if names else ""), lambda t: t and (str(newest.get("steps", 0)) + " 步") in t and ":" in t)

    print("[3] 用下拉框里选中的 id 真跑一次回放（要走通，不能是「脚本没有步骤」）")
    rep = api("/api/replay?script=%s" % sel.get("v"), timeout=90)
    steps_out = rep.get("steps") or []
    chk("回放接口能跑", rep.get("ok"), True)
    chk("回来的不是「脚本没有步骤」", rep.get("error") or "", lambda e: "没有步骤" not in e)
    chk("给了逐步结果（跑的就是这条脚本）", len(steps_out), lambda n: n >= 1)
    if steps_out:
        print("    第 1 步：" + json.dumps(steps_out[0], ensure_ascii=False)[:160])

    print("[4] 用户脚本走回放这条路的报错要能看懂（指向「这是用户脚本」）")
    us = [s for s in allsc if s.get("kind") == "userscript"]
    if us:
        rep2 = api("/api/replay?script=%s" % us[0]["id"], timeout=60)
        err = rep2.get("error") or ""
        chk("明确说这是用户脚本（而不是干巴巴一句「脚本没有步骤」）",
            err, lambda e: "用户脚本" in e and "录制脚本" in e)
        print("    报错文案：" + err[:120])
    else:
        chk("有用户脚本可用来验证报错文案", False, True)

    bad = [c for c in checks if not c[1]]
    print("\n结果：%d/%d 通过" % (len(checks) - len(bad), len(checks)))
    for c in bad:
        print("  未通过：" + c[0] + " → " + c[2])
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
