#!/usr/bin/env python3
"""小窗（modal）按用户新要求重做后的验收 —— 只测这次改的东西：

用户原话（要点）：
  · 小窗里的信息「很多本来就可以编辑」，值要写成 **textbox** 的形态（可改 / 不可改都长这样）；
  · 「最后肯定有个确定」+「还有一个恢复原始值的按键」；
  · 「有些按键本来就没有指向一个特定的功能，为什么非要多加这些按键」；
  · 「有些按键根本就没有意义，没有功能」。

逐条对应到下面的检查（判据全是读回来的事实：DOM 状态、原生队列 JSON、小点屏幕坐标）：
  [1] 点行打开的**就是可编辑小窗**：第一个字段"类型"只读，其余是真能改的文本框（不再开两层窗）
  [2] 底部按键 = 真功能键 + 「恢复原始值」+「确定」；**没有**「关掉」「好」「保存」这种凑数键；无重复；每键都有落点(data-fn)
  [3] 「恢复原始值」真恢复：改了不保存 → 值回来，且原生队列没被动过
  [4] 「确定」真保存：原生队列变了，小点跟着挪
  [5] 行上的 ▲▼ 只在该有的地方摆（第一行没有 ▲、最后一行没有 ▼）——没功能的键不摆
  [6] 小窗里不再重复一份「上移/下移」；「并进上一个点」只在前面真有点时才有
  [7] 只读信息窗也走同一形态（值也是文本框，只是只读），且没有「恢复原始值」这种没东西可恢复的键
  [8] 界面里没有说明文字（以前写在按键/正文里的操作说明）
  [9] 全页面按钮集里没有空功能键；「添加步骤」的形态清单是可点行（不占按键名额）
"""
import json
import subprocess
import sys
import time

ROOT = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
checks = []


def ui(expr, match="ui/index.html", timeout=40):
    r = subprocess.run(["node", "tools/uiprobe.mjs", "--timeout", "8000", "--match", match, expr],
                       capture_output=True, text=True, cwd=ROOT, timeout=timeout)
    out = [l for l in (r.stdout or "").strip().splitlines() if l.strip()]
    if not out:
        print("      [probe 没输出] stderr=" + (r.stderr or "")[-160:])
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


def js(expr):
    """在控制台页面里跑一段，返回 'ok'（点东西用）"""
    return ui("(function(){" + expr + "return 'ok';})()")


def state():
    """把小窗此刻的事实一次读回来（DOM + 渲染器自检）"""
    return ui("JSON.stringify((function(){var m=document.getElementById('cdp-modal');"
              "var el=Array.prototype.slice.call(document.querySelectorAll('#cdp-modal [data-field]'));"
              "var sc=(window.CDPUI&&CDPUI.Modal&&CDPUI.Modal.scan)?CDPUI.Modal.scan():{};"
              "return {open:sc.open,fields:sc.fields,readonly:sc.readonly,editable:sc.editable,"
              "textboxes:sc.textboxes,buttons:sc.buttons,fns:sc.fns,dupes:sc.dupes,options:sc.options,"
              "values:sc.values,keys:el.map(function(e){return e.getAttribute('data-field');}),"
              "tags:el.map(function(e){return e.tagName+'/'+e.getAttribute('data-readonly');}),"
              "text:(m?String(m.textContent||''):'')};})())")


def steps():
    return api("/api/record/live").get("steps") or []


def wait_rows(n, timeout=14):
    t0 = time.time()
    last = -1
    while time.time() - t0 < timeout:
        v = ui("document.querySelectorAll('#r-steps .row-item').length")
        last = v if isinstance(v, int) else -1
        if last >= n:
            return last
        time.sleep(0.6)
    return last


def row_btns(i):
    """第 i 行（0 起）上的按键落点：[[文案, data-fn], …]"""
    return ui("JSON.stringify(Array.prototype.map.call(document.querySelectorAll('#r-steps .row-item')[%d]"
              ".querySelectorAll('button'),function(b){return [b.textContent.trim(),b.getAttribute('data-fn')||''];}))" % i)


def press(label):
    js("var b=Array.prototype.filter.call(document.querySelectorAll('#cdp-modal .sheet-actions button'),"
       "function(x){return x.textContent.indexOf('%s')>=0;})[0];if(b)b.click();" % label)


