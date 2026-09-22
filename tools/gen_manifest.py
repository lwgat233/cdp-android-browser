#!/usr/bin/env python3
"""重新生成归档清单 MANIFEST.txt（项目内逐文件大小 + sha256）。

约定（这个项目一直这么归档）：
  · 清单只列"归档里该有的东西"：docs / evidence / env / apk / tools / README / source 快照；
  · 构建中间产物（app/build、.gradle、local.properties 之类）不进清单；
  · 交付构件单独在头部写清楚大小与 sha256 —— 报告里引用的就是这个值。
用法：python3 tools/gen_manifest.py
"""
import hashlib
import os
import time

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
SKIP_DIRS = {"build", ".gradle", ".git", "outputs", "tmp", "kotlin"}
PREFIX_ORDER = ["README.md", "MANIFEST.txt", "apk/", "docs/", "evidence/", "env/", "tools/", "source/"]


def sha256(path, limit=None):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            b = f.read(1024 * 256)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def skip(path):
    parts = set(os.path.relpath(path, ROOT).split(os.sep))
    return bool(parts & SKIP_DIRS) or os.path.relpath(path, ROOT).startswith("source/cdp/app/build")


rows = []
for dirpath, dirnames, filenames in os.walk(ROOT):
    dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
    for fn in sorted(filenames):
        p = os.path.join(dirpath, fn)
        rel = os.path.relpath(p, ROOT)
        if skip(p) or rel.endswith(".pyc") or "__pycache__" in rel:
            continue
        rows.append((rel.replace(os.sep, "/"), os.path.getsize(p), sha256(p)))

rows.sort(key=lambda r: (next((i for i, pre in enumerate(PREFIX_ORDER) if r[0].startswith(pre)), 99), r[0]))
apk = os.path.join(ROOT, "apk", "cdp-debug.apk")
apk_line = ""
if os.path.exists(apk):
    apk_line = "交付构件：apk/cdp-debug.apk  %d 字节  sha256 %s\n" % (os.path.getsize(apk), sha256(apk))

out = ["CDP 归档清单（MANIFEST）：%d 个文件\n" % len(rows),
       "生成时间：%s\n" % time.strftime("%Y-%m-%d %H:%M:%S CST"),
       apk_line, "\n"]
out += ["%10d  %s  %s\n" % (size, digest[:16], rel) for rel, size, digest in rows]
open(os.path.join(ROOT, "MANIFEST.txt"), "w").write("".join(out))

# apk/apk-sha256.txt 也一起刷新
apkdir = os.path.join(ROOT, "apk")
if os.path.isdir(apkdir):
    lines = []
    for fn in sorted(os.listdir(apkdir)):
        if fn.endswith(".apk"):
            p = os.path.join(apkdir, fn)
            lines.append("%s  %d 字节\nsha256 %s\n" % (fn, os.path.getsize(p), sha256(p)))
    open(os.path.join(apkdir, "apk-sha256.txt"), "w").write("".join(lines))
print("MANIFEST 已更新：%d 个文件" % len(rows))
