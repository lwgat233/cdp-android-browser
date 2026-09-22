#!/usr/bin/env python3
"""接口文档生成器：从 App 自己的**接口目录**生成 docs/接口文档.md（不手写、不会和代码漂移）。

App 里有一份接口目录（`ApiCatalog.kt`），控制口 `/api/catalog` 能读出来。
这个脚本把它拉下来，按前缀分组，写成一张人看的表：
  方法/路径 | 说明 | 需要令牌 | 参数 | 回包要点
用法：python3 tools/gen_api_doc.py    （需要 App 在跑；生成到 ../../docs/接口文档.md）
"""
import json
import os
import subprocess
import sys
import time

ROOT = "/vol1/1000/airesults/cdp"
SRC = ROOT + "/source/cdp"
HTTP = "http://127.0.0.1:8848"
OUT = ROOT + "/docs/接口文档.md"
ENV = dict(os.environ, PATH="/home/lwgat/.local/bin:" + os.environ.get("PATH", ""))


def api(path, timeout=25):
    r = subprocess.run(["curl", "-s", "--max-time", str(timeout), HTTP + path],
                       capture_output=True, text=True, timeout=timeout + 10, env=ENV)
    try:
        return json.loads(r.stdout)
    except Exception:
        return {}


if not api("/api/status").get("ok"):
    print("环境没起来：控制口不通，先跑 tools/env_health.py")
    sys.exit(2)

cat = api("/api/catalog")
items = cat.get("list") or cat.get("items") or []
if not items:
    print("接口目录读不到（/api/catalog 回包：%s）" % json.dumps(cat)[:200])
    sys.exit(1)

# 分组：按路径的第一段（/api/<组>/…）
groups = {}
for it in items:
    path = it.get("path") or it.get("url") or it.get("name") or "?"
    key = path.split("/")[2] if path.startswith("/api/") and len(path.split("/")) > 2 else "其它"
    groups.setdefault(key, []).append(it)

lines = []
lines.append("# CDP 控制口接口文档（自动生成，别手改）")
lines.append("")
lines.append("生成时间：%s" % time.strftime("%Y-%m-%d %H:%M:%S"))
lines.append("")
lines.append("来源：App 内 `ApiCatalog.kt` → 控制口 `/api/catalog`（%d 个接口）。"
             "生成脚本 `source/cdp/tools/gen_api_doc.py`，重跑即刷新。" % len(items))
lines.append("")
lines.append("**通用约定**")
lines.append("")
lines.append("- 只监听本机：`http://127.0.0.1:8848`（手机自己调不需要令牌）。")
lines.append("- 从**局域网**访问（别的电脑/手机）：必须带令牌（`?token=…` 或 `X-CDP-Token`），否则 403；"
             "令牌在 App 的「控制口」栏目里看。")
lines.append("- 敏感接口默认关（网页控制、下载到系统、AI 上下文等），要去「控制口」里显式打开。")
lines.append("- 回包统一 JSON；出错是 `{\"ok\":false,\"error\":\"…\"}`。")
lines.append("- 页面类接口（goto / eval / click / query / diag）在**浏览器那个 WebView** 里执行；"
             "控制台界面走的是同一套 op（`CDPT.call(op, args)`）。")
lines.append("")

for key in sorted(groups):
    lines.append("## %s" % key)
    lines.append("")
    lines.append("| 方法 | 路径 | 说明 | 需要令牌 | 参数 |")
    lines.append("|---|---|---|---|---|")
    for it in sorted(groups[key], key=lambda x: (x.get("path") or "")):
        path = it.get("path") or it.get("url") or "?"
        desc = (it.get("desc") or it.get("note") or it.get("summary") or "").replace("|", "／")
        method = it.get("method") or "GET"
        tok = it.get("needToken")
        tok_s = "—" if tok is None else ("是" if tok else "否")
        params = it.get("params") or it.get("args") or ""
        if isinstance(params, (list, dict)):
            params = json.dumps(params, ensure_ascii=False)
        lines.append("| %s | `%s` | %s | %s | %s |" % (method, path, desc, tok_s, str(params).replace("|", "／")))
    lines.append("")

os.makedirs(os.path.dirname(OUT), exist_ok=True)
open(OUT, "w").write("\n".join(lines) + "\n")
print("已生成 %s（%d 个接口，%d 个分组）" % (OUT, len(items), len(groups)))
