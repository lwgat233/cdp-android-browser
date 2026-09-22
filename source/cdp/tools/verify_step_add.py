#!/usr/bin/env python3
"""只验这一轮改的东西：添加步骤能不能加进去 + 界面瘦身（去掉 ⋯ 提示、说明文字）。

用户路径（原话）："添加步骤，点击添加之后弹出信息，然后比如等待几秒，停下几秒之类的，
他没有被协调到步骤里面。这个按键就是失效的。"

所以这里只做三件事：
  1) 走界面：点列表底部「＋ 添加步骤」→ 选「等待 / 停顿几秒」→ 填秒数 → 保存 → 队列里要有那一步、列表里要立刻看到；
  2) 参数容错：填 "3秒" 也认；填 "abc" 要有**看得见**的提示（不能静默丢）；
  3) 界面规矩：步骤行不留 ⋯ 键（只有 ▲▼）；加号行文字就是"添加步骤"；信息行不再有 ⋯ 提示符。
"""
import json
import subprocess
import sys
import time

ROOT = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
checks = []


def ui(expr, timeout=40):
    r = subprocess.run(["node", "tools/uiprobe.mjs", "--timeout", "9000", "--match", "ui/index.html", expr],
                       capture_output=True, text=True, cwd=ROOT, timeout=timeout)
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
    return None


def api(path):
    try:
        r = subprocess.run(["curl", "-s", "--max-time", "30", HTTP + path], capture_output=True, text=True, timeout=40)
        return json.loads(r.stdout)
    except Exception:
        return {}


def steps():
    return (api("/api/record/live") or {}).get("steps") or []


def click(js):
    return ui("(function(){" + js + "return 'ok';})()")


def chk(name, got, want):
    ok = want(got) if callable(want) else got == want
    checks.append((name, bool(ok), str(got)[:160]))
    print(("  PASS " if ok else "  FAIL ") + name + "  → " + str(got)[:160])
    return ok


def pick(label):
    """在弹出的小窗里按文字点一颗键（恢复原始值 / 确定 / 删掉这一步…）"""
    return click("var b=Array.prototype.filter.call(document.querySelectorAll('#cdp-modal .sheet-actions button'),"
                 "function(x){return x.textContent.indexOf('%s')>=0;})[0];if(!b)return 'nobtn';b.click();" % label)


def pickopt(label):
    """选步骤形态：现在是一行行可点的行（data-option），不再挤成一排按键"""
    return click("var r=Array.prototype.filter.call(document.querySelectorAll('#cdp-modal [data-option]'),"
                 "function(x){return x.getAttribute('data-option').indexOf('%s')>=0;})[0];if(!r)return 'noopt';r.click();" % label)


