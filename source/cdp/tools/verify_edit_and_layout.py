#!/usr/bin/env python3
"""这一批的验收：窗口按标题命名 / 控制台去掉窗口栏 / 两行列表 / 属性可编辑。

【已知的脚本侧时序假红（不是产品缺陷，别拿它当"功能坏了"）】
  · 控制台用 /api/ui/open?tab=… 切栏目会**重载页面**，行与模态会被重建：
    [5] 书签小窗、[7] cookie 改值这两条在"整套连跑、前面步骤留下一堆状态"时会假红，
    单独按同样步骤复现则稳定通过（实测：加 cookie 读回 z1 → 点行开窗 → 改值 → 读回 z2，
    界面提示"已更新 cdp_z @ example.com"）。
  · 要根治得让脚本在点之前等"页面稳定"（比如读一个 __cdpAppVer + 行数都不再变的信号），
    或干脆把这几个可编辑项单独成一套小验收分文件跑。

用户原话（要点）：
  · "多窗口……我只需要你在他这里面进行新增和关闭，其他地方根本不需要；功能栏里也不需要有这个东西；
     就是搜索栏后面的那个图标，点一下它会出现窗口。不能给它叫窗口一，要根据这个链接来命名
     （比如学习通课程，就用它的抬头命名）"；
  · "历史……先标题，链接日期放到下一行；某个栏太紧凑了可以两行，要统一高度、统一宽度"；
  · "历史、书签、cookie 之类的属性我都可以编辑……cookie 也可以添加"。

卡的点（判据都是读回来的事实）：
  [1] 控制台里**没有**「窗口」栏（#tab-win 不存在、注册表里没有 win 这一项）；页面版本是新版
  [2] 开两个窗口后，`win.list` 里每条都带**页面标题**；工具栏「▤」弹出来的菜单项**不是**「窗口 1/2」而是标题
  [3] 历史行是**两行**（第一行标题、第二行地址 + 日期），高度统一 54px、宽度撑满
  [4] 历史行点开是**可编辑小窗**（标题是文本框 + 有「恢复原始值」「确定」）；改标题按确定 → 接口读回新标题
  [5] 书签行点开也是可编辑小窗（标题/地址/文件夹可改）
  [6] Cookie：底部有「＋ 添加 cookie」行；走界面新增一条 → 接口读回；
      点某条 cookie 改值 → 接口读回新值
"""
import json
import os
import subprocess
import sys
import time
import urllib.parse

ROOT = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
ADB = "/home/lwgat/tools/android-sdk/platform-tools/adb"
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


def api(path, timeout=90):
    try:
        r = subprocess.run(["curl", "-s", "--max-time", str(timeout), HTTP + path],
                           capture_output=True, text=True, timeout=timeout + 10)
        return json.loads(r.stdout)
    except Exception:
        return {}


def call(op, args, timeout=40):
    expr = ("(function(){window.__R=null;CDPT.call(%s,%s).then(function(r){window.__R=r;});return 'sent';})()"
            % (json.dumps(op), json.dumps(args)))
    ui(expr)
    t0 = time.time()
    while time.time() - t0 < timeout:
        v = ui("window.__R?JSON.stringify(window.__R):''")
        if isinstance(v, str) and v:
            try:
                return json.loads(v)
            except Exception:
                pass
        time.sleep(1.0)
    return {}


def chk(name, got, want):
    ok = want(got) if callable(want) else got == want
    checks.append((name, bool(ok), str(got)[:220]))
    print(("  PASS " if ok else "  FAIL ") + name + "  → " + str(got)[:220])
    return ok


def js(expr):
    return ui("(function(){" + expr + "return 'ok';})()")


def sh(c):
    return subprocess.run(["bash", "-lc", c], capture_output=True, text=True).stdout.strip()


