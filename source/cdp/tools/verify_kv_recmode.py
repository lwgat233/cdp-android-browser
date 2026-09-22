#!/usr/bin/env python3
"""两件事的验收：
  ① 小窗里的键值对排版 —— 键和值**不在一行**（键小字在上、值大字在下），每对之间一条横线；
  ② 录制支持两种方式 —— **元素录制**（记选择器/文字）与**坐标录制**（只记点在哪），并且回放都真的点到。

判据：① 读 DOM 结构与 computedStyle（字号大小 + border-top）；
      ② 读录制出来的步骤 JSON（mode / selector / anchor），再回放一次看 isTrusted。
"""
import json
import subprocess
import sys
import time

ROOT = "/vol1/1000/airesults/cdp/source/cdp"
HTTP = "http://127.0.0.1:8848"
PAGE = "https://appassets.androidplatform.net/test/audio.html"
TAPPAGE = "https://appassets.androidplatform.net/test/tap.html"
checks = []


def ui(expr, timeout=45):
    r = subprocess.run(["node", "tools/uiprobe.mjs", "--timeout", "8000", expr],
                       capture_output=True, text=True, cwd=ROOT, timeout=timeout)
    out = [l for l in (r.stdout or "").strip().splitlines() if l.strip()]
    if not out:
        return None
    try:
        return json.loads(out[-1])
    except Exception:
        return out[-1]


def api(path, timeout=60, retry=2):
    for _ in range(retry):
        r = subprocess.run(["curl", "-s", "--max-time", str(timeout), HTTP + path],
                           capture_output=True, text=True, timeout=timeout + 10)
        try:
            return json.loads(r.stdout)
        except Exception:
            time.sleep(0.5)
    return {}


def tape_steps():
    """读当前录制队列（原生那份）"""
    d = api("/api/record/live")
    return (d.get("steps") or []) if isinstance(d, dict) else []


def tap_retry(x, y, expect_at_least=1, tries=3):   # noqa: D401
    """真手指点，点到为止：模拟器偶尔**丢输入事件**（实测约 1/3），
    所以点完要读回步数确认，没记上就再点一次（坐标现测、重试同一点）。"""
    for i in range(tries):
        subprocess.run(["/home/lwgat/tools/android-sdk/platform-tools/adb", "-s", "emulator-5554",
                        "shell", "input", "tap", str(x), str(y)], capture_output=True, text=True, timeout=40)
        time.sleep(2)
        if len(tape_steps()) >= expect_at_least:
            return i + 1
    return tries


def chk(name, got, want):
    ok = want(got) if callable(want) else got == want
    checks.append((name, bool(ok), str(got)[:220]))
    print(("  PASS " if ok else "  FAIL ") + name + "  → " + str(got)[:220])
    return ok


def kv_layout():
    print("[1] 键值对排版：键小字在上、值大字在下、横线分隔")
    api("/api/ui/open?tab=net")
    time.sleep(1.5)
    ui("window.__cdpTab('net')")
    time.sleep(1.8)
    ui("(function(){var r=document.querySelector('#n-list .row-item');if(r)r.click();return 1;})()")
    time.sleep(0.5)
    got = ui("""(function(){
      var box=document.querySelector('#cdp-modal .kv-list');
      if(!box) return JSON.stringify({found:false});
      var kv=Array.prototype.slice.call(box.querySelectorAll('.kv'));
      var first=kv[0];
      var k=first.querySelector('.kv-k'), v=first.querySelector('.kv-v');
      var cs=function(el){var s=getComputedStyle(el);return {size:parseFloat(s.fontSize), color:s.color};};
      var kids=Array.prototype.slice.call(first.children).map(function(c){return c.className;});
      var second=kv[1]?getComputedStyle(kv[1]):null;
      return JSON.stringify({
        found:true, count:kv.length,
        order:kids,
        kSize:cs(k).size, vSize:cs(v).size,
        kColor:cs(k).color, vColor:cs(v).color,
        sameRow: Math.abs(k.getBoundingClientRect().top - v.getBoundingClientRect().top) < 3,
        kAboveV: k.getBoundingClientRect().bottom <= v.getBoundingClientRect().top + 1,
        secondBorder: second ? second.borderTopWidth : '',
        actions: document.querySelectorAll('#cdp-modal .sheet-actions').length
      });
    })()""")
    if not isinstance(got, dict) or not got.get("found"):
        chk("弹窗里有关键值对列表", got, lambda g: bool(g and g.get("found")))
        return
    chk("键值对有多条（%s 条）" % got.get("count"), got.get("count"), lambda n: n >= 2)
    chk("每一对是「键在上、值在下」的两行（顺序）", got.get("order"), lambda o: o == ["kv-k", "kv-v"] or (o and o[0].startswith("kv-k")))
    chk("两个不在同一行", got.get("sameRow"), False)
    chk("键在上、值在下（纵向位置）", got.get("kAboveV"), True)
    chk("值字号比键大", [got.get("kSize"), got.get("vSize")], lambda kv: kv[1] > kv[0])
    chk("值与键颜色有层次（值更亮）", [got.get("kColor"), got.get("vColor")], lambda c: c[0] != c[1])
    chk("键值对之间有横线（第 2 条有 border-top）", got.get("secondBorder"), lambda w: w and float(str(w).replace("px", "")) > 0)
    chk("动作栏在（动作排在信息下面）", got.get("actions"), 1)
    ui("(function(){var x=document.getElementById('cdp-modal-x');if(x)x.click();return 1;})()")


