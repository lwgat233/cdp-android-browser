#!/usr/bin/env bash
# 看 /vol1/1000/mydoc/log.md 有没有新增需求（跟上次保存的哈希比），有就同步到项目里
SRC=/vol1/1000/mydoc/log.md
DST=/vol1/1000/airesults/cdp/docs/需求清单-来自mydoc.md
H=/vol1/1000/airesults/cdp/docs/.reqs.sha
NEW=$(sha256sum "$SRC" 2>/dev/null | awk '{print $1}')
OLD=$(cat "$H" 2>/dev/null || echo "")
if [ "$NEW" != "$OLD" ]; then
  cp "$SRC" "$DST"; printf '%s\n' "$NEW" > "$H"
  echo "需求有更新：已同步到 $DST（$(grep -c '' "$DST") 行）"
else
  echo "需求没变化"
fi