def main():
    print("[0] 清空 + 打开录制栏目")
    api("/api/record/clear")
    api("/api/ui/open?tab=rec")
    time.sleep(2.5)

    print("[1] 列表底部那个加号：文字、能点")
    got = ui("JSON.stringify({txt:((document.getElementById('r-add')||{}).textContent||''),"
             "kind:((document.getElementById('r-add')||{}).getAttribute&&document.getElementById('r-add').getAttribute('data-kind'))})")
    chk("加号行文字就是「＋添加步骤」（不再带一长串说明）", (got or {}).get("txt"), lambda t: t and t in ("＋添加步骤", "＋ 添加步骤"))
    chk("它是列表底部那一行（data-kind=add）", (got or {}).get("kind"), "add")

    print("[2] 点加号 → 选「等待」→ 填 2.5 → 保存：队列与列表都要有")
    click("document.getElementById('r-add').click();")
    time.sleep(1)
    chk("形态清单弹出来了", ui("!!document.getElementById('cdp-modal')"), True)
    pickopt("等待")                   # 「等待 / 停顿几秒」
    time.sleep(1)
    chk("切到填参数的表单（字段是秒）", ui("JSON.stringify(Array.prototype.map.call(document.querySelectorAll('#cdp-modal [data-field]'),function(e){return e.getAttribute('data-field');}))"),
        lambda f: f and "sec" in f)
    click("document.getElementById('fld-sec').value='2.5';document.getElementById('cdp-modal-ok').click();")
    time.sleep(1.5)
    st = steps()
    chk("队列里真的多了这一步（2500 毫秒）", [len(st), (st[0] or {}).get("t"), (st[0] or {}).get("ms")] if st else [0, None, None],
        [1, "wait", 2500])
    chk("列表里立刻看得到（没等轮询）",
        ui("JSON.stringify({txt:((document.getElementById('r-steps')||{}).textContent||'')})"),
        lambda o: o and o.get("txt") and "等待" in o["txt"] and "添加步骤" in o["txt"])

    print("[3] 容错：填 “3秒” 也认")
    click("document.getElementById('r-add').click();")
    time.sleep(0.8)
    pickopt("等待")
    time.sleep(0.8)
    click("document.getElementById('fld-sec').value='3秒';document.getElementById('cdp-modal-ok').click();")
    time.sleep(1.5)
    st = steps()
    chk("“3秒”被认成 3000 毫秒", (st[1] or {}).get("ms") if len(st) > 1 else None, 3000)

    print("[4] 容错：填 “abc” 要给看得见的提示，不能静默丢")
    n0 = len(steps())
    click("document.getElementById('r-add').click();")
    time.sleep(0.8)
    pickopt("等待")
    time.sleep(0.8)
    click("document.getElementById('fld-sec').value='abc';document.getElementById('cdp-modal-ok').click();")
    time.sleep(1.2)
    tip = ui("JSON.stringify({title:((document.querySelector('#cdp-modal .sheet-head b')||{}).textContent||''),body:((document.querySelector('#cdp-modal .sheet-body')||{}).textContent||'')})")
    chk("弹了「参数不对」的提示", (tip or {}).get("title"), lambda t: t and "参数" in t)
    chk("队列没有偷偷多一条", len(steps()), n0)

    print("[5] 界面规矩：步骤行不留 ⋯、点行弹出小窗（信息+操作）")
    click("var r=document.querySelector('#r-steps .row-item[data-kind=step]');r.click();")
    time.sleep(1.3)
    m = ui("JSON.stringify(CDPUI.Modal.scan())")
    chk("步骤行上只有 ▲▼（没有 ⋯；且第一行不该有 ▲）", ui("(function(){var r=document.querySelector('#r-steps .row-item[data-kind=step]');return JSON.stringify(Array.prototype.map.call(r.querySelectorAll('button'),function(b){return b.textContent;}));})()"),
        lambda v: v and all(x in ("▲", "▼") for x in v) and "▲" not in v)
    chk("点行弹出的是**可编辑**小窗（类型只读 + 参数可改）",
        [(m or {}).get("readonly"), (m or {}).get("editable")], lambda v: v[0] == 1 and v[1] >= 1)
    chk("底部有「恢复原始值」和「确定」",
        [b for b in ((m or {}).get("buttons") or []) if b in ("恢复原始值", "确定")],
        lambda v: v and len(v) == 2)
    chk("还有真做事的键（执行 / 删）",
        [b for b in ((m or {}).get("buttons") or []) if ("执行" in b or "删" in b)], lambda v: bool(v))

    print("[5b] 改这一步也用秒：把 2.5 秒改成 7 秒（点行就是编辑，不再开两层窗）")
    click("document.querySelector('#r-steps .row-item[data-kind=step]').click();")
    time.sleep(1.4)
    chk("编辑表单里带出当前秒数 2.5", ui("(function(){var e=document.getElementById('fld-sec');return e?e.value:null;})()"),
        lambda v: v is not None and abs(float(v) - 2.5) < 0.001)
    click("document.getElementById('fld-sec').value='7';document.getElementById('cdp-modal-ok').click();")
    time.sleep(1.5)
    st2 = steps()
    chk("改完队列里是 7000 毫秒", (st2[0] or {}).get("ms") if st2 else None, 7000)

    print("[6] 信息列表里不再有 ⋯ 提示符")
    api("/api/ui/open?tab=net")
    time.sleep(2)
    chk("界面里 .more-cue 数量为 0", ui("document.querySelectorAll('.more-cue').length"), 0)

    bad = [c for c in checks if not c[1]]
    print("\n结果：%d/%d 通过" % (len(checks) - len(bad), len(checks)))
    for c in bad:
        print("  未通过：" + c[0] + " → " + c[2])
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
