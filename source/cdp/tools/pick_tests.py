#!/usr/bin/env python3
"""按功能 id 或改动文件，挑出"该跑的那几个验收脚本"（不跑全量）。

数据源 tools/test_map.json：功能 id → 脚本列表（空列表＝这个功能还没有测试，本身就是要报的缺口）。
用法：
  python3 tools/pick_tests.py --feature apps
  python3 tools/pick_tests.py --files app/src/main/assets/ui/modules/appgrid.js
  python3 tools/pick_tests.py --list            # 看看哪些功能还没有测试
"""
import argparse
import json
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAP = os.path.join(ROOT, "tools", "test_map.json")
ENV = dict(os.environ, PATH="/home/lwgat/.local/bin:/home/lwgat/tools/android-sdk/platform-tools:" + os.environ.get("PATH", ""))
ap = argparse.ArgumentParser()
ap.add_argument("--feature", action="append", default=[])
ap.add_argument("--files", nargs="*", default=[])
ap.add_argument("--list", action="store_true")
args = ap.parse_args()

m = json.load(open(MAP))
if args.list:
    empty = sorted([k for k, v in m.items() if not v])
    print("还没有测试的功能（%d 个）：" % len(empty))
    for k in empty:
        print("   " + k)
    sys.exit(0)

scripts = []
for f in args.feature:
    hit = [k for k in m if k == f or k.startswith(f + ".") or f in k]
    if not hit:
        print("  功能 id 不在 test_map 里：%s（要么拼错了，要么这个功能还没登记）" % f)
    for k in hit:
        scripts += m[k]

# 按改动文件猜：文件名去掉扩展名后跟脚本名的关键词对上（例：appgrid.js → verify_appgrid.py）
for path in args.files:
    base = os.path.basename(path).split(".")[0].lower()
    if base in ("app", "index", "style", "registry", "transport"):
        continue
    for s in os.listdir(os.path.join(ROOT, "tools")):
        if s.startswith("verify_") and base and base in s.lower():
            scripts.append(s)

scripts = sorted(set(scripts))
if not scripts:
    print("  没找到对应的验收脚本 → 说明这个改动还没配测试（这本身就是缺口，记进登记）")
    sys.exit(0)

print("  要跑 %d 个脚本：%s" % (len(scripts), "，".join(scripts)))
fail = 0
for s in scripts:
    p = os.path.join(ROOT, "tools", s)
    if not os.path.exists(p):
        print("  （缺文件）%s" % s)
        continue
    r = subprocess.run(["timeout", "420", "python3", "-u", p], cwd=ROOT, env=ENV,
                       capture_output=True, text=True)
    tail = [l for l in (r.stdout or "").strip().splitlines() if l.strip()][-4:]
    print("  ── %s rc=%d" % (s, r.returncode))
    for l in tail:
        print("     " + l[:160])
    if r.returncode != 0:
        fail += 1
print("  局部验收：%d 个脚本，%d 个没过" % (len(scripts), fail))
sys.exit(1 if fail else 0)
