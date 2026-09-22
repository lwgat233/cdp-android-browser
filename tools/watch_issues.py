#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
tools/watch_issues.py —— 盯 GitHub 议题（"保活"那一半）

用户要求（2026-09-22）：「对于提交的议题，你先自己修改验证，然后提示开发者二次验证，
并写给开发者通俗易懂的验证方法」「每次结束后等待网页的更新，不断使得自己保活」。

这个进程负责"等待更新"这一半：常驻轮询仓库的议题/评论，一有新东西就
① 追加到事件日志  ② 走 ~/.hermes/agent-hooks/notify-needs-you.py 推 QQ + 手机。
它**不**自动改代码 —— 改动由助手在会话里做（自测通过后请用户二次验证）。

用法：
    python3 tools/watch_issues.py --once            # 只跑一轮（看基线，不发通知）
    python3 tools/watch_issues.py --announce        # 先推一条"已上线"，然后常驻
    setsid nohup python3 tools/watch_issues.py >> /vol1/1000/aicache/cdp-watch/watch.log 2>&1 &
停止：pkill -f watch_issues.py

忽略自己的评论：正文里带 `<!--hermes-->` 的（助手发的报告都带这个标记，免得自己刷自己）。
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

REPO = os.environ.get("CDP_REPO", "lwgat233/cdp-android-browser")
GH = os.environ.get("GH_BIN", str(Path.home() / ".local/bin/gh"))
NOTIFY = os.environ.get("NOTIFY_HOOK", str(Path.home() / ".hermes/agent-hooks/notify-needs-you.py"))
STATE_DIR = Path(os.environ.get("CDP_WATCH_DIR", "/vol1/1000/aicache/cdp-watch"))
STATE_PATH = STATE_DIR / "issues-state.json"
EVENTS_PATH = STATE_DIR / "events.log"
MARKER = os.environ.get("CDP_WATCH_MARKER", "<!--hermes-->")


def log(msg: str, quiet: bool = False) -> None:
    if quiet:
        return
    line = time.strftime("%Y-%m-%d %H:%M:%S ") + msg
    print(line, flush=True)


def event(msg: str, quiet: bool = False) -> None:
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    with EVENTS_PATH.open("a", encoding="utf-8") as f:
        f.write(time.strftime("%Y-%m-%d %H:%M:%S ") + msg + "\n")
    log("EVENT " + msg, quiet)


def notify(title: str, body: str, quiet: bool = False) -> None:
    try:
        subprocess.run([sys.executable, NOTIFY, "--say", "issue", title, body],
                       timeout=25, capture_output=True, text=True)
        log(f"已推送: {title} | {body[:80]}", quiet)
    except Exception as e:  # noqa: BLE001
        log(f"推送失败（不影响盯守）: {e}", quiet)


def gh_json(*args: str):
    try:
        r = subprocess.run([GH, *args], capture_output=True, text=True, timeout=60)
        if r.returncode != 0:
            log(f"gh 失败: {' '.join(args)} → {r.stderr.strip()[:120]}")
            return None
        return json.loads(r.stdout or "null")
    except Exception as e:  # noqa: BLE001
        log(f"gh 异常: {e}")
        return None


def snapshot():
    """所有议题 + 每条的最新状态与评论指纹（不含自己标记的评论）。"""
    issues = gh_json("issue", "list", "-R", REPO, "--state", "all", "--limit", "50",
                     "--json", "number,title,state,updatedAt,comments")
    if issues is None:
        return None
    out = {}
    for it in issues:
        comments = it.get("comments") or []
        fresh = [c for c in comments if MARKER not in (c.get("body") or "")]
        out[str(it["number"])] = {
            "title": it.get("title") or "",
            "state": it.get("state") or "",
            "updatedAt": it.get("updatedAt") or "",
            "comments": len(comments),
            "fresh": len(fresh),
            "last": (fresh[-1].get("body") if fresh else "") or "",
        }
    return out


def load_state() -> dict:
    try:
        return json.loads(STATE_PATH.read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001
        return {}


def save_state(s: dict) -> None:
    STATE_DIR.mkdir(parents=True, exist_ok=True)
    STATE_PATH.write_text(json.dumps(s, ensure_ascii=False, indent=1), encoding="utf-8")


def diff(old: dict, new: dict):
    """返回 (新议题, 有变化的议题) 两份列表，元素是 (number, 说明文字)。"""
    added, changed = [], []
    for num, st in new.items():
        o = old.get(num)
        if o is None:
            added.append((num, f"新议题 #{num}「{st['title']}」"))
            continue
        bits = []
        if st["fresh"] > o.get("fresh", 0):
            bits.append(f"新增 {st['fresh'] - o.get('fresh', 0)} 条评论")
        if st["state"] != o.get("state"):
            bits.append(f"状态 {o.get('state')} → {st['state']}")
        if bits:
            snippet = " ".join((st["last"] or "").split())[:70]
            changed.append((num, f"议题 #{num}「{st['title']}」：" + "，".join(bits) + (f" ｜ 最新：{snippet}" if snippet else "")))
    return added, changed


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--once", action="store_true", help="只跑一轮就退出")
    ap.add_argument("--interval", type=int, default=int(os.environ.get("CDP_WATCH_INTERVAL", "90")))
    ap.add_argument("--announce", action="store_true", help="先推一条'已上线'再常驻")
    ap.add_argument("--quiet", action="store_true", help="不打日志（cron 里用；通知仍会推）")
    args = ap.parse_args()
    q = args.quiet

    state = load_state()
    if args.announce:
        notify("议题盯守已上线", f"{REPO}：有新议题/新评论会推给你（每 {args.interval}s 轮询一次）", q)

    first = True
    while True:
        snap = snapshot()
        if snap is not None:
            if not state:
                save_state(snap)
                event(f"基线已建立：{len(snap)} 条议题", q)
                if not args.once:
                    log("基线已建立，开始盯守", q)
            else:
                added, changed = diff(state, snap)
                for num, text in added + changed:
                    event(text, q)
                    notify(f"CDP 议题 #{num}", text, q)
                if added or changed:
                    state.update(snap)
                    save_state(state)
                else:
                    save_state({**state, **{k: v for k, v in snap.items()}})
                    state = {**state, **snap}
        if args.once:
            return 0
        first = False
        time.sleep(args.interval)


if __name__ == "__main__":
    sys.exit(main())
