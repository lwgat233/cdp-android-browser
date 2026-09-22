#!/usr/bin/env python3
"""按"功能 → 板块 → 按键/交互 → 判据 → 缺口"生成规格文档，并做覆盖自检。

用户的要求（原话大意）：写项目要明确**功能是什么、要求是什么、目标是什么**；
测试围着功能点转；每个板块**应该具备哪些功能、哪个功能对应哪个按键**要写成文档；
然后就能看出**哪个功能点不足**（没有这个功能、没有人机交互 → 开发不足）。

这份脚本机械地做三件事：
  1. 从 `registry.js` 读功能登记表（id/name/coll/tab/board/carrier/entry/testid）；
  2. 从 `index.html` 读每个板块里的**控件**（按键 / 输入框 / 下拉 / 状态文字）—— 这就是"人机交互"；
  3. 从 `tools/test_map.json` 读"哪个功能被哪条测试断言覆盖"，算出缺口：
       · 空板块（HTML 里没有这个 data-board）→ 登记了却没做
       · 没有任何按键/输入 → **没有交互**（用户说的"没有人机交互就是开发不足"）
       · 没有任何测试覆盖 → **没有判据**
  输出：`docs/功能规格.md`（人看的规格表）与 `docs/覆盖自检.md`（缺口清单）。

用法：python3 tools/check_coverage.py [--strict]
      --strict 时有缺口就退出码 1（放进回归里当门禁）
"""
import argparse
import json
import os
import re
import sys

def find_layout():
    """两个地方都能跑：源码树 source/cdp/tools/ 与归档树 cdp/tools/"""
    here = os.path.dirname(os.path.abspath(__file__))
    up = os.path.abspath(os.path.join(here, ".."))
    cand = os.path.join(up, "app", "src", "main", "assets", "ui")
    if os.path.isdir(cand):                       # 源码树：source/cdp/tools → 文档在 ../../docs
        return here, up, cand, os.path.abspath(os.path.join(up, "..", "..", "docs"))
    ui = os.path.join(up, "source", "cdp", "app", "src", "main", "assets", "ui")   # 归档树
    return here, up, ui, os.path.join(up, "docs")


TOOLS, ROOT, UI, DOCS = find_layout()
OUT_SPEC = os.path.join(DOCS, "功能规格.md")
OUT_GAP = os.path.join(DOCS, "覆盖自检.md")


def read(p):
    with open(p, encoding="utf-8") as f:
        return f.read()


def parse_registry():
    """从 registry.js 里抠出功能行（它是纯数据，正则可读）"""
    txt = read(os.path.join(UI, "registry.js"))
    feats = []
    for m in re.finditer(r"\{\s*id:\s*'([^']+)'[^}]*?\}", txt):
        row = m.group(0)
        def g(k, d=""):
            mm = re.search(k + r":\s*'([^']*)'", row)
            return mm.group(1) if mm else d
        feats.append({
            "id": g("id"), "name": g("name"), "coll": g("coll"), "tab": g("tab"),
            "board": g("board"), "carrier": g("carrier"), "entry": g("entry"),
            "testid": g("testid"), "status": g("status"),
        })
    return feats


def parse_collections():
    txt = read(os.path.join(UI, "registry.js"))
    colls, tabs = {}, {}
    for m in re.finditer(r"\{\s*key:\s*'([^']+)',\s*name:\s*'([^']+)'", txt):
        pass
    # 集合与栏目分两块：先 COLLECTIONS 再 TABS
    part = txt.split("var TABS")[0]
    for m in re.finditer(r"\{\s*key:\s*'([^']+)',\s*name:\s*'([^']+)'", part):
        colls[m.group(1)] = m.group(2)
    part2 = txt.split("var TABS")[1] if "var TABS" in txt else ""
    for m in re.finditer(r"key:\s*'([^']+)',\s*coll:\s*'([^']+)',\s*name:\s*'([^']+)'", part2):
        tabs[m.group(1)] = {"coll": m.group(2), "name": m.group(3)}
    return colls, tabs


def parse_boards():
    """每个 data-board 里的控件清单：按键 / 输入 / 下拉 / 状态文字"""
    txt = read(os.path.join(UI, "index.html"))
    boards = {}
    # 按 <div class="card" ... data-board="x" ...> 到下一个 card / section 结束切块
    idx = [m.start() for m in re.finditer(r'<div class="card"', txt)]
    idx.append(len(txt))
    for i in range(len(idx) - 1):
        block = txt[idx[i]:idx[i + 1]]
        bm = re.search(r'data-board="([^"]+)"', block)
        if not bm:
            continue
        bid = bm.group(1)
        btns = [{"id": m.group(1), "text": re.sub(r"<[^>]+>", "", m.group(2)).strip()}
                for m in re.finditer(r'<button[^>]*id="([^"]+)"[^>]*>(.*?)</button>', block, re.S)]
        inputs = [{"id": m.group(1), "ph": m.group(2)}
                  for m in re.finditer(r'<input[^>]*id="([^"]+)"[^>]*placeholder="([^"]*)"', block)]
        sels = [{"id": m.group(1), "n": len(re.findall(r"<option", m.group(2)))}
                for m in re.finditer(r'<select[^>]*id="([^"]+)"[^>]*>(.*?)</select>', block, re.S)]
        labels = [re.sub(r"<[^>]+>", "", m.group(1)).strip()
                  for m in re.finditer(r'<div class="label">(.*?)</div>', block, re.S)]
        boards[bid] = {"buttons": btns, "inputs": inputs, "selects": sels, "label": labels[0] if labels else ""}
    return boards


