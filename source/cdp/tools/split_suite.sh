#!/usr/bin/env bash
# 把第七组（verify_browser.sh）按段落切成可单独跑的一段：长跑会把模拟器跑崩，
# 拆开跑是**同一批断言**，只是分两次执行（每段都在模拟器崩溃窗口之内）。
# 用法：bash tools/split_suite.sh <起始段号> <结束段号> [输出文件]
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/tools/verify_browser.sh"
FROM="${1:-1}"; TO="${2:-32}"
OUT="${3:-/tmp/cdp_part_${FROM}_${TO}.sh}"

python3 - "$SRC" "$FROM" "$TO" "$OUT" <<'PY'
import sys, re
src, frm, to, out = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
lines = open(src, encoding='utf-8').read().split('\n')
starts = [i for i, l in enumerate(lines) if l.startswith('say "第七组')]
assert starts, '没找到段落'
header = lines[:starts[0]]
footer_i = None
for i, l in enumerate(lines):
    if l.startswith('printf') and '第七组小结' in l:
        footer_i = i
        break
assert footer_i is not None, '没找到小结行'
footer = lines[footer_i:]
bounds = starts + [footer_i]
body = []
for idx in range(frm - 1, min(to, len(starts))):
    body += lines[bounds[idx]:bounds[idx + 1]]
# 分段脚本在 /tmp 下跑，dirname $0 就不是项目目录了 → 把 _lib.sh 换成绝对路径
ROOT = __import__('os').path.dirname(__import__('os').path.dirname(__import__('os').path.abspath(src)))
for i, l in enumerate(header):
    if '_lib.sh' in l and '. ' in l:
        # 分段脚本在 /tmp 下跑：_lib.sh 用 $0 推项目目录会推成 /tmp，
        # 于是 cdp.mjs 之类的路径全错（界面类断言会全空）——所以这里显式钉住 ROOT。
        header[i] = ('. "%s/tools/_lib.sh"\nROOT="%s"\nNODE="$(command -v node || echo \"$HOME/.local/bin/node\")"' % (ROOT, ROOT))
res = header + ['# [分段跑] 只跑第 %d–%d 段；整份脚本仍在 tools/verify_browser.sh' % (frm, to)] + body + footer
open(out, 'w', encoding='utf-8').write('\n'.join(res))
print('写出 %s：段落 %d–%d，共 %d 行' % (out, frm, to, len(res)))
PY
bash -n "$OUT" && echo "分段脚本语法 OK：$OUT"