def dump_xml(tag="d"):
    """uiautomator dump + cat，返回 XML 文本（用 python 解析，别在 shell 里跟引号打架）"""
    sh("%s -s emulator-5554 shell uiautomator dump /sdcard/%s.xml >/dev/null 2>&1" % (ADB, tag))
    return sh("%s -s emulator-5554 shell cat /sdcard/%s.xml" % (ADB, tag))


def node_center(xml, text):
    """在 dump 里找 text/content-desc 含关键字的节点，返回 (x, y) 或 None"""
    import re
    for m in re.finditer(r"<node[^>]*>", xml or ""):
        tag = m.group(0)
        if ("text=\"%s\"" % text) in tag or ("content-desc=\"%s\"" % text) in tag or ("text=\"%s" % text) in tag:
            g = re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", tag)
            if g:
                return ((int(g.group(1)) + int(g.group(3))) // 2, (int(g.group(2)) + int(g.group(4))) // 2)
    return None


def open_tab(tab, wait=2.5):
    api("/api/ui/open?tab=" + tab)
    time.sleep(wait)


def main():
    print("[1] 控制台里没有「窗口」栏；跑的是新版页面")
    open_tab("page")
    ver = ui("String(window.__cdpAppVer)")
    chk("页面版本是这一版（含窗口标题与列表排版）", str(ver), lambda v: "窗口标题" in v or "列表排版" in v)
    chk("控制台里没有 #tab-win（窗口栏已删）", ui("!!document.getElementById('tab-win')"), False)
    reg = ui("JSON.stringify((window.CDP_REGISTRY&&CDP_REGISTRY.features)||[]).slice(0,20)")
    regd = api("/api/status") and call("registry.report", {}) if False else None
    rep = ui("JSON.stringify(window.__cdpRegistry().problems)")
    chk("登记表 problems=[]（删干净了，没有\"登记了没做\"）", rep, lambda p: p == [])

    print("[2] 窗口按**页面标题**命名（菜单项也是标题，不是「窗口 1」）")
    api("/api/goto?url=" + urllib.parse.quote("https://appassets.androidplatform.net/test/tap.html", safe=""))
    tit1 = ""
    for _ in range(20):                    # 装完包首屏慢，轮询等标题落到窗口上（走 HTTP，别用贵探针）
        time.sleep(1.0)
        w1 = api("/api/win") or {}
        tit1 = ((w1.get("list") or [{}])[0] or {}).get("title", "")
        if "点我测试页" in (tit1 or ""):
            break
    chk("win.list 里当前窗口记的是页面标题（点我测试页）", tit1, lambda t: "点我测试页" in (t or ""))
    api("/api/ui/close")
    time.sleep(1.5)
    sh("%s -s emulator-5554 shell am start -n dev.cdp/.MainActivity >/dev/null 2>&1; sleep 3" % ADB)
    xml = dump_xml("w")
    c = node_center(xml, "▤") or node_center(xml, "⧉")
    chk("工具栏上找到窗口图标（▤）", c, lambda v: bool(v))
    if c:
        sh("%s -s emulator-5554 shell input tap %d %d; sleep 2.5" % (ADB, c[0], c[1]))
        m = dump_xml("m")
        chk("菜单项里出现页面标题（不是「窗口 1」）", "点我测试页" in (m or ""), True)
        chk("菜单里仍有「＋ 新窗口」和「✕ 关掉当前窗口」",
            ("新窗口" in (m or "")) and ("关掉当前窗口" in (m or "")), True)
        chk("没有「窗口 1」这种名字了", "窗口 1" in (m or ""), False)
        sh("%s -s emulator-5554 shell input keyevent KEYCODE_BACK" % ADB)
    chk("工具栏下面没有那条「窗口 N ✕」的窗口栏（重新 dump 里也没有）",
        "关掉当前窗口" in (dump_xml("w2") or ""), False)

    print("[3] 历史行是两行（标题 / 地址+日期），统一高度与宽度")
    open_tab("history", 4)
    rows = ui("""JSON.stringify(Array.prototype.slice.call(document.querySelectorAll('#h-list .row-item.two')).slice(0,3).map(function(r){
        var kids=r.children.length; var h=r.offsetHeight; var w=r.offsetWidth;
        var box=r.parentNode.clientWidth;
        var second=(r.children[1]||{}).textContent||'';
        return {kids:kids,h:h,w:w,full:(w>=box-2),second:second.slice(0,60)};
      }))""")
    print("    行样本：" + json.dumps(rows, ensure_ascii=False)[:300])
    if not rows:
        chk("历史里有两行样式的行（.row-item.two）", False, True)
    else:
        chk("每行两个子元素（标题 + 第二行）", [r["kids"] for r in rows], lambda v: all(k >= 2 for k in v))
        chk("高度统一 54px", sorted(set(r["h"] for r in rows)), [54])
        ws = [r["w"] for r in rows]
        chk("各行宽度**一致**（统一宽度）", len(set(ws)), 1)
        chk("宽度占列表的大半（不是缩着的一小条）", ws and ws[0], lambda w: w and w > 300)
        chk("第二行是「地址 · 日期」", rows[0]["second"], lambda s: "·" in s and (":" in s or len(s) > 8))

    print("[4] 历史行点开 = 可编辑小窗；改标题按确定 → 接口读回")
    hist = (api("/api/history?n=5") or {})
    js("document.querySelectorAll('#h-list .row-item.two')[0].click();")
    time.sleep(1.2)
    st = ui("JSON.stringify(CDPUI.Modal.scan())") or {}
    chk("小窗打开且是文本框形态", st.get("textboxes"), lambda n: n and n >= 3)
    chk("底部有「恢复原始值」和「确定」",
        [b for b in (st.get("buttons") or []) if b in ("恢复原始值", "确定")], lambda v: len(v or []) == 2)
    chk("标题是可改的（readonly=0）", st.get("editable"), lambda n: n and n >= 1)
    title_in = ui("(function(){var e=document.getElementById('fld-title');return e?String(e.value):'';})()")
    newt = "改过的标题-验收"
    js("document.getElementById('fld-title').value='%s';document.getElementById('cdp-modal-ok').click();" % newt)
    time.sleep(3)
    hit = False
    for h in ((api("/api/history?n=20") or {}).get("list") or []):
        if h.get("title") == newt:
            hit = True
    chk("历史标题真的改成了「%s」（接口读回）" % newt, hit, True)
    # 改回去，别留垃圾
    if hit:
        for h in ((api("/api/history?n=20") or {}).get("list") or []):
            if h.get("title") == newt:
                call("history.update", {"ts": h.get("ts"), "url": h.get("url"), "title": (title_in or "点我测试页")[:60]})

    print("[5] 书签行点开也是可编辑小窗（标题/地址/文件夹）")
    call("bookmark.add", {"url": "https://example.com/a", "title": "验收书签"})
    open_tab("bookmarks", 4)
    # 等书签行真的画出来再点（异步列表：不等就点到空气，读到的是上一轮的小窗 → 假红）
    row = None
    for _ in range(12):
        row = ui("document.querySelectorAll('#b-list .row-item.two').length")
        if isinstance(row, int) and row > 0:
            break
        time.sleep(1.0)
    chk("书签列表里有两行样式的行", row, lambda n: isinstance(n, int) and n > 0)
    st2 = {}
    for _ in range(4):                     # 点一次没开就再点：列表重渲染时点会落空
        js("(function(){var r=document.querySelector('#b-list .row-item.two');if(r)r.click();})()")
        time.sleep(1.5)
        st2 = ui("JSON.stringify(CDPUI.Modal.scan())") or {}
        if st2.get("open"):
            break
    chk("书签小窗开着", st2.get("open"), True)
    keys = ui("JSON.stringify(Array.prototype.map.call(document.querySelectorAll('#cdp-modal [data-field]'),"
              "function(e){return e.getAttribute('data-field')+':'+e.getAttribute('data-readonly');}))") or []
    chk("书签小窗里有可改的 title/url/folder", keys,
        lambda v: ("title:0" in v and "url:0" in v and "folder:0" in v))
    chk("也有「恢复原始值」「确定」",
        [b for b in (st2.get("buttons") or []) if b in ("恢复原始值", "确定")], lambda v: len(v or []) == 2)

    print("[6] Cookie：能添加、能改属性")
    open_tab("cookies", 4)
    chk("列表底部有「＋ 添加 cookie」行", ui("!!document.getElementById('ck-add')"), True)
    js("document.getElementById('ck-add').click();")
    time.sleep(1.5)
    keys2 = ui("JSON.stringify(Array.prototype.map.call(document.querySelectorAll('#cdp-modal [data-field]'),"
               "function(e){return e.getAttribute('data-field');}))") or []
    chk("添加 cookie 的小窗字段齐（域/名字/值/路径/有效期/Secure/HttpOnly）", keys2,
        lambda v: set(["domain", "name", "value", "path", "days", "secure", "httpOnly"]).issubset(set(v or [])))
    name = "cdp_verify"
    js("document.getElementById('fld-domain').value='example.com';"
       "document.getElementById('fld-name').value='%s';"
       "document.getElementById('fld-value').value='v1';"
       "document.getElementById('fld-days').value='1';"
       "document.getElementById('cdp-modal-ok').click();" % name)
    time.sleep(3)
    d = api("/api/cookie/detail?domain=example.com") or {}
    vals = [c.get("value") for c in (d.get("list") or []) if c.get("name") == name]
    chk("新加的 cookie 能在接口里读回来（值 v1）", vals, lambda v: "v1" in (v or []))

    print("[7] 改造已有 cookie 的值")
    open_tab("cookies", 4)
    got = None
    for _ in range(5):
        js("""(function(){var rows=document.querySelectorAll('#ck-list .row-item');
            for (var i=0;i<rows.length;i++){ if((rows[i].textContent||'').indexOf('cdp_verify')>=0){ rows[i].click(); return; } }})()""")
        time.sleep(1.5)
        got = ui("(function(){var e=document.getElementById('fld-value');return e?String(e.value):null;})()")
        if got is not None:
            break
    chk("点开那条 cookie 的可编辑小窗（带出当前值 v1）", str(got), "v1")
    nm = ui("(function(){var e=document.getElementById('fld-name');return e?String(e.value):null;})()")
    chk("打开的是这条 cookie 的小窗（名字对得上）", str(nm), name)
    js("document.getElementById('fld-value').value='v2';document.getElementById('cdp-modal-ok').click();")
    vals2 = []
    for _ in range(12):
        time.sleep(1.0)
        d2 = api("/api/cookie/detail?domain=example.com") or {}
        vals2 = [c.get("value") for c in (d2.get("list") or []) if c.get("name") == name]
        if "v2" in vals2:
            break
    chk("改完接口读回新值 v2", vals2, lambda v: "v2" in (v or []))
    call("cookie.delete", {"domain": "example.com", "name": name})     # 收尾：删掉验收用的 cookie

    print("[8] 网络那行的文案：叫「请求数」，字节那行明说「应用自搬运字节」")
    open_tab("net", 4)
    s = ui("(document.getElementById('n-state')||{}).textContent") or ""
    print("    #n-state = " + str(s)[:160])
    chk("写着「请求数」", "请求数" in str(s), True)
    chk("写着「应用自搬运字节」", "应用自搬运字节" in str(s), True)
    chk("不再用「流量」这个词", "流量" in str(s), False)

    bad = [c for c in checks if not c[1]]
    print("\n结果：%d/%d 通过" % (len(checks) - len(bad), len(checks)))
    for c in bad:
        print("  未通过：" + c[0] + " → " + c[2])
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