def parse_test_map():
    p = os.path.join(TOOLS, "test_map.json")
    if os.path.exists(p):
        return json.loads(read(p))
    return {}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--strict", action="store_true")
    args = ap.parse_args()

    feats = parse_registry()
    colls, tabs = parse_collections()
    boards = parse_boards()
    tmap = parse_test_map()

    rows, gaps = [], []
    for f in feats:
        b = boards.get(f["board"])
        tests = tmap.get(f["id"], [])
        n_btn = len(b["buttons"]) if b else 0
        n_inp = (len(b["inputs"]) + len(b["selects"])) if b else 0
        missing = []
        if f["board"] and not b:
            missing.append("HTML 里没有这个板块（登记了没做）")
        if f["board"] and b and n_btn == 0 and n_inp == 0:
            missing.append("**没有任何交互**（没有按键也没有输入框）")
        if not tests:
            missing.append("**没有测试覆盖**（没有判据）")
        if f["status"] and ("占位" in f["status"] or "待" in f["status"]):
            missing.append("实现状态写着：" + f["status"])
        rows.append({
            "id": f["id"], "name": f["name"],
            "where": "%s ▸ %s" % (colls.get(f["coll"], f["coll"]), tabs.get(f["tab"], {}).get("name", f["tab"])),
            "board": f["board"] or "（无板块：纯状态文字）",
            "carrier": f["carrier"], "entry": f["entry"],
            "buttons": ", ".join("`%s` %s" % (x["id"], x["text"]) for x in (b["buttons"] if b else [])),
            "inputs": ", ".join("`%s`%s" % (x["id"], ("（" + x["ph"] + "）") if x["ph"] else "") for x in (b["inputs"] if b else []))
                      + (", " if (b and b["inputs"] and b["selects"]) else "")
                      + ", ".join("`%s`(下拉 %d 项)" % (x["id"], x["n"]) for x in (b["selects"] if b else [])),
            "tests": ", ".join(tests),
            "status": f["status"] or "-",
            "missing": "；".join(missing),
        })
        if missing:
            gaps.append(rows[-1])

    # ---------- 写规格文档 ----------
    lines = ["# 功能规格（功能 → 板块 → 按键/交互 → 判据）",
             "",
             "> 这份是**规格**：每个功能「是什么、有什么功能点、对应哪些按键、怎么算做到」。",
             "> 机器可读的那份是 `assets/ui/registry.js`（抽屉与板块由它生成）；",
             "> 缺口清单由 `tools/check_coverage.py` 自动算出来，写在 `docs/覆盖自检.md`。",
             "> 规矩：**新加功能先在这里写清楚，再接控件、再补测试**；三者缺一项就算「开发不足」。",
             "",
             "## 一、功能总表（%d 个）" % len(rows),
             "",
             "| # | 功能 | 在哪 | 板块 | 载体 | 按键（交互） | 输入/下拉 | 判据（测试） | 现状 |",
             "|---|---|---|---|---|---|---|---|---|"]
    for i, r in enumerate(rows, 1):
        lines.append("| %d | %s<br>`%s` | %s | `%s` | %s | %s | %s | %s | %s |" % (
            i, r["name"], r["id"], r["where"], r["board"], r["carrier"],
            r["buttons"] or "—", r["inputs"] or "—", r["tests"] or "**缺**", r["status"]))
    lines += ["", "## 二、逐板块的「功能点」（人话版）", "",
              "上面那张表是机械抽出来的**控件清单**；下面按板块写清楚「它到底要能干哪些事」。",
              "**判据一律是读回来的事实**（队列 JSON / 小点状态 / 下载字节 / DOM 状态），不是「看起来对了」。",
              ""]

    spec_detail = os.path.join(DOCS, "功能规格-逐板块.md")
    if os.path.exists(spec_detail):
        lines.append("（逐板块细节在 `docs/功能规格-逐板块.md`，这里引一下）")
        lines.append("")
        lines.append(read(spec_detail))
    else:
        lines.append("（待补：`docs/功能规格-逐板块.md` 还没写）")

    with open(OUT_SPEC, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

    # ---------- 写缺口清单 ----------
    g = ["# 覆盖自检（功能 vs 交互 vs 测试）",
         "",
         "自动化生成，别手改。跑法：`python3 tools/check_coverage.py`（`--strict` 有缺口就退出码 1）。",
         "",
         "| 指标 | 数 |", "|---|---|",
         "| 功能总数 | %d |" % len(rows),
         "| 有交互（有按键或输入） | %d |" % len([r for r in rows if (r["buttons"] or r["inputs"])]),
         "| 有测试覆盖 | %d |" % len([r for r in rows if r["tests"]]),
         "| **有缺口** | **%d** |" % len(gaps),
         "",
         "## 缺口清单（按用户的话：这些就是「开发不足」的地方）", ""]
    if not gaps:
        g.append("（无缺口）")
    else:
        g += ["| 功能 | 缺什么 | 在哪 |", "|---|---|---|"]
        for r in gaps:
            g.append("| %s<br>`%s` | %s | %s |" % (r["name"], r["id"], r["missing"], r["where"]))
    with open(OUT_GAP, "w", encoding="utf-8") as f:
        f.write("\n".join(g) + "\n")

    print("功能 %d 个：有交互 %d，有测试 %d，缺口 %d" % (
        len(rows), len([r for r in rows if (r["buttons"] or r["inputs"])]),
        len([r for r in rows if r["tests"]]), len(gaps)))
    print("已写：%s\n      %s" % (os.path.relpath(OUT_SPEC, ROOT), os.path.relpath(OUT_GAP, ROOT)))
    for r in gaps[:20]:
        print("  缺口：%s（%s）→ %s" % (r["name"], r["id"], r["missing"]))
    if gaps and args.strict:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