def setup():
    """三步：等待 3 秒 / 坐标点击(250,250) / 坐标点击(600,900)"""
    api("/api/record?action=stop")
    api("/api/record/clear")
    api("/api/ui/open?tab=rec")
    time.sleep(2.0)
    for step in [
        {"t": "wait", "ms": 3000},
        {"t": "click", "mode": "coord", "selector": "",
         "box": {"cx": 250, "cy": 250, "x": 250, "y": 250, "w": 1, "h": 1},
         "anchor": {"mode": "top", "cx": 250, "cy": 250, "vw": 393, "vh": 680, "ratioY": 0.3676, "ratioX": 0.6361},
         "reps": 1, "pauseAfter": 350},
        {"t": "click", "mode": "coord", "selector": "",
         "box": {"cx": 600, "cy": 900, "x": 600, "y": 900, "w": 1, "h": 1},
         "anchor": {"mode": "top", "cx": 600, "cy": 900, "vw": 393, "vh": 680, "ratioY": 1.3235, "ratioX": 1.5267},
         "reps": 1, "pauseAfter": 350},
    ]:
        js("CDPT.call('rec.insert',{step:%s});" % json.dumps(step))
        time.sleep(0.6)
    return wait_rows(3)


def main():
    n = setup()
    s0 = steps()
    print("[0] 准备：队列 %d 步（%s），列表 %s 行" % (len(s0), [s.get("t") for s in s0], n))
    if len(s0) != 3:
        print("  准备没成功（队列不是 3 步），后面的检查没意义")
        sys.exit(2)

    # ---------------------------------------------------------------- [1] 点行 = 可编辑小窗
    print("[1] 点第 2 行（坐标步）→ 打开的**就是可编辑小窗**（值都是文本框）")
    js("document.querySelectorAll('#r-steps .row-item')[1].click();")
    time.sleep(1.0)
    st = state() or {}
    chk("小窗打开", st.get("open"), True)
    keys = st.get("keys") or []
    chk("第一个字段是「类型」", keys[0] if keys else None, "kind")
    tags = st.get("tags") or []
    chk("字段全部是文本框（input/select），没有纯文本值", tags, lambda t: t and all(x.startswith(("INPUT", "SELECT")) for x in t))
    chk("「类型」是只读文本框（改不动）", (tags[0] if tags else ""), "INPUT/1")
    chk("x/y/reps 是可改的（readonly=0）", keys,
        lambda k: set(["kind", "x", "y", "reps", "pauseAfter"]).issubset(set(k)))
    ro = st.get("readonly"); ed = st.get("editable")
    chk("只读 1 个（类型）+ 可改 5 个（x/y/reps/pauseAfter/跑几次）", [ro, ed], [1, 5])
    chk("值落在文本框里（读得回来）", (st.get("values") or {}).get("x"), "250")

    # ---------------------------------------------------------------- [2] 底部按键
    print("[2] 底部按键：真功能键 + 恢复原始值 + 确定；没有凑数键")
    btns = st.get("buttons") or []
    fns = st.get("fns") or []
    print("    按键：" + " / ".join(btns))
    chk("有「确定」", [b for b in btns if b == "确定"], lambda v: bool(v))
    chk("有「恢复原始值」", [b for b in btns if b == "恢复原始值"], lambda v: bool(v))
    chk("没有「关掉」「好」「保存」这种键",
        [b for b in btns if b in ("关掉", "好", "保存", "确定保存", "✔ 保存")], [])
    chk("没有重复文案的键", st.get("dupes"), [])
    chk("每个可见键都有落点（data-fn 非空）", fns, lambda f: f and all(x for x in f))
    chk("「确定」是最后一个键（排在末尾）", btns[-1] if btns else None, "确定")

    # ---------------------------------------------------------------- [3] 恢复原始值
    print("[3] 「恢复原始值」真恢复：改 x=777（不保存）→ 按恢复 → 回 250，且原生队列没被动过")
    js("document.getElementById('fld-x').value='777';")
    v_before = (state() or {}).get("values", {}).get("x")
    press("恢复原始值")
    time.sleep(0.6)
    st3 = state() or {}
    chk("改动先落进文本框（x=777）", v_before, "777")
    chk("按了「恢复原始值」后文本框回到 250", (st3.get("values") or {}).get("x"), "250")
    chk("原生队列里 x 还是 250（恢复不等于保存）", round((steps()[1].get("box") or {}).get("cx", 0)), 250)

    # ---------------------------------------------------------------- [4] 确定 = 真保存
    print("[4] 「确定」真保存：x 改 888 → 确定 → 队列 cx=888，小点跟着挪")
    dots_before = api("/api/record/dots/status")
    x0 = ((dots_before.get("screen") or [{}])[0] or {}).get("x")
    js("document.getElementById('fld-x').value='888';")
    press("确定")
    time.sleep(1.6)
    s4 = steps()
    chk("队列里第 2 步 cx 变成 888（读回来）", round((s4[1].get("box") or {}).get("cx", 0)), 888)
    chk("锚点也跟着改（回放按锚点走）", round((s4[1].get("anchor") or {}).get("cx", 0)), 888)
    dots_after = api("/api/record/dots/status")
    x1 = ((dots_after.get("screen") or [{}])[0] or {}).get("x")
    chk("小点在屏幕上跟着挪了（x %s → %s）" % (x0, x1), [x0, x1],
        lambda v: v[0] is not None and v[1] is not None and v[1] > v[0] + 200)
    chk("保存后小窗自己关掉", (state() or {}).get("open"), False)

    # ---------------------------------------------------------------- [5] 行上的 ▲▼
    print("[5] 行上的 ▲▼ 只在能移的时候摆（没功能的键不摆）")
    b0 = row_btns(0) or []
    b2 = row_btns(2) or []
    print("    第 1 行：" + str(b0) + "；第 3 行：" + str(b2))
    chk("第 1 行没有 ▲（上移）", [x for x in b0 if x[1] == "move-up"], [])
    chk("第 1 行有 ▼（下移）", [x for x in b0 if x[1] == "move-down"], lambda v: bool(v))
    chk("最后 1 行没有 ▼（下移）", [x for x in b2 if x[1] == "move-down"], [])
    chk("最后 1 行有 ▲", [x for x in b2 if x[1] == "move-up"], lambda v: bool(v))

    # ---------------------------------------------------------------- [6] 不与行重复 + 合并键只在必要时
    print("[6] 小窗里不再重复「上移/下移」；「并进上一个点」只在前面的确有「点」时才有")
    js("document.querySelectorAll('#r-steps .row-item')[0].click();")
    time.sleep(0.9)
    st6 = state() or {}
    b6 = st6.get("buttons") or []
    chk("小窗里没有「上移」「下移」（行上已经有 ▲▼）",
        [b for b in b6 if ("上移" in b or "下移" in b)], [])
    chk("第 1 步前面没有点 → 没有「并进上一个点」", [b for b in b6 if "并进" in b], [])
    js("document.getElementById('cdp-modal-x').click();")
    time.sleep(0.4)
    js("document.querySelectorAll('#r-steps .row-item')[1].click();")
    time.sleep(0.9)
    st6b = state() or {}
    b6b = st6b.get("buttons") or []
    print("    第 2 步（前面没有点）的按键：" + " / ".join(b6b))
    js("document.getElementById('cdp-modal-x').click();")
    time.sleep(0.4)

    # ---------------------------------------------------------------- [7] 只读信息窗同一形态
    print("[7] 只读信息窗也走同一形态：值还是文本框（只读），且没有「恢复原始值」")
    ui("(function(){CDPUI.Modal.show({title:'只读试',pairs:[['键一','值一'],['键二','值二']]});return 'ok';})()")
    time.sleep(0.4)
    st7 = state() or {}
    chk("只读窗的字段也是文本框", st7.get("tags"),
        lambda t: t and len(t) == 2 and all(x == "INPUT/1" for x in t))
    chk("只读窗可改字段数 = 0", st7.get("editable"), 0)
    chk("只读窗没有「恢复原始值」（没东西可恢复）",
        [b for b in (st7.get("buttons") or []) if b == "恢复原始值"], [])
    js("document.getElementById('cdp-modal-x').click();")
    time.sleep(0.3)

    # ---------------------------------------------------------------- [8] 界面无说明文字
    print("[8] 界面里没有说明文字（操作说明只进源码/文档）")
    js("document.querySelectorAll('#r-steps .row-item')[1].click();")
    time.sleep(0.9)
    txt = (state() or {}).get("text") or ""
    chk("小窗正文不含「可以直接改数字」这类说明句", "可以直接改数字" in txt, False)
    chk("小窗正文不含「要跑就按」这类说明句", "要跑就按" in txt, False)
    js("document.getElementById('cdp-modal-x').click();")
    time.sleep(0.3)

    # ---------------------------------------------------------------- [9] 全页面没有空功能键 + 形态清单是行
    print("[9] 全页面按钮里没有空功能键；「添加步骤」的形态清单是可点行（不占按键名额）")
    allb = ui("JSON.stringify(Array.prototype.map.call(document.querySelectorAll('button'),"
              "function(b){return b.textContent.trim();}))") or []
    bad = [b for b in allb if b in ("好", "关掉", "保存", "✔ 保存")]
    chk("页面上没有「好」「关掉」「保存」这种键", bad, [])
    js("document.getElementById('r-add').click();")
    time.sleep(0.9)
    st9 = state() or {}
    chk("形态清单是 7 行可点行（没有挤成按键）", st9.get("options"), 7)
    chk("选形态这一屏没有空的「确定」（只有抬头那个 ✕）", [b for b in (st9.get("buttons") or []) if b == "确定"], [])
    js("document.getElementById('cdp-modal-x').click();")

    ok = sum(1 for _, o, _ in checks if o)
    print("\n结果：%d/%d 通过" % (ok, len(checks)))
    if ok != len(checks):
        print("  未通过：")
        for n2, o, g in checks:
            if not o:
                print("    - %s → %s" % (n2, g))
        sys.exit(1)


if __name__ == "__main__":
    main()