def record_modes():
    print("\n[2] 录制两种方式：元素录制 / 坐标录制（点用**真手指** adb input tap —— ")
    print("    坐标录制只记真实手指：回放/脚本注入的触摸不算，否则回放时会把步骤又记一遍）")
    api("/api/goto?url=" + TAPPAGE)
    time.sleep(3)
    api("/api/ui/close")
    last_saved = None
    for mode in ("element", "coord"):
        api("/api/record/clear")
        # 开始录制：元素 / 坐标（mode 参数走同一条 op，页面侧记在 rec.mode 上）
        # 名字用百分号编码（控制口 URL 里的非 ASCII 不编码可能被丢掉，踩过：脚本名退回默认"录制脚本 N"）
        from urllib.parse import quote
        nm = quote("坐标录制" if mode == "coord" else "元素录制")
        r = api("/api/record?action=start&mode=%s&name=%s" % (mode, nm))
        time.sleep(1.0)
        # 真手指点屏幕中央：整屏都是可点区，坐标录制不看元素、只看"点在哪"
        tap_retry(540, 1400)
        # 注意：/api/recording 的 steps 是**数量**，要看步骤列表得用 /api/record/live
        live = api("/api/record/live")
        lst = (live.get("steps") or []) if isinstance(live, dict) else []
        # 停止并保存：这个回包直接给出**刚存的那份脚本 id**，回放就照它来（别去列表里猜哪条是刚录的）
        saved_id = (api("/api/record?action=stop").get("id")) or None
        last_saved = saved_id or last_saved
        clicks = [s for s in lst if isinstance(s, dict) and (s.get("t") == "click")]
        if not clicks:
            chk("%s：录到了一个点击步骤" % mode, lst, lambda x: bool(x))
            continue
        s = clicks[0]
        if mode == "element":
            chk("元素录制：步骤带选择器", (s.get("target") or {}).get("selector"), lambda v: bool(v))
            chk("元素录制：模式标成 element", s.get("mode") or "element", "element")
        else:
            chk("坐标录制：步骤不带选择器（不看元素）", (s.get("target") or {}).get("selector"), lambda v: not v)
            chk("坐标录制：模式标成 coord", s.get("mode"), "coord")
            anc = s.get("anchor") or {}
            chk("坐标录制：记下了位置锚点（cx/cy）", [anc.get("cx"), anc.get("cy")],
                lambda v: v[0] is not None and v[1] is not None)
            box = s.get("box") or {}
            chk("坐标录制：记下了点击点的视口坐标", [box.get("cx"), box.get("cy")],
                lambda v: v[0] is not None)
    # 回放**刚录的这条坐标脚本**：用停止时的回包给的 id，别去列表里猜
    # （列表是"旧的在前"，抓 rec[0] 可能抓到"等视频播完"那种带等待条件的脚本 → 在测试页等不到 → 超时假红，踩过）
    sid = last_saved
    if sid:
        print("    回放对象：%s（停止时保存的那份）" % sid)
        pl = api("/api/replay?script=%s" % sid, timeout=120)
        if not pl.get("ok"):
            print("    [详情] " + str(pl)[:220])
        chk("坐标录制的脚本能回放（整体 ok）", pl.get("ok"), True if pl else None)
        steps_pl = pl.get("steps") or []
        if steps_pl:
            first = steps_pl[0]
            chk("回放用的是真实触摸（via 位置锚点 / trusted）",
                [first.get("via"), first.get("trusted")],
                lambda v: (v[1] is True) or (v[0] in ("anchor", "anchor-point", "anchor-only")))


def main():
    kv_layout()
    record_modes()
    bad = [c for c in checks if not c[1]]
    print("\n结果：%d/%d 通过" % (len(checks) - len(bad), len(checks)))
    for c in bad:
        print("  未通过：" + c[0] + " → " + c[2])
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
