#!/usr/bin/env python3
"""步骤（三种形态）能不能加、能不能改、能不能删 —— 按用户抱怨的三点逐条验：

  ① 不在监听/录制里、列表还空着时，也能**加步骤**，而且点了要有反馈（不再"一点反应也没有"）；
  ② 每一步都能**编辑信息**（坐标 / 毫秒 / 条件 / 选择器 / 连点次数），小点也要能编辑；
  ③ 三种形态（等待类 / 播放类 / 点击类）都是"步骤"：能加、能改、能删、能换顺序、能只跑它。

判据都是读回来的事实：队列 JSON、小点状态里的屏幕坐标、控制台 DOM 里的小窗字段。
"""
import json
import subprocess
import sys
import time

ROOT = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
ADB = "/home/lwgat/tools/android-sdk/platform-tools/adb"
SER = "emulator-5554"
PAGE = "https://appassets.androidplatform.net/test/tap.html"
checks = []


def ui(expr, match="ui/index.html", timeout=40):
    r = subprocess.run(["node", "tools/uiprobe.mjs", "--timeout", "8000", "--match", match,
                        expr], capture_output=True, text=True, cwd=ROOT, timeout=timeout)
    out = [l for l in (r.stdout or "").strip().splitlines() if l.strip()]
    if not out:
        print("      [probe 没输出] stderr=" + (r.stderr or "")[-160:])
        return None
    for line in reversed(out):            # 从后往前找第一个能当 JSON 解的行
        try:
            v = json.loads(line)
        except Exception:
            continue
        if isinstance(v, str):            # 表达式里 stringify 过一层
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


def steps():
    return api("/api/record/live").get("steps") or []


def chk(name, got, want):
    ok = want(got) if callable(want) else got == want
    checks.append((name, bool(ok), str(got)[:220]))
    print(("  PASS " if ok else "  FAIL ") + name + "  → " + str(got)[:220])
    return ok


def open_rec_tab():
    api("/api/ui/open?tab=rec")
    time.sleep(2.5)


def click_js(expr):
    """点控制台里的东西（控制台是我们自己的页面，直接走它自己的 click 处理器）"""
    return ui("(function(){" + expr + "return 'ok';})()")


def pickopt(label):
    """选步骤形态：形态清单现在是可点的行（data-option），不再挤成一排按键"""
    return ui("(function(){var r=Array.prototype.filter.call(document.querySelectorAll('#cdp-modal [data-option]'),"
              "function(x){return x.getAttribute('data-option').indexOf('%s')>=0;})[0];"
              "if(!r)return 'noopt';r.click();return 'ok';})()" % label)


def modal_state():
    return ui("JSON.stringify({open:(function(){var m=document.getElementById('cdp-modal');"
              "return !!m && m.style.display!=='none';})(),"
              "title:(function(){var h=document.querySelector('#cdp-modal .sheet-head b');return h?h.textContent:'';})(),"
              "fields:Array.prototype.map.call(document.querySelectorAll('#cdp-modal [data-field]'),"
              "function(e){return e.getAttribute('data-field');}),"
              "buttons:Array.prototype.map.call(document.querySelectorAll('#cdp-modal .sheet-actions button'),"
              "function(e){return e.textContent;})})")


def wait_rows(n, timeout=12):
    """等界面上的步骤行数达到 n（录制的每秒轮询 + 每次操作后重画，都要给它一点时间）"""
    t0 = time.time()
    last = -1
    while time.time() - t0 < timeout:
        v = ui("document.querySelectorAll('#r-steps .row-item').length")
        last = v if isinstance(v, int) else -1
        if last >= n:
            return last
        time.sleep(0.6)
    return last


def main():
    print("[0] 干净起：清空步骤，只看列表（这时候还没开监听）")
    api("/api/record?action=stop")
    api("/api/record/clear")
    open_rec_tab()
    wait_rows(0)                      # 清空要等界面刷过来（每秒轮询），不然读到的是上一轮的列表
    for _ in range(10):               # 空列表的引导文字也是轮询画上去的，给它几秒
        _t = ui("((document.getElementById('r-steps')||{}).textContent||'')")
        if isinstance(_t, str) and "添加步骤" in _t:
            break
        time.sleep(0.7)
    st = ui("JSON.stringify({empty:(document.getElementById('r-steps')||{}).textContent||'',"
            "addBtn:!!document.getElementById('r-add'),"
            "clearBtn:!!document.getElementById('r-clear')})")
    # 1) 空列表要有引导 + 添加入口
    chk("列表空着时给了明确引导（不是干瘪一句「还没录到步骤」）",
        (st or {}).get("empty"), lambda t: t and "添加步骤" in t)
    chk("「＋ 添加步骤」按钮在（不用先开监听）", (st or {}).get("addBtn"), True)
    chk("「清空步骤」按钮还在", (st or {}).get("clearBtn"), True)

    print("[1] 点「＋ 添加步骤」→ 出形态选择（等待/播放/点击…）")
    click_js("document.getElementById('r-add').click();")
    time.sleep(0.8)
    m = modal_state()
    chk("小窗弹出来了", (m or {}).get("open"), True)
    opts = ui("JSON.stringify(Array.prototype.map.call(document.querySelectorAll('#cdp-modal [data-option]'),"
              "function(x){return x.getAttribute('data-option');}))") or []
    chk("给了多种形态可选（≥5 种，而且是可点的行）", len(opts), lambda n: n >= 5)
    chk("选形态这一屏没有空的「确定」", [b for b in ((m or {}).get("buttons") or []) if b == "确定"], [])
    print("    可选形态：" + " / ".join(opts))

    print("[2] 加一条「等待 3 秒」：走小窗 → 填参数 → 保存")
    pickopt("等待")
    time.sleep(0.8)
    m2 = modal_state()
    chk("切到「填参数」的表单（秒数字段）", (m2 or {}).get("fields"), lambda f: f and "sec" in f)
    click_js("var e=document.getElementById('fld-sec');e.value='3';")
    click_js("document.getElementById('cdp-modal-ok').click();")
    time.sleep(1.2)
    s1 = steps()
    chk("队列里多了一条等待步", [len(s1), (s1[0].get("t") if s1 else None), (s1[0].get("ms") if s1 else None)],
        [1, "wait", 3000])

    print("[3] 加一条「按坐标点 (150,250)」→ 队列 + 页面上出小点")
    click_js("document.getElementById('r-add').click();")
    time.sleep(0.7)
    pickopt("按坐标点")
    time.sleep(0.7)
    click_js("document.getElementById('fld-x').value='150';document.getElementById('fld-y').value='250';")
    click_js("document.getElementById('cdp-modal-ok').click();")
    time.sleep(1.5)
    s2 = steps()
    chk("队列 2 条，第 2 条是按坐标的点击步", [len(s2), s2[1].get("t"), s2[1].get("mode"),
                                              round(s2[1].get("box", {}).get("cx", 0)), round(s2[1].get("box", {}).get("cy", 0))],
        [2, "click", "coord", 150, 250])
    dots = api("/api/record/dots/status")
    chk("页面上出现 1 个小点", dots.get("count"), 1)
    screen_y = (dots.get("screen") or [{}])[0].get("y")

    print("[4] 改它：直接点这一行（点行就是可编辑小窗）→ 把 y 从 250 改成 400 → 确定")
    wait_rows(2)                      # 等两行都在（每秒轮询刷）
    click_js("document.querySelectorAll('#r-steps .row-item')[1].click();")
    time.sleep(1.0)
    m4 = modal_state()
    chk("小窗里带出 x/y/reps（值就是文本框，可改）", (m4 or {}).get("fields"),
        lambda f: f and set(["kind", "x", "y", "reps"]).issubset(set(f)))
    click_js("document.getElementById('fld-y').value='400';")
    click_js("document.getElementById('cdp-modal-ok').click();")
    time.sleep(1.5)
    s3 = steps()
    chk("第 2 步的 y 真的改成 400（读回来）", round(s3[1].get("box", {}).get("cy", 0)), 400)
    chk("锚点也跟着改（回放用的是锚点）", round((s3[1].get("anchor") or {}).get("cy", 0)), 400)
    dots2 = api("/api/record/dots/status")
    ny = (dots2.get("screen") or [{}])[0].get("y")
    chk("小点跟着挪了（屏幕 y %s → %s）" % (screen_y, ny), [screen_y, ny],
        lambda v: v[0] is not None and v[1] is not None and v[1] > v[0] + 100)

    print("[5] 三种形态都能加：等条件（视频播完）+ 播放视频")
    for label, want_t in [("等条件", "waitFor"), ("播放视频", "playVideo")]:
        click_js("document.getElementById('r-add').click();")
        time.sleep(0.7)
        pickopt(label)
        time.sleep(0.7)
        click_js("document.getElementById('cdp-modal-ok').click();")
        time.sleep(1.2)
        cur = steps()
        chk("加了「%s」步（队尾类型 %s）" % (label, want_t), (cur[-1].get("t") if cur else None), want_t)
    cur = steps()
    chk("现在队列 4 条，形态分布", [s.get("t") for s in cur], ["wait", "click", "waitFor", "playVideo"])

    print("[6] 删除：在编辑小窗里按「✕ 删掉这一步」")
    wait_rows(4)
    click_js("document.querySelectorAll('#r-steps .row-item')[3].click();")
    time.sleep(1.0)
    click_js("Array.prototype.filter.call(document.querySelectorAll('#cdp-modal .sheet-actions button'),"
             "function(b){return b.textContent.indexOf('删掉这一步')>=0;})[0].click();")
    time.sleep(1.2)
    chk("删完剩 3 条（少了一条）", len(steps()), 3)

    print("[7] 真手指点小点 → 应该开「这一步」的小窗（能改信息），而不是只听个响")
    api("/api/ui/close")
    time.sleep(1)
    pos = api("/api/record/dots/status").get("screen") or []
    if pos:
        x, y = int(pos[0]["x"]), int(pos[0]["y"])
        subprocess.run(["bash", "-lc", "%s -s %s shell input tap %d %d" % (ADB, SER, x, y)])
        m5 = None
        for _ in range(12):                 # 控制台要先被叫出来再等页面就绪，最多等 ~12 秒
            time.sleep(1)
            m5 = modal_state()
            if (m5 or {}).get("open"):
                break
        if not (m5 or {}).get("open"):      # 失败要能分辨：是"没点到小点"还是"点了但没开窗"
            lg = api("/api/log?n=40")
            lines = [l for l in (lg.get("lines") or lg.get("log") or []) if "小点" in str(l)]
            print("      小点相关日志：" + " ｜ ".join(str(l)[-90:] for l in lines[-4:]))
        chk("小窗开了", (m5 or {}).get("open"), True)
        chk("小窗标题写着这是第几步", (m5 or {}).get("title"), lambda t: t and ("步" in t))
        chk("表单里有可改的字段", (m5 or {}).get("fields"), lambda f: bool(f))
        chk("有「✕ 删掉这一步」这类键", [b for b in ((m5 or {}).get("buttons") or []) if "删掉" in b], lambda v: bool(v))
    else:
        chk("取到小点位置", False, True)

    print("[8] 模块与登记表自检")
    mod = ui("JSON.stringify(window.__cdpModules())")
    chk("模块自检 violations=[] 且未撞 id", [(mod or {}).get("violations"), (mod or {}).get("duplicateIds")],
        [[], []])
    prob = ui("JSON.stringify(window.__cdpRegistry().problems)")
    chk("登记表 problems=[]", prob, lambda p: p == [])

    bad = [c for c in checks if not c[1]]
    print("\n结果：%d/%d 通过" % (len(checks) - len(bad), len(checks)))
    for c in bad:
        print("  未通过：" + c[0] + " → " + c[2])
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
